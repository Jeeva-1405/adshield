package com.jeeva.adshield.core.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.jeeva.adshield.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * VPN service that intercepts DNS queries and drops requests to known ad domains.
 * Non-ad queries are forwarded transparently to 8.8.8.8.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.jeeva.adshield.dns.START"
        const val ACTION_STOP  = "com.jeeva.adshield.dns.STOP"

        // VPN tunnel addressing
        private const val VPN_ADDR = "10.111.0.1"
        private const val DNS_ADDR = "10.111.0.2"   // fake DNS server pointing into the tunnel
        private const val UPSTREAM  = "8.8.8.8"

        private const val NOTIF_CHANNEL = "adshield_dns"
        private const val NOTIF_ID = 1001

        /** True while the service is running; read by ViewModel to update UI. */
        @Volatile
        var isRunning = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var blocklist: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())
        scope.launch {
            blocklist = BlocklistRepository.load(applicationContext)
            establishTunnel()
        }
        return START_STICKY
    }

    private fun establishTunnel() {
        tun = Builder()
            .setSession("AdShield")
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)   // only route fake DNS through tunnel
            .setBlocking(true)
            .setMtu(1500)
            .establish() ?: return

        val input  = FileInputStream(tun!!.fileDescriptor)
        val output = FileOutputStream(tun!!.fileDescriptor)

        // Single writer coroutine avoids concurrent writes to the TUN fd
        scope.launch {
            for (pkt in outQueue) {
                try { output.write(pkt) } catch (_: Exception) { break }
            }
        }

        scope.launch {
            val buf = ByteArray(32767)
            try {
                while (isActive) {
                    val len = input.read(buf)
                    if (len > 0) dispatch(buf.copyOf(len))
                }
            } catch (_: Exception) { /* fd closed on stop */ }
        }
    }

    private fun dispatch(pkt: ByteArray) {
        if (pkt.size < 28) return
        if (pkt[9].toInt() and 0xFF != 17) return          // not UDP
        val ipLen = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.u16(ipLen + 2) != 53) return               // dest port ≠ 53
        val dnsOff = ipLen + 8
        val domain = extractDomain(pkt, dnsOff) ?: return

        if (isBlocked(domain)) {
            outQueue.trySend(nxDomainReply(pkt, ipLen, dnsOff))
        } else {
            scope.launch { forwardUpstream(pkt, ipLen, dnsOff) }
        }
    }

    private fun isBlocked(domain: String): Boolean =
        blocklist.contains(domain) ||
        blocklist.any { b -> domain.length > b.length && domain.endsWith(".$b") }

    private fun extractDomain(pkt: ByteArray, dnsOff: Int): String? {
        if (dnsOff + 12 >= pkt.size) return null
        val sb = StringBuilder()
        var pos = dnsOff + 12  // skip 12-byte DNS header
        while (pos < pkt.size) {
            val labelLen = pkt[pos].toInt() and 0xFF
            if (labelLen == 0) break
            if (pos + 1 + labelLen > pkt.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(pkt, pos + 1, labelLen, Charsets.US_ASCII))
            pos += 1 + labelLen
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    private fun nxDomainReply(pkt: ByteArray, ipLen: Int, dnsOff: Int): ByteArray {
        val dns = pkt.copyOfRange(dnsOff, pkt.size)
        dns[2] = (dns[2].toInt() or 0x80).toByte()            // QR = response
        dns[3] = ((dns[3].toInt() and 0xF0) or 0x03).toByte() // RCODE = NXDOMAIN
        dns[6] = 0; dns[7] = 0                                 // ANCOUNT = 0
        dns[8] = 0; dns[9] = 0                                 // NSCOUNT = 0
        dns[10] = 0; dns[11] = 0                               // ARCOUNT = 0
        return ipUdpPacket(
            srcIp  = pkt.copyOfRange(16, 20),
            dstIp  = pkt.copyOfRange(12, 16),
            srcPort = 53,
            dstPort = pkt.u16(ipLen),   // original UDP source port
            payload = dns,
        )
    }

    private suspend fun forwardUpstream(pkt: ByteArray, ipLen: Int, dnsOff: Int) {
        val srcPort = pkt.u16(ipLen)
        val dns = pkt.copyOfRange(dnsOff, pkt.size)
        try {
            val sock = DatagramSocket()
            protect(sock)
            sock.soTimeout = 4000
            sock.send(DatagramPacket(dns, dns.size, InetAddress.getByName(UPSTREAM), 53))
            val resp = ByteArray(1024)
            val dp = DatagramPacket(resp, resp.size)
            sock.receive(dp)
            sock.close()
            val reply = ipUdpPacket(
                srcIp  = pkt.copyOfRange(16, 20),
                dstIp  = pkt.copyOfRange(12, 16),
                srcPort = 53,
                dstPort = srcPort,
                payload = resp.copyOf(dp.length),
            )
            withContext(Dispatchers.IO) { outQueue.send(reply) }
        } catch (_: Exception) { /* drop on error or timeout */ }
    }

    private fun ipUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45.toByte()                              // IPv4, IHL=5
        b[2] = (total ushr 8).toByte(); b[3] = (total and 0xFF).toByte()
        b[6] = 0x40.toByte()                              // DF flag
        b[8] = 64                                          // TTL
        b[9] = 17                                          // protocol = UDP
        srcIp.copyInto(b, 12); dstIp.copyInto(b, 16)
        val chk = ipChecksum(b)
        b[10] = (chk ushr 8).toByte(); b[11] = (chk and 0xFF).toByte()
        val udpLen = 8 + payload.size
        b[20] = (srcPort ushr 8).toByte(); b[21] = (srcPort and 0xFF).toByte()
        b[22] = (dstPort ushr 8).toByte(); b[23] = (dstPort and 0xFF).toByte()
        b[24] = (udpLen ushr 8).toByte(); b[25] = (udpLen and 0xFF).toByte()
        payload.copyInto(b, 28)
        return b
    }

    private fun ipChecksum(buf: ByteArray): Int {
        var sum = 0
        for (i in 0 until 20 step 2) sum += buf.u16(i)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun ByteArray.u16(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun createChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "DNS Blocker", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "AdShield DNS ad blocking is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, DnsVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("AdShield active")
            .setContentText("DNS ad blocking is on")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        outQueue.close()
        scope.cancel()
        tun?.close()
        tun = null
        super.onDestroy()
    }
}

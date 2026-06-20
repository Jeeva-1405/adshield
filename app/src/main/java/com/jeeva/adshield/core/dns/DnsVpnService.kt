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
import com.jeeva.adshield.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

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

        /** Cumulative DNS queries blocked since the service started. */
        val blockedCount = AtomicLong(0)

        /** Cumulative DNS queries allowed by the whitelist since the service started. */
        val whitelistedCount = AtomicLong(0)

        private val _recentlyBlocked = MutableStateFlow<List<String>>(emptyList())

        /** The last 20 blocked domains, most recent first. */
        val recentlyBlocked: StateFlow<List<String>> = _recentlyBlocked.asStateFlow()

        /** Prepends [domain] to the list; deduplicates consecutive identical queries. */
        fun addRecentlyBlocked(domain: String) {
            val cur = _recentlyBlocked.value
            if (cur.firstOrNull() == domain) return
            _recentlyBlocked.value = (listOf(domain) + cur).take(20)
        }

        /** Removes [domain] from the list (called after user taps Allow). */
        fun removeRecentlyBlocked(domain: String) {
            _recentlyBlocked.value = _recentlyBlocked.value.filter { it != domain }
        }

        /** Resets counters and clears the recently-blocked list; call when the service starts. */
        fun resetCounters() {
            blockedCount.set(0)
            whitelistedCount.set(0)
            _recentlyBlocked.value = emptyList()
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var blocklist: Set<String> = emptySet()

    // @Volatile so that the whitelist-sync coroutine's writes are visible to the packet-dispatch
    // coroutine, which may run on a different IO thread.
    @Volatile private var whitelist: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        resetCounters()
        startForeground(NOTIF_ID, buildNotification())
        scope.launch {
            val prefs = AppPreferences(applicationContext)
            blocklist = BlocklistRepository.load(applicationContext)
            whitelist = WhitelistRepository.load(applicationContext, prefs)
            // Keep whitelist live — when user taps Allow the change takes effect immediately
            launch { prefs.userWhitelistFlow.collect { whitelist = it } }
            establishTunnel()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        isRunning = false
        super.onRevoke()
    }

    private fun establishTunnel() {
        tun = Builder()
            .setSession("AdShield")
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)      // fake DNS
            // Route popular DoH/DoT resolvers through the tunnel so we can drop them,
            // forcing Chrome and other apps to fall back to plain system DNS (which we intercept).
            .addRoute("8.8.8.8", 32)    // Google DoH
            .addRoute("8.8.4.4", 32)    // Google DoH alternate
            .addRoute("1.1.1.1", 32)    // Cloudflare DoH
            .addRoute("1.0.0.1", 32)    // Cloudflare DoH alternate
            .addRoute("9.9.9.9", 32)    // Quad9 DoH
            .addRoute("149.112.112.112", 32) // Quad9 secondary
            .addRoute("208.67.222.222", 32)  // OpenDNS
            .addRoute("208.67.220.220", 32)  // OpenDNS
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
        if (pkt.size < 20) return
        val ipLen = (pkt[0].toInt() and 0x0F) * 4
        if (ipLen + 4 > pkt.size) return
        val dstPort = pkt.u16(ipLen + 2)

        // Drop DoH (port 443) and DoT (port 853) to known public resolvers.
        // This forces Chrome out of its DoH fast-path and back to plain UDP DNS,
        // which we intercept and filter.
        if ((dstPort == 443 || dstPort == 853) && isDohProvider(pkt)) return

        // Only handle UDP DNS from here on
        if (pkt.size < 28) return
        if (pkt[9].toInt() and 0xFF != 17) return          // not UDP
        if (dstPort != 53) return                           // dest port ≠ 53
        val dnsOff = ipLen + 8
        val domain = extractDomain(pkt, dnsOff) ?: return

        when {
            matchesSet(domain, whitelist) -> {
                whitelistedCount.incrementAndGet()
                scope.launch { forwardUpstream(pkt, ipLen, dnsOff) }
            }
            matchesSet(domain, blocklist) -> {
                blockedCount.incrementAndGet()
                addRecentlyBlocked(domain)
                outQueue.trySend(nxDomainReply(pkt, ipLen, dnsOff))
            }
            else -> scope.launch { forwardUpstream(pkt, ipLen, dnsOff) }
        }
    }

    /** Checks destination IP bytes 16-19 against known DoH/DoT provider addresses. */
    private fun isDohProvider(pkt: ByteArray): Boolean {
        val a = pkt[16].toInt() and 0xFF
        val b = pkt[17].toInt() and 0xFF
        val c = pkt[18].toInt() and 0xFF
        val d = pkt[19].toInt() and 0xFF
        return (a == 8   && b == 8   && c == 8   && d == 8)   ||  // 8.8.8.8
               (a == 8   && b == 8   && c == 4   && d == 4)   ||  // 8.8.4.4
               (a == 1   && b == 1   && c == 1   && d == 1)   ||  // 1.1.1.1
               (a == 1   && b == 0   && c == 0   && d == 1)   ||  // 1.0.0.1
               (a == 9   && b == 9   && c == 9   && d == 9)   ||  // 9.9.9.9
               (a == 149 && b == 112 && c == 112 && d == 112) ||  // 149.112.112.112
               (a == 208 && b == 67  && c == 222 && d == 222) ||  // 208.67.222.222
               (a == 208 && b == 67  && c == 220 && d == 220)     // 208.67.220.220
    }

    /**
     * O(k) subdomain lookup — checks the domain and each of its parent labels against [set].
     * k = number of domain labels (typically 2-3), so this is constant-time in practice
     * regardless of how large the blocklist grows.
     */
    private fun matchesSet(domain: String, set: Set<String>): Boolean {
        if (set.contains(domain)) return true
        var dot = domain.indexOf('.')
        while (dot != -1) {
            if (set.contains(domain.substring(dot + 1))) return true
            dot = domain.indexOf('.', dot + 1)
        }
        return false
    }

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
            val resp = ByteArray(4096)
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

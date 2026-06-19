package com.jeeva.adshield.core.detector

import android.content.pm.PackageManager
import android.os.Build

/** Detects installation status for all 4 target apps. */
class AppDetector(private val packageManager: PackageManager) {

    /** Returns a map of package name → status for every target app. */
    fun detectAll(): Map<String, TargetAppStatus> =
        TargetApps.ALL.associateWith { pkg -> detectStatus(pkg) }

    private fun detectStatus(pkg: String): TargetAppStatus {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return TargetAppStatus.NotInstalled
        }
        return TargetAppStatus.Installed(info.versionName ?: "unknown")
    }
}

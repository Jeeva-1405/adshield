package com.jeeva.adshield.core.detector

import android.content.pm.PackageManager
import android.os.Build

/** Returns true if the given package is installed on the device. */
fun PackageManager.isInstalled(pkg: String): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(pkg, 0)
    }
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

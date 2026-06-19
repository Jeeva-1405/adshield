package com.jeeva.adshield.core.detector

/** Represents the current state of a target app on the device. */
sealed class TargetAppStatus {
    object NotInstalled : TargetAppStatus()
    data class Installed(val versionName: String) : TargetAppStatus()
    object BlockerActive : TargetAppStatus()
    object Patched : TargetAppStatus()
}

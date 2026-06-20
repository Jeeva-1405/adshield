package com.jeeva.adshield.core.detector

/** Hardcoded package name constants for the 4 supported target apps. */
object TargetApps {
    const val CHROME        = "com.android.chrome"
    const val YOUTUBE       = "com.google.android.youtube"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val SPOTIFY       = "com.spotify.music"
    const val XMANAGER      = "com.xManager.app"

    val ALL = listOf(CHROME, YOUTUBE, YOUTUBE_MUSIC, SPOTIFY)
}

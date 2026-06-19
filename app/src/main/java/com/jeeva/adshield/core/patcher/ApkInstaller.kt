package com.jeeva.adshield.core.patcher

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    /** Hands the APK file to Android's system package installer. */
    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

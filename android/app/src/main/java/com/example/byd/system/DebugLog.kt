package com.example.byd.system

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.byd.BuildConfig
import com.example.byd.R
import com.example.byd.data.CarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Share debug log: the phone's command log (and the watch's, when it answers within a
 * few seconds) as one text file, through the system share sheet. Already redacted: no server
 * address, password, VIN or position.
 */
object DebugLog {
    suspend fun share(context: Context) {
        val repo = CarRepository.get(context)
        val watch = WearSync.requestWatchLog(context)
        val phone = withContext(Dispatchers.IO) { repo.log.export() }
        val text = buildString {
            appendLine("BYD Pro ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Exported ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} · server ${repo.bus.server.kind}")
            appendLine()
            appendLine("===== phone =====")
            append(phone)
            appendLine()
            appendLine("===== watch =====")
            append(watch ?: "(no answer from the watch)")
            appendLine()
        }
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "share").apply { mkdirs() }.resolve("byd-debug-log.txt").apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "BYD Pro debug log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_debug_log)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

package com.nax.atsupager.ui.screens.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object MainUiUtils {
    fun formatDate(timestamp: Long, context: Context): String {
        val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            isSameDay(messageDate, today) -> context.getString(R.string.today)
            isSameDay(messageDate, yesterday) -> context.getString(R.string.yesterday)
            else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun formatDurationMillis(millis: Long): String {
        if (millis < 0) return "0:00"
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    fun formatDuration(seconds: Int?): String {
        if (seconds == null || seconds < 0) return "0:00"
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    fun formatFileSize(size: Long): String {
        return when {
            size > 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            size > 1024 -> "${size / 1024} KB"
            else -> "$size B"
        }
    }

    fun getCoilModel(message: ChatMessage): Any? {
        val path = message.localFilePath ?: return null
        return when {
            path.endsWith(".enc") -> message 
            path.startsWith("content://") -> Uri.parse(path)
            else -> File(path)
        }
    }

    fun openFile(context: Context, message: ChatMessage) {
        val localPath = message.localFilePath ?: return
        val uri = if (localPath.startsWith("content://")) {
            localPath.toUri()
        } else {
            val file = File(localPath)
            if (!file.exists()) return

            if (localPath.endsWith(".enc")) {
                // Используем наш безопасный провайдер для дешифровки в RAM
                Uri.parse("content://${context.packageName}.decrypted.provider$localPath")
            } else {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            }
        }

        // Пытаемся получить MIME-тип: из сообщения -> из провайдера -> по расширению
        val mime = message.mimeType 
            ?: context.contentResolver.getType(uri) 
            ?: getMimeTypeFromPath(localPath)
            ?: "application/octet-stream"

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            val chooser = Intent.createChooser(viewIntent, context.getString(R.string.open_with))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeTypeFromPath(path: String): String? {
        val extension = path.removeSuffix(".enc").substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    fun createTempUri(context: Context, extension: String): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "MEDIA_$timestamp.$extension")
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}

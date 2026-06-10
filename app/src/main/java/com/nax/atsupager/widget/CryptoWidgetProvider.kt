package com.nax.atsupager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.nax.atsupager.MainActivity
import com.nax.atsupager.R
import com.nax.atsupager.webrtc.NtfyService

class CryptoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("AtsuPagerPrefs", Context.MODE_PRIVATE)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, NtfyService.isRunning, prefs)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_SERVICE) {
            if (NtfyService.isRunning) {
                NtfyService.stop(context)
            } else {
                NtfyService.start(context)
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE_SERVICE = "com.nax.atsupager.widget.TOGGLE_SERVICE"

        fun updateStatus(context: Context, isRunning: Boolean) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CryptoWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            val prefs = context.getSharedPreferences("AtsuPagerPrefs", Context.MODE_PRIVATE)
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId, isRunning, prefs)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, isRunning: Boolean, prefs: SharedPreferences) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            if (isRunning) {
                views.setTextViewText(R.id.status_text, "В сети")
                views.setInt(R.id.status_icon, "setColorFilter", Color.GREEN)
                views.setImageViewResource(R.id.btn_toggle_service, android.R.drawable.ic_lock_power_off)
                
                // Формируем строку инфо из SharedPreferences
                val missed = prefs.getInt(NtfyService.PREF_MISSED_CALLS, 0)
                val messages = prefs.getInt(NtfyService.PREF_NEW_MESSAGES, 0)
                
                val info = when {
                    missed > 0 && messages > 0 -> "$missed зв., $messages сообщ."
                    missed > 0 -> "$missed пропущенных"
                    messages > 0 -> "$messages новых сообщ."
                    else -> "Нет новых уведомлений"
                }
                views.setTextViewText(R.id.info_text, info)
                views.setViewVisibility(R.id.info_text, View.VISIBLE)
            } else {
                views.setTextViewText(R.id.status_text, "Оффлайн")
                views.setInt(R.id.status_icon, "setColorFilter", Color.GRAY)
                views.setImageViewResource(R.id.btn_toggle_service, android.R.drawable.ic_media_play)
                views.setViewVisibility(R.id.info_text, View.GONE)
            }

            // Intent to open App
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("clear_counters", true)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.status_container, openAppPendingIntent)

            // Intent to toggle Service
            val toggleIntent = Intent(context, CryptoWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_SERVICE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 1, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.btn_toggle_service, togglePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

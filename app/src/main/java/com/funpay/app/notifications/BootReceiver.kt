package com.funpay.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — BroadcastReceiver для восстановления Worker после перезагрузки устройства.
 * WorkManager обычно восстанавливается автоматически, но этот ресивер
 * гарантирует перезапуск при необходимости.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted, rescheduling FunPay worker")
            val prefs = context.getSharedPreferences("funpay_prefs", Context.MODE_PRIVATE)
            val notificationsEnabled = prefs.getBoolean("notifications_enabled", true)
            if (notificationsEnabled) {
                FunPayWorker.scheduleWork(context)
            }
        }
    }
}

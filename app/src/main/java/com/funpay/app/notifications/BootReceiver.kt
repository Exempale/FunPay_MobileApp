package com.funpay.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.funpay.app.utils.Prefs

/**
 * BootReceiver — восстанавливает фоновые проверки после перезагрузки
 * устройства и после обновления приложения.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.d("BootReceiver", "Received $action, restoring notifications")
        if (!Prefs.notificationsEnabled(context)) return

        NotificationHelper.createNotificationChannels(context)
        FunPayWorker.scheduleWork(context)
        ChatWatchService.start(context)
    }
}

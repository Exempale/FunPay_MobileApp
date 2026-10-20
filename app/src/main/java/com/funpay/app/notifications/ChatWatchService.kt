package com.funpay.app.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.funpay.app.utils.NetworkHelper
import com.funpay.app.utils.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ChatWatchService — «мгновенные» уведомления.
 *
 * WorkManager даёт минимум 15 минут между проверками, поэтому для
 * уведомлений «как только пришло сообщение» используется foreground-сервис:
 * он раз в N секунд (по умолчанию 60) запрашивает /chat/, сравнивает
 * счётчики и показывает системное уведомление.
 *
 * Сервис показывается тихим постоянным уведомлением «FunPay следит за
 * сообщениями» — это требование Android для долгой фоновой работы.
 */
class ChatWatchService : Service() {

    companion object {
        private const val TAG = "ChatWatchService"
        private const val ACTION_START = "com.funpay.app.action.START_WATCH"
        private const val ACTION_STOP = "com.funpay.app.action.STOP_WATCH"
        private const val NO_NETWORK_RETRY_MS = 15_000L

        /** Запускает сервис, если уведомления и «мгновенный» режим включены. */
        fun start(context: Context) {
            if (!Prefs.notificationsEnabled(context)) return
            if (!Prefs.instantWatchEnabled(context)) return

            val intent = Intent(context, ChatWatchService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+: запуск FGS из фона может быть запрещён.
                Log.w(TAG, "Cannot start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ChatWatchService::class.java))
            } catch (_: Exception) {
                // ignore
            }
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var lastUnread = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground() обязан быть вызван сразу после старта сервиса.
        if (!promoteToForeground()) {
            return START_NOT_STICKY
        }

        if (!Prefs.notificationsEnabled(this) || !Prefs.instantWatchEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startPolling()
        return START_STICKY
    }

    private fun promoteToForeground(): Boolean {
        val notification = NotificationHelper.buildServiceNotification(
            this,
            if (lastUnread > 0) lastUnread else 0,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIF_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NotificationHelper.NOTIF_ID_SERVICE, notification)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return false
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return

        pollingJob = scope.launch {
            while (isActive) {
                if (!Prefs.notificationsEnabled(this@ChatWatchService)) {
                    stopSelf()
                    break
                }

                if (!NetworkHelper.isConnected(this@ChatWatchService)) {
                    delay(NO_NETWORK_RETRY_MS)
                    continue
                }

                val result = FunPayChecker.fetch(this@ChatWatchService)
                if (result != null) {
                    val outcome = NotificationCenter.process(this@ChatWatchService, result)
                    lastUnread = outcome.totalUnread
                    NotificationHelper.updateServiceNotification(
                        this@ChatWatchService,
                        outcome.totalUnread,
                    )
                }

                delay(Prefs.pollSeconds(this@ChatWatchService) * 1000L)
            }
        }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

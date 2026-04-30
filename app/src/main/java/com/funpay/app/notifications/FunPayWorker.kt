package com.funpay.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.*
import com.funpay.app.utils.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * FunPayWorker — фоновый Worker для проверки новых уведомлений на FunPay.
 * Использует WorkManager для периодического запуска даже при закрытом приложении.
 */
class FunPayWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FunPayWorker"
        const val WORK_NAME = "funpay_notification_check"
        const val KEY_INTERVAL_MINUTES = "notification_interval_minutes"

        // URL для проверки непрочитанных сообщений/уведомлений
        private const val CHECK_URL = "https://funpay.com/chat/"

        /**
         * Планирует периодический Worker.
         * Если Worker уже запланирован — перезапускает с новыми параметрами.
         */
        fun scheduleWork(context: Context) {
            val prefs = context.getSharedPreferences("funpay_prefs", Context.MODE_PRIVATE)
            val intervalMinutes = prefs.getLong(KEY_INTERVAL_MINUTES, 15L)

            // Ограничения: только при наличии сети
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<FunPayWorker>(
                intervalMinutes, TimeUnit.MINUTES,
                5L, TimeUnit.MINUTES // flex period
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, // Обновляем если уже есть
                request
            )

            Log.d(TAG, "Worker scheduled with interval: ${intervalMinutes}m")
        }

        /**
         * Отменяет запланированный Worker.
         */
        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Worker cancelled")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking FunPay notifications...")

            // Проверяем сетевое соединение
            if (!NetworkHelper.isConnected(context)) {
                Log.d(TAG, "No network, skipping check")
                return@withContext Result.retry()
            }

            // Проверяем наличие новых уведомлений
            val hasNewNotifications = checkForNotifications()

            if (hasNewNotifications) {
                Log.d(TAG, "New notifications found, sending notification")
                NotificationHelper.showNewMessageNotification(context)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker error: ${e.message}", e)
            // Повторяем попытку при ошибке
            Result.retry()
        }
    }

    /**
     * Проверяет наличие новых сообщений/уведомлений на FunPay.
     * Делает HTTP-запрос и анализирует ответ.
     */
    private suspend fun checkForNotifications(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("funpay_prefs", Context.MODE_PRIVATE)
            val cookies = prefs.getString("saved_cookies", null) ?: return@withContext false

            val url = URL(CHECK_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Cookie", cookies)
                setRequestProperty("User-Agent", "FunPayApp/1.0 Android")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext false
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Ищем индикатор новых сообщений в HTML-ответе
            // FunPay показывает счётчик непрочитанных сообщений
            val hasUnread = response.contains("badge-notify") ||
                response.contains("unread-count") ||
                response.contains("notification-badge")

            hasUnread
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check notifications: ${e.message}")
            false
        }
    }
}

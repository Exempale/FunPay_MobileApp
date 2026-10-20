package com.funpay.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.funpay.app.utils.NetworkHelper
import com.funpay.app.utils.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * FunPayWorker — резервная фоновая проверка через WorkManager.
 *
 * Минимальный период WorkManager — 15 минут, поэтому для мгновенных
 * уведомлений используется ChatWatchService. Этот Worker страхует случай,
 * когда сервис был убит системой.
 */
class FunPayWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FunPayWorker"
        const val WORK_NAME = "funpay_notification_check"

        /** Планирует (или обновляет) периодическую проверку. */
        fun scheduleWork(context: Context) {
            if (!Prefs.notificationsEnabled(context)) return

            val intervalMinutes = Prefs.workIntervalMinutes(context)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<FunPayWorker>(
                intervalMinutes,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )

            Log.d(TAG, "Worker scheduled, interval: ${intervalMinutes}m")
        }

        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Worker cancelled")
        }

        /**
         * Немедленная проверка (кнопка «Проверить сейчас»).
         * @return null, если проверить не удалось (нет сети / сервер недоступен)
         */
        suspend fun checkNow(context: Context): NotificationCenter.Outcome? =
            withContext(Dispatchers.IO) {
                if (!NetworkHelper.isConnected(context)) return@withContext null
                val result = FunPayChecker.fetch(context) ?: return@withContext null
                NotificationCenter.process(context, result)
            }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!Prefs.notificationsEnabled(context)) {
                return@withContext Result.success()
            }

            if (!NetworkHelper.isConnected(context)) {
                Log.d(TAG, "No network, skip")
                return@withContext Result.success()
            }

            val result = FunPayChecker.fetch(context) ?: return@withContext Result.retry()
            val outcome = NotificationCenter.process(context, result)

            Log.d(TAG, "Check done: unread=${outcome.totalUnread}, new=${outcome.newMessages.size}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker error: ${e.message}", e)
            Result.retry()
        }
    }
}

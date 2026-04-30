package com.funpay.app

import android.app.Application
import androidx.work.Configuration
import com.funpay.app.notifications.NotificationHelper

/**
 * FunPayApplication — кастомный Application класс.
 * Инициализирует компоненты при старте приложения:
 * - Каналы уведомлений
 * - WorkManager
 */
class FunPayApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

        // Создаём каналы уведомлений (обязательно для Android 8.0+)
        NotificationHelper.createNotificationChannels(this)
    }

    /**
     * Кастомная конфигурация WorkManager.
     * Позволяет контролировать логирование и пул потоков.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

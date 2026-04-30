package com.funpay.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.funpay.app.R
import com.funpay.app.ui.main.MainActivity

/**
 * NotificationHelper — вспомогательный класс для отображения уведомлений.
 * Создаёт каналы уведомлений и показывает различные типы уведомлений.
 */
object NotificationHelper {

    // ID каналов уведомлений
    const val CHANNEL_MESSAGES = "funpay_messages"
    const val CHANNEL_ORDERS = "funpay_orders"
    const val CHANNEL_GENERAL = "funpay_general"

    // ID уведомлений
    private const val NOTIF_ID_MESSAGE = 1001
    private const val NOTIF_ID_ORDER = 1002

    /**
     * Инициализирует каналы уведомлений.
     * Вызывать при старте приложения (Android 8.0+).
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Канал для новых сообщений
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_messages_desc)
                enableVibration(true)
                enableLights(true)
            }

            // Канал для новых заказов
            val ordersChannel = NotificationChannel(
                CHANNEL_ORDERS,
                context.getString(R.string.channel_orders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_orders_desc)
                enableVibration(true)
                enableLights(true)
            }

            // Общий канал
            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                context.getString(R.string.channel_general_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_general_desc)
            }

            notificationManager.createNotificationChannels(
                listOf(messagesChannel, ordersChannel, generalChannel)
            )
        }
    }

    /**
     * Показывает уведомление о новом сообщении.
     */
    fun showNewMessageNotification(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "https://funpay.com/chat/")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID_MESSAGE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_message_title))
            .setContentText(context.getString(R.string.notif_message_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true) // Удалять при нажатии
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_MESSAGE, notification)
        } catch (e: SecurityException) {
            // Разрешение не выдано — игнорируем
        }
    }

    /**
     * Показывает уведомление о новом заказе.
     */
    fun showNewOrderNotification(context: Context, orderId: String? = null) {
        val targetUrl = if (orderId != null) {
            "https://funpay.com/orders/$orderId/"
        } else {
            "https://funpay.com/orders/"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", targetUrl)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID_ORDER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ORDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_order_title))
            .setContentText(context.getString(R.string.notif_order_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ORDER, notification)
        } catch (e: SecurityException) {
            // Разрешение не выдано
        }
    }

    /**
     * Отменяет все уведомления приложения.
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

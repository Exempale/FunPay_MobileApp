package com.funpay.app.notifications

import android.app.Notification
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
 * NotificationHelper — каналы и готовые уведомления.
 */
object NotificationHelper {

    // ID каналов уведомлений
    const val CHANNEL_MESSAGES = "funpay_messages"
    const val CHANNEL_ORDERS = "funpay_orders"
    const val CHANNEL_GENERAL = "funpay_general"
    const val CHANNEL_SERVICE = "funpay_service"

    // ID уведомлений
    const val NOTIF_ID_SUMMARY = 1001
    private const val NOTIF_ID_ORDER = 1002
    private const val NOTIF_ID_LOGIN = 1003
    const val NOTIF_ID_SERVICE = 1004

    private const val NOTIF_ID_CHAT_BASE = 2000
    private const val GROUP_MESSAGES = "funpay_messages_group"

    private const val CHAT_URL = "https://funpay.com/chat/?node="

    /**
     * Инициализирует каналы уведомлений.
     * Вызывать при старте приложения (Android 8.0+).
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_messages_desc)
            enableVibration(true)
            enableLights(true)
        }

        val ordersChannel = NotificationChannel(
            CHANNEL_ORDERS,
            context.getString(R.string.channel_orders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_orders_desc)
            enableVibration(true)
            enableLights(true)
        }

        val generalChannel = NotificationChannel(
            CHANNEL_GENERAL,
            context.getString(R.string.channel_general_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_general_desc)
        }

        // Постоянное уведомление фонового сервиса — тихое, без звука и вибрации
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(
            listOf(messagesChannel, ordersChannel, generalChannel, serviceChannel),
        )
    }

    // ─── Сообщения ───────────────────────────────────────────────────────────

    /**
     * Уведомление о новом сообщении в конкретном чате.
     * Тап открывает приложение сразу на этом диалоге.
     */
    fun showNewMessageNotification(
        context: Context,
        chatId: Long,
        chatName: String,
        preview: String,
    ) {
        if (!canNotify(context)) return

        val text = preview.ifBlank { context.getString(R.string.notif_message_text) }
        val title = chatName.ifBlank { context.getString(R.string.notif_message_title) }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup(GROUP_MESSAGES)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(chatPendingIntent(context, chatId))
            .build()

        safeNotify(context, notificationIdForChat(chatId), notification)
    }

    /** Убирает уведомление конкретного чата (например, когда он прочитан). */
    fun cancelChatNotification(context: Context, chatId: Long) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationIdForChat(chatId))
        } catch (_: Exception) {
            // ignore
        }
    }

    /** Убирает сводное уведомление (когда все сообщения прочитаны). */
    fun cancelSummary(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID_SUMMARY)
        } catch (_: Exception) {
            // ignore
        }
    }

    /** Сводное уведомление группы чатов. */
    fun showMessagesSummary(context: Context, totalUnread: Int) {
        if (!canNotify(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_messages_summary, totalUnread))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(appPendingIntent(context, MainActivity.URL_CHAT))
            .build()

        safeNotify(context, NOTIF_ID_SUMMARY, notification)
    }

    /** Уведомление о новом заказе. */
    fun showNewOrderNotification(context: Context, orderId: String? = null) {
        if (!canNotify(context)) return

        val targetUrl = if (!orderId.isNullOrBlank()) {
            "https://funpay.com/orders/$orderId/"
        } else {
            "https://funpay.com/orders/"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ORDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_order_title))
            .setContentText(context.getString(R.string.notif_order_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(appPendingIntent(context, targetUrl))
            .build()

        safeNotify(context, NOTIF_ID_ORDER, notification)
    }

    /** Протухла сессия — просим войти в аккаунт. */
    fun showLoginRequiredNotification(context: Context) {
        if (!canNotify(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_login_title))
            .setContentText(context.getString(R.string.notif_login_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_login_text)),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(appPendingIntent(context, MainActivity.URL_HOME))
            .build()

        safeNotify(context, NOTIF_ID_LOGIN, notification)
    }

    /**
     * Тестовое уведомление — помогает пользователю убедиться,
     * что разрешения и канал настроены.
     */
    fun showTestNotification(context: Context) {
        if (!canNotify(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_test_title))
            .setContentText(context.getString(R.string.notif_test_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(appPendingIntent(context, MainActivity.URL_CHAT))
            .build()

        safeNotify(context, 1999, notification)
    }

    // ─── Фоновый сервис ──────────────────────────────────────────────────────

    /** Постоянное (ongoing) уведомление foreground-сервиса. */
    fun buildServiceNotification(context: Context, unread: Int): Notification {
        val text = if (unread > 0) {
            context.getString(R.string.notif_service_text_unread, unread)
        } else {
            context.getString(R.string.notif_service_text)
        }

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(appPendingIntent(context, MainActivity.URL_CHAT))
            .build()
    }

    fun updateServiceNotification(context: Context, unread: Int) {
        val notification = buildServiceNotification(context, unread)
        safeNotify(context, NOTIF_ID_SERVICE, notification)
    }

    /** Отменяет все уведомления приложения (кроме ongoing-уведомления сервиса). */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    // ─── Внутреннее ──────────────────────────────────────────────────────────

    private fun notificationIdForChat(chatId: Long): Int =
        NOTIF_ID_CHAT_BASE + (chatId % 1000).toInt()

    private fun chatPendingIntent(context: Context, chatId: Long): PendingIntent {
        val url = if (chatId > 0) CHAT_URL + chatId else MainActivity.URL_CHAT
        return appPendingIntent(context, url, chatId.toInt())
    }

    private fun appPendingIntent(
        context: Context,
        url: String,
        requestCode: Int = url.hashCode(),
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, url)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Есть ли у приложения разрешение показывать уведомления. */
    fun canPostNotifications(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun canNotify(context: Context): Boolean = canPostNotifications(context)

    private fun safeNotify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Разрешение POST_NOTIFICATIONS не выдано
        } catch (e: Exception) {
            // На некоторых прошивках notify может бросить IllegalArgumentException
        }
    }
}

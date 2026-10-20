package com.funpay.app.notifications

import android.content.Context
import android.util.Log
import com.funpay.app.utils.CookieHelper
import com.funpay.app.utils.Prefs
import org.json.JSONObject

/**
 * NotificationCenter — сравнение результатов проверки с предыдущим состоянием
 * и показ уведомлений.
 *
 * Именно здесь решается, что считать «новым сообщением»:
 * - у чата увеличился `data-node-msg` (id последнего сообщения),
 * - чат помечен как `unread`,
 * - последнее сообщение новее последнего прочитанного (`data-user-msg`).
 *
 * Состояние хранится в SharedPreferences, поэтому сервис, Worker и JS-мост
 * не показывают одно и то же уведомление дважды.
 */
object NotificationCenter {

    private const val TAG = "NotificationCenter"

    /** Сколько времени не надоедать предупреждением «войдите в аккаунт» */
    private const val LOGIN_WARN_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class NewMessage(
        val chatId: Long,
        val chatName: String,
        val preview: String,
    )

    data class Outcome(
        val newMessages: List<NewMessage>,
        val totalUnread: Int,
        val authorized: Boolean,
        /** Первый запуск: сохранён только baseline, уведомления не показывались */
        val firstRun: Boolean,
    )

    /**
     * @param suppressChatId чат, открытый на экране прямо сейчас — по нему
     *        уведомление не показываем, чтобы не мешать пользователю.
     */
    @Synchronized
    fun process(
        context: Context,
        result: ChatCheckResult,
        suppressChatId: Long = 0L,
    ): Outcome {
        val prefs = Prefs.get(context)
        val firstRun = !prefs.getBoolean(Prefs.KEY_BASELINE_DONE, false)

        // Страница авторизации / не распозналась разметка — состояние не трогаем.
        if (!result.authorized) {
            maybeWarnAboutLogin(context)
            prefs.edit()
                .putLong(Prefs.KEY_LAST_CHECK_AT, System.currentTimeMillis())
                .apply()
            return Outcome(emptyList(), 0, false, firstRun)
        }

        if (!result.parsedChatList && result.chats.isEmpty()) {
            Log.w(TAG, "Chat list not parsed, keeping previous state")
            return Outcome(emptyList(), result.totalUnread, true, firstRun)
        }

        val previous = loadState(prefs)
        val previousTotal = prefs.getInt(Prefs.KEY_LAST_TOTAL_UNREAD, 0)
        val fresh = ArrayList<NewMessage>()

        if (!firstRun) {
            for (chat in result.chats) {
                if (chat.id <= 0L) continue
                val prev = previous.optJSONObject(chat.id.toString())
                val prevLastMessageId = prev?.optLong("last", -1L) ?: -1L
                val prevUnread = prev?.optBoolean("unread", false) ?: false

                // Новое сообщение: id последнего сообщения вырос И чат не прочитан.
                val arrivedNew = chat.lastMessageId > prevLastMessageId &&
                    chat.unread &&
                    chat.lastMessageId > chat.lastReadMessageId

                // Чат стал непрочитанным (например, первое сообщение в новом диалоге).
                val becameUnread = chat.unread && !prevUnread

                if (arrivedNew || becameUnread) {
                    if (suppressChatId > 0L && chat.id == suppressChatId) continue
                    fresh += NewMessage(
                        chatId = chat.id,
                        chatName = chat.name,
                        preview = chat.preview,
                    )
                }
            }
        }

        saveState(prefs, result)
        prefs.edit()
            .putBoolean(Prefs.KEY_BASELINE_DONE, true)
            .putLong(Prefs.KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .putInt(Prefs.KEY_LAST_TOTAL_UNREAD, result.totalUnread)
            .apply()

        if (fresh.isNotEmpty()) {
            Log.d(TAG, "New messages: ${fresh.map { it.chatName }}")
            fresh.forEach { message ->
                NotificationHelper.showNewMessageNotification(
                    context = context,
                    chatId = message.chatId,
                    chatName = message.chatName,
                    preview = message.preview,
                )
            }
            NotificationHelper.showMessagesSummary(context, result.totalUnread.coerceAtLeast(fresh.size))
        } else if (!firstRun && result.totalUnread > previousTotal && suppressChatId <= 0L) {
            // Счётчик в шапке вырос, но по списку диалогов новых сообщений
            // не нашлось (например, изменилась разметка). Уведомление всё
            // равно показываем — лучше сводное, чем никакого.
            Log.d(TAG, "Unread grew to ${result.totalUnread} without per-chat diff")
            NotificationHelper.showMessagesSummary(context, result.totalUnread)
        }

        // Чат прочитан — убираем его уведомление из шторки.
        if (result.totalUnread == 0) {
            NotificationHelper.cancelSummary(context)
            result.chats.forEach { chat -> NotificationHelper.cancelChatNotification(context, chat.id) }
        } else {
            result.chats.filter { !it.unread }.forEach { chat ->
                NotificationHelper.cancelChatNotification(context, chat.id)
            }
        }

        return Outcome(fresh, result.totalUnread, true, firstRun)
    }

    /** Предупреждение о протухшей сессии (не чаще раза в сутки). */
    private fun maybeWarnAboutLogin(context: Context) {
        val prefs = Prefs.get(context)

        // Если пользователь ещё ни разу не входил в аккаунт — не докучаем.
        val wasLoggedIn = CookieHelper.hasSavedSession(context) ||
            prefs.getBoolean(Prefs.KEY_BASELINE_DONE, false)
        if (!wasLoggedIn) return

        val now = System.currentTimeMillis()
        val lastWarn = prefs.getLong(Prefs.KEY_LOGIN_WARN_AT, 0L)
        if (now - lastWarn < LOGIN_WARN_INTERVAL_MS) return

        NotificationHelper.showLoginRequiredNotification(context)
        prefs.edit().putLong(Prefs.KEY_LOGIN_WARN_AT, now).apply()
    }

    private fun loadState(prefs: android.content.SharedPreferences): JSONObject {
        val raw = prefs.getString(Prefs.KEY_STATE, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveState(prefs: android.content.SharedPreferences, result: ChatCheckResult) {
        val state = JSONObject()
        for (chat in result.chats) {
            if (chat.id <= 0L) continue
            state.put(
                chat.id.toString(),
                JSONObject()
                    .put("last", chat.lastMessageId)
                    .put("read", chat.lastReadMessageId)
                    .put("unread", chat.unread)
                    .put("name", chat.name),
            )
        }
        prefs.edit().putString(Prefs.KEY_STATE, state.toString()).apply()
    }

    /** Сбрасывает baseline (например, после очистки cookies / смены аккаунта). */
    fun resetState(context: Context) {
        Prefs.get(context).edit()
            .remove(Prefs.KEY_STATE)
            .remove(Prefs.KEY_BASELINE_DONE)
            .remove(Prefs.KEY_LAST_CHECK_AT)
            .remove(Prefs.KEY_LAST_TOTAL_UNREAD)
            .apply()
    }
}

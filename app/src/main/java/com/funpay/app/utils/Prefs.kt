package com.funpay.app.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Prefs — единая точка доступа к настройкам приложения.
 *
 * Все ключи собраны здесь, чтобы не было расхождений между
 * MainActivity / SettingsActivity / Worker / Service.
 */
object Prefs {

    const val NAME = "funpay_prefs"

    /** Мастер-переключатель уведомлений */
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

    /** Мгновенные уведомления через foreground-сервис */
    const val KEY_INSTANT_WATCH = "instant_watch_enabled"

    /** Интервал резервной проверки WorkManager (мин, минимум 15) */
    const val KEY_INTERVAL_MINUTES = "notification_interval_minutes"

    /** Период опроса сервиса (сек) */
    const val KEY_POLL_SECONDS = "watch_poll_seconds"

    /** Cookie-строка для фоновых запросов */
    const val KEY_SAVED_COOKIES = "saved_cookies"
    const val KEY_COOKIES_SAVED_AT = "saved_cookies_at"

    /** Сериализованное состояние чатов (для дедупликации уведомлений) */
    const val KEY_STATE = "notification_state_v1"

    /** Первый запуск проверки — не показываем уведомления о старых сообщениях */
    const val KEY_BASELINE_DONE = "notification_baseline_done"

    const val KEY_LAST_CHECK_AT = "notification_last_check_at"
    const val KEY_LAST_TOTAL_UNREAD = "notification_last_total_unread"
    const val KEY_LOGIN_WARN_AT = "notification_login_warn_at"

    /** Разумные границы, чтобы не сломать WorkManager/батарею */
    const val MIN_WORK_INTERVAL_MINUTES = 15L
    const val MAX_WORK_INTERVAL_MINUTES = 60L
    const val DEFAULT_WORK_INTERVAL_MINUTES = 15L

    const val MIN_POLL_SECONDS = 30L
    const val MAX_POLL_SECONDS = 300L
    const val DEFAULT_POLL_SECONDS = 60L

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ─── Уведомления ─────────────────────────────────────────────────────────

    fun notificationsEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) =
        get(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()

    fun instantWatchEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_INSTANT_WATCH, true)

    fun setInstantWatchEnabled(context: Context, enabled: Boolean) =
        get(context).edit().putBoolean(KEY_INSTANT_WATCH, enabled).apply()

    // ─── Интервалы ───────────────────────────────────────────────────────────

    fun workIntervalMinutes(context: Context): Long =
        get(context).getLong(KEY_INTERVAL_MINUTES, DEFAULT_WORK_INTERVAL_MINUTES)
            .coerceIn(MIN_WORK_INTERVAL_MINUTES, MAX_WORK_INTERVAL_MINUTES)

    fun setWorkIntervalMinutes(context: Context, minutes: Long) =
        get(context).edit()
            .putLong(KEY_INTERVAL_MINUTES, minutes.coerceIn(MIN_WORK_INTERVAL_MINUTES, MAX_WORK_INTERVAL_MINUTES))
            .apply()

    fun pollSeconds(context: Context): Long =
        get(context).getLong(KEY_POLL_SECONDS, DEFAULT_POLL_SECONDS)
            .coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)

    fun setPollSeconds(context: Context, seconds: Long) =
        get(context).edit()
            .putLong(KEY_POLL_SECONDS, seconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS))
            .apply()
}

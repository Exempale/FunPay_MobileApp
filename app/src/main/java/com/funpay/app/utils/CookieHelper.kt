package com.funpay.app.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * CookieHelper — управление cookie для WebView и фоновых проверок.
 *
 * ВАЖНО: именно здесь раньше была причина «неработающих» уведомлений —
 * cookies никогда не сохранялись в SharedPreferences, поэтому Worker
 * ходил на funpay.com без сессии и всегда видел анонимную страницу.
 */
object CookieHelper {

    private const val FUNPAY_URL = "https://funpay.com"

    /**
     * Синхронизирует сохранённые cookies с WebView.
     * Вызывать перед первой загрузкой страницы.
     */
    fun syncCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val savedCookies = Prefs.get(webView.context).getString(Prefs.KEY_SAVED_COOKIES, null)
        if (!savedCookies.isNullOrEmpty()) {
            savedCookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                if (trimmed.isNotEmpty() && trimmed.contains("=")) {
                    cookieManager.setCookie(FUNPAY_URL, trimmed)
                }
            }
            cookieManager.flush()
        }
    }

    /**
     * Забирает актуальные cookies из WebView и сохраняет их для фоновых задач.
     * Вызывать после каждой успешной загрузки страницы и в onPause().
     */
    fun saveCookies(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        if (!url.startsWith("http")) return

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)
        cookieManager.flush()

        if (cookies.isNullOrEmpty()) return

        Prefs.get(context).edit()
            .putString(Prefs.KEY_SAVED_COOKIES, cookies)
            .putLong(Prefs.KEY_COOKIES_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Совместимый алиас, используется из JS-моста. */
    fun persistCookies(context: Context, url: String) = saveCookies(context, url)

    /** Возвращает сохранённую cookie-строку для фоновых запросов. */
    fun savedCookies(context: Context): String? =
        Prefs.get(context).getString(Prefs.KEY_SAVED_COOKIES, null)

    fun hasSavedSession(context: Context): Boolean =
        !savedCookies(context).isNullOrEmpty()

    /**
     * Принудительно сохраняет все cookies на диск.
     * Вызывать в onPause().
     */
    fun flush() {
        CookieManager.getInstance().flush()
    }

    /**
     * Очищает все cookies и сохранённые данные.
     */
    fun clearAll(context: Context) {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        Prefs.get(context).edit()
            .remove(Prefs.KEY_SAVED_COOKIES)
            .remove(Prefs.KEY_COOKIES_SAVED_AT)
            .remove(Prefs.KEY_STATE)
            .remove(Prefs.KEY_BASELINE_DONE)
            .apply()
    }

    /**
     * Очищает историю навигации (отдельно от cookies).
     */
    fun clearHistory(context: Context) {
        Prefs.get(context).edit()
            .remove("nav_history")
            .apply()
    }
}

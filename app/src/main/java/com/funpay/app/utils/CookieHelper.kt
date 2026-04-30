package com.funpay.app.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * CookieHelper — управление cookie для WebView.
 * Синхронизирует куки между сессиями, чтобы пользователь оставался авторизованным.
 */
object CookieHelper {

    private const val PREFS_NAME = "funpay_prefs"
    private const val KEY_COOKIES = "saved_cookies"
    private const val FUNPAY_URL = "https://funpay.com"

    /**
     * Синхронизирует сохранённые куки с WebView.
     * Вызывать перед первой загрузкой страницы.
     */
    fun syncCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Загружаем сохранённые куки
        val prefs = webView.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCookies = prefs.getString(KEY_COOKIES, null)

        if (!savedCookies.isNullOrEmpty()) {
            // Восстанавливаем каждый кuki
            savedCookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                if (trimmed.isNotEmpty()) {
                    cookieManager.setCookie(FUNPAY_URL, trimmed)
                }
            }
            cookieManager.flush()
        }
    }

    /**
     * Сохраняет куки для указанного URL.
     * Вызывать после каждой успешной загрузки страницы.
     */
    fun saveCookies(url: String?) {
        if (url.isNullOrEmpty()) return

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url) ?: return

        // Куки сохраняются через Application Context — используем статический доступ
        // (В реальном проекте лучше передавать Context через DI)
        android.app.Application::class.java // placeholder
        cookieManager.flush()
    }

    /**
     * Сохраняет куки в SharedPreferences (для фоновых Worker'ов).
     */
    fun persistCookies(context: Context, url: String) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url) ?: return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIES, cookies)
            .apply()
    }

    /**
     * Принудительно сохраняет все куки на диск.
     * Вызывать в onPause().
     */
    fun flush() {
        CookieManager.getInstance().flush()
    }

    /**
     * Очищает все куки и сохранённые данные.
     */
    fun clearAll(context: Context) {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COOKIES)
            .apply()
    }

    /**
     * Очищает историю навигации (отдельно от кuki).
     */
    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("nav_history")
            .apply()
    }
}

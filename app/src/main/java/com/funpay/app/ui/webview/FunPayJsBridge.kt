package com.funpay.app.ui.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.webkit.JavascriptInterface
import com.funpay.app.BuildConfig
import com.funpay.app.notifications.NotificationHelper
import com.funpay.app.utils.CookieHelper
import org.json.JSONObject

/**
 * FunPayJsBridge — мост JavaScript ↔ нативный Android.
 *
 * Доступен в WebView как `window.FunPayBridge`.
 *
 * ВАЖНО: методы с @JavascriptInterface вызываются из JS-потока
 * (не UI-поток), поэтому колбэки должны сами решать, нужен ли им UI-поток.
 */
class FunPayJsBridge(
    private val context: Context,
    /** Навигация к URL внутри WebView (вызывается из фонового потока) */
    private val onNavigate: (url: String) -> Unit,
    /** Разрешён ли сейчас pull-to-refresh (вызывается из фонового потока) */
    private val onPullToRefreshChanged: (allowed: Boolean) -> Unit,
    /** Отчёт о непрочитанных сообщениях со страницы */
    private val onUnreadReport: (total: Int, activeChatId: Long, chatsJson: String) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Короткий Toast (совместимость с API 1.0.0) */
    @JavascriptInterface
    fun showToast(message: String) {
        try {
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "showToast failed: ${e.message}")
        }
    }

    /** Версия приложения */
    @JavascriptInterface
    fun getAppVersion(): String = APP_VERSION

    /** Всегда возвращает "android" */
    @JavascriptInterface
    fun getPlatform(): String = "android"

    /** Сохраняет cookies текущей страницы для фоновых проверок */
    @JavascriptInterface
    fun saveCookiesForBackground(url: String) {
        try {
            CookieHelper.persistCookies(context, url)
        } catch (e: Exception) {
            Log.w(TAG, "saveCookiesForBackground failed: ${e.message}")
        }
    }

    /** Навигация к URL внутри WebView */
    @JavascriptInterface
    fun navigateTo(url: String) {
        try {
            onNavigate(url)
        } catch (e: Exception) {
            Log.w(TAG, "navigateTo failed: ${e.message}")
        }
    }

    /** Включает/выключает нативный pull-to-refresh для текущей страницы */
    @JavascriptInterface
    fun setPullToRefreshEnabled(allowed: Boolean) {
        try {
            onPullToRefreshChanged(allowed)
        } catch (e: Exception) {
            Log.w(TAG, "setPullToRefreshEnabled failed: ${e.message}")
        }
    }

    /**
     * Отчёт со страницы: сколько непрочитанных в шапке и состояние списка чатов.
     * Используется для мгновенных уведомлений, пока приложение открыто.
     */
    @JavascriptInterface
    fun reportUnread(total: Int, activeChatId: Long, chatsJson: String) {
        try {
            onUnreadReport(total, activeChatId, chatsJson)
        } catch (e: Exception) {
            Log.w(TAG, "reportUnread failed: ${e.message}")
        }
    }

    /** Триггер уведомления о новом сообщении из JS */
    @JavascriptInterface
    fun onNewMessage(jsonData: String) {
        try {
            val json = JSONObject(jsonData)
            NotificationHelper.showNewMessageNotification(
                context = context,
                chatId = json.optLong("chat_id", 0L),
                chatName = json.optString("sender", json.optString("sender_name", "")),
                preview = json.optString("text", ""),
            )
        } catch (_: Exception) {
            // Некорректный JSON
        }
    }

    /** Триггер уведомления о новом заказе из JS */
    @JavascriptInterface
    fun onNewOrder(jsonData: String) {
        try {
            val json = JSONObject(jsonData)
            val orderId = json.optString("order_id", "")
            NotificationHelper.showNewOrderNotification(context, orderId)
        } catch (_: Exception) {
            // Некорректный JSON
        }
    }

    /** Тестовое уведомление (используется для отладки интеграции) */
    @JavascriptInterface
    fun showTestNotification() {
        NotificationHelper.showTestNotification(context)
    }

    private companion object {
        const val TAG = "FunPayJsBridge"
        val APP_VERSION: String = BuildConfig.VERSION_NAME
    }
}

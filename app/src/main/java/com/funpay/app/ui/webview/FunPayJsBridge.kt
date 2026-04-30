package com.funpay.app.ui.webview

import android.content.Context
import android.webkit.JavascriptInterface
import com.funpay.app.notifications.NotificationHelper
import com.funpay.app.utils.CookieHelper
import org.json.JSONObject

/**
 * FunPayJsBridge — мост JavaScript ↔ нативный Android.
 *
 * Доступен в WebView как `window.FunPayBridge`.
 *
 * Пример из JS:
 * ```javascript
 * window.FunPayBridge.showToast("Привет");
 * window.FunPayBridge.getAppVersion(); // "1.0.0"
 * ```
 *
 * ВАЖНО: все методы с аннотацией @JavascriptInterface вызываются из JS-потока,
 * поэтому UI-операции нужно выполнять через Handler/runOnUiThread.
 */
class FunPayJsBridge(
    private val context: Context,
    /** Callback для навигации к URL внутри WebView */
    private val onNavigate: (url: String) -> Unit,
) {

    /** Возвращает версию приложения */
    @JavascriptInterface
    fun getAppVersion(): String = "1.0.0"

    /** Всегда возвращает "android" */
    @JavascriptInterface
    fun getPlatform(): String = "android"

    /** Сохраняет куки для использования в фоновых задачах */
    @JavascriptInterface
    fun saveCookiesForBackground(url: String) {
        CookieHelper.persistCookies(context, url)
    }

    /** Навигация к URL внутри WebView */
    @JavascriptInterface
    fun navigateTo(url: String) {
        onNavigate(url)
    }

    /** Триггер уведомления о новом сообщении из JS */
    @JavascriptInterface
    fun onNewMessage(jsonData: String) {
        try {
            JSONObject(jsonData) // Проверяем валидность JSON
            NotificationHelper.showNewMessageNotification(context)
        } catch (_: Exception) { /* Некорректный JSON */ }
    }

    /** Триггер уведомления о новом заказе из JS */
    @JavascriptInterface
    fun onNewOrder(jsonData: String) {
        try {
            val json    = JSONObject(jsonData)
            val orderId = json.optString("order_id", null)
            NotificationHelper.showNewOrderNotification(context, orderId)
        } catch (_: Exception) { /* Некорректный JSON */ }
    }
}

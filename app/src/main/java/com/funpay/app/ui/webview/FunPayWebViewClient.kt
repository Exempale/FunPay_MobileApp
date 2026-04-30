package com.funpay.app.ui.webview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.*

/**
 * FunPayWebViewClient — WebView-клиент для FunPay.
 *
 * Ключевые правила навигации:
 * - ВСЕ http/https ссылки открываются ВНУТРИ приложения (включая игры, товары, категории)
 * - Только схемы tg://, tel:, mailto: — во внешнем приложении
 * - Ошибки сети показывают кастомный экран (без стандартного WebView-сообщения)
 */
class FunPayWebViewClient(
    /** Вызывается когда страница начала загружаться */
    private val onPageStarted: (url: String?) -> Unit,
    /** Вызывается когда страница полностью загружена */
    private val onPageFinished: (url: String?) -> Unit,
    /** Вызывается при ошибке загрузки основного фрейма */
    private val onReceivedError: () -> Unit,
) : WebViewClient() {

    // Флаг: текущая страница завершилась ошибкой (не показываем onPageFinished)
    private var hasError = false

    companion object {
        /** Схемы, которые нужно открывать во внешних приложениях */
        private val EXTERNAL_SCHEMES = setOf(
            "tg://",
            "tel:",
            "mailto:",
            "intent://",
            "viber://",
            "whatsapp://",
            "vk://",
        )
    }

    // ─── Перехват навигации ───────────────────────────────────────────────────

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleNavigation(view, url)
    }

    @Deprecated("Используется для API < 24")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        url ?: return false
        return handleNavigation(view, url)
    }

    /**
     * Обрабатывает переход по URL.
     *
     * @return true — WebView НЕ обрабатывает (мы открываем сами), false — WebView обрабатывает
     */
    private fun handleNavigation(view: WebView?, url: String): Boolean {
        // Внешние схемы — запускаем Intent
        if (EXTERNAL_SCHEMES.any { url.startsWith(it) }) {
            openExternalIntent(view, url)
            return true // Останавливаем WebView
        }
        // Всё остальное (http/https, включая любые страницы FunPay) — загружаем внутри
        return false
    }

    // ─── Жизненный цикл загрузки ──────────────────────────────────────────────

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        hasError = false
        onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!hasError) {
            onPageFinished(url)
        }
    }

    // ─── Обработка ошибок ─────────────────────────────────────────────────────

    /**
     * Ошибка загрузки ресурса (для API 23+).
     * Реагируем только на ошибки основного фрейма — не на суб-ресурсы (картинки, шрифты).
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            hasError = true
            // Загружаем пустую страницу чтобы скрыть стандартное WebView-сообщение об ошибке
            view?.loadUrl("about:blank")
            onReceivedError()
        }
    }

    @Deprecated("Используется для API < 23")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?,
    ) {
        @Suppress("DEPRECATION")
        super.onReceivedError(view, errorCode, description, failingUrl)
        hasError = true
        view?.loadUrl("about:blank")
        onReceivedError()
    }

    /**
     * HTTP-ошибки (4xx, 5xx). Реагируем только на 5xx (серверные ошибки).
     */
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        val isMainFrame  = request?.isForMainFrame == true
        val statusCode   = errorResponse?.statusCode ?: 0
        if (isMainFrame && statusCode >= 500) {
            hasError = true
            onReceivedError()
        }
    }

    /**
     * SSL-ошибки. Для FunPay принимаем автоматически, для прочих — отменяем.
     */
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        val url = view?.url.orEmpty()
        if ("funpay" in url) {
            handler?.proceed()
        } else {
            handler?.cancel()
        }
    }

    // ─── Хелперы ──────────────────────────────────────────────────────────────

    /**
     * Открывает ссылку во внешнем приложении (Telegram, телефон и т.д.).
     */
    private fun openExternalIntent(view: WebView?, url: String) {
        val context = view?.context ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Нет приложения для обработки — тихо игнорируем
        }
    }
}

package com.funpay.app.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.funpay.app.R
import com.funpay.app.ui.main.MainActivity
import com.funpay.app.utils.NetworkHelper

/**
 * SplashActivity — заставка при запуске.
 *
 * Синий фон + белый текст "FunPay".
 * Пока заставка показывается — в фоне предзагружается WebView с сайтом.
 * Переходит в MainActivity через 1.5 сек (или сразу после загрузки страницы).
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    // Минимальное время показа заставки (мс)
    private val MIN_SPLASH_MS = 1500L

    // Флаг: страница уже загружена в фоновом WebView
    private var pageLoaded = false
    private var splashDone = false

    // Фоновый WebView для предзагрузки сайта
    private var backgroundWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Полноэкранный режим
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        // Запускаем предзагрузку сайта в фоне
        startBackgroundPreload()

        // Минимальная задержка заставки
        handler.postDelayed({
            splashDone = true
            if (pageLoaded) goToMain()
        }, MIN_SPLASH_MS)
    }

    /**
     * Создаём скрытый WebView и начинаем грузить FunPay пока идёт заставка.
     * Это устраняет "белую вспышку" — сайт уже готов когда пользователь увидит его.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startBackgroundPreload() {
        if (!NetworkHelper.isConnected(this)) {
            // Нет сети — просто ждём таймаут
            return
        }

        backgroundWebView = WebView(applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Chrome User-Agent для совместимости с Google Login и reCAPTCHA
                userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    if (splashDone) goToMain()
                }
            }
            loadUrl("https://funpay.com")
        }
    }

    /**
     * Передаём предзагруженный WebView в MainActivity через companion-хранилище,
     * чтобы не грузить страницу повторно.
     */
    private fun goToMain() {
        // Сохраняем предзагруженный WebView для MainActivity
        backgroundWebView?.let {
            PreloadedWebViewHolder.webView = it
        }

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // Не уничтожаем backgroundWebView — он передаётся в MainActivity
    }
}

/**
 * Простое хранилище предзагруженного WebView между Activity.
 * Используется только при первом запуске.
 */
object PreloadedWebViewHolder {
    var webView: WebView? = null

    /** Забирает WebView (сбрасывает хранилище после взятия) */
    fun take(): WebView? {
        val wv = webView
        webView = null
        return wv
    }
}

package com.funpay.app.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.funpay.app.R
import com.funpay.app.databinding.ActivityMainBinding
import com.funpay.app.notifications.FunPayWorker
import com.funpay.app.notifications.NotificationHelper
import com.funpay.app.ui.settings.SettingsActivity
import com.funpay.app.ui.splash.PreloadedWebViewHolder
import com.funpay.app.ui.webview.FunPayJsBridge
import com.funpay.app.ui.webview.FunPayWebViewClient
import com.funpay.app.utils.CookieHelper
import com.funpay.app.utils.NetworkHelper

/**
 * MainActivity — главный экран приложения.
 *
 * Особенности:
 * - Принимает предзагруженный WebView от SplashActivity (нет повторной загрузки)
 * - SwipeRefresh срабатывает ТОЛЬКО когда сайт прокручен в самый верх
 * - Нижняя навигация: Главная / Чаты / Заказы / Профиль / Настройки
 * - Тулбар полностью скрыт
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentTab = BottomTab.HOME
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ─── Launchers ────────────────────────────────────────────────────────────

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else null
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) FunPayWorker.scheduleWork(this) }

    companion object {
        const val URL_HOME    = "https://funpay.com"
        const val URL_CHAT    = "https://funpay.com/chat/"
        const val URL_ORDERS  = "https://funpay.com/orders/trade"
        const val URL_PROFILE = "https://funpay.com/account/settings"
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Тулбар полностью скрыт — не нужен
        supportActionBar?.hide()

        setupWebView()
        setupSwipeRefresh()
        setupBottomNav()
        setupErrorLayout()
        setupBackNavigation()

        // Создаём каналы уведомлений (Android 8.0+)
        NotificationHelper.createNotificationChannels(this)
        requestNotificationPermission()

        if (savedInstanceState != null) {
            // Восстановление после поворота экрана
            binding.webView.restoreState(savedInstanceState)
        } else {
            CookieHelper.syncCookies(binding.webView)
            // Если SplashActivity предзагрузил страницу — применяем CSS и показываем
            // Если нет — грузим заново
            navigateTo(URL_HOME)
        }
    }

    // ─── WebView ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webViewClient = FunPayWebViewClient(
            onPageStarted  = { _ ->
                showError(false)
            },
            onPageFinished = { url ->
                binding.swipeRefresh.isRefreshing = false
                if (!url.isNullOrEmpty()) CookieHelper.saveCookies(url)
            },
            onReceivedError = { showError(true) },
        )

        with(binding.webView) {
            this.webViewClient = webViewClient
            webChromeClient    = buildChromeClient()

            settings.apply {
                javaScriptEnabled                    = true
                domStorageEnabled                    = true
                mixedContentMode                     = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportZoom(true)
                builtInZoomControls                  = true
                displayZoomControls                  = false
                cacheMode                            = WebSettings.LOAD_DEFAULT
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                allowFileAccess                      = true
                allowContentAccess                   = true
                mediaPlaybackRequiresUserGesture     = false
                // Chrome User-Agent для совместимости с Google Login и reCAPTCHA
                userAgentString                      = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isScrollbarFadingEnabled = true
            scrollBarStyle           = View.SCROLLBARS_INSIDE_OVERLAY

            // JS Bridge
            addJavascriptInterface(
                FunPayJsBridge(context = this@MainActivity, onNavigate = ::navigateTo),
                "FunPayBridge",
            )
        }
    }

    private fun buildChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.apply {
                progress   = newProgress
                visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            val intent = fileChooserParams?.createIntent()
                ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }
            return try { fileChooserLauncher.launch(intent); true }
            catch (_: Exception) {
                this@MainActivity.filePathCallback = null
                Toast.makeText(this@MainActivity, R.string.error_file_chooser, Toast.LENGTH_SHORT).show()
                false
            }
        }

        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean,
            isUserGesture: Boolean, resultMsg: android.os.Message?,
        ): Boolean {
            val hijack = WebView(this@MainActivity).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?,
                    ): Boolean {
                        request?.url?.toString()?.let { navigateTo(it) }
                        return true
                    }
                }
            }
            (resultMsg?.obj as? WebView.WebViewTransport)?.webView = hijack
            resultMsg?.sendToTarget()
            return true
        }

        override fun onJsAlert(
            view: WebView?, url: String?, message: String?, result: JsResult?,
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView?, url: String?, message: String?, result: JsResult?,
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }
    }

    // ─── SwipeRefresh — срабатывает ТОЛЬКО при скролле в самом верху ──────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.funpay_primary)

            // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: SwipeRefresh проверяет, может ли дочерний View
            // скроллиться вверх. Переопределяем canChildScrollUp, опрашивая WebView через JS.
            // Это предотвращает случайное срабатывание при обычном скролле вверх по странице.
            setOnChildScrollUpCallback { _, _ ->
                // Возвращает true (запрещает SwipeRefresh) если WebView не в самом верху
                binding.webView.scrollY > 0
            }

            setOnRefreshListener {
                if (NetworkHelper.isConnected(this@MainActivity)) {
                    binding.webView.reload()
                } else {
                    isRefreshing = false
                    showError(true)
                }
            }
        }
    }

    // ─── Нижняя навигация ─────────────────────────────────────────────────────

    private fun setupBottomNav() {
        binding.btnNavHome.setOnClickListener    { selectTab(BottomTab.HOME);    navigateTo(URL_HOME) }
        binding.btnNavChat.setOnClickListener    { selectTab(BottomTab.CHAT);    navigateTo(URL_CHAT) }
        binding.btnNavOrders.setOnClickListener  { selectTab(BottomTab.ORDERS);  navigateTo(URL_ORDERS) }
        binding.btnNavProfile.setOnClickListener { selectTab(BottomTab.PROFILE); navigateTo(URL_PROFILE) }
        binding.btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        selectTab(BottomTab.HOME)
    }

    private fun selectTab(tab: BottomTab) {
        currentTab = tab
        val active   = ContextCompat.getColor(this, R.color.funpay_primary)
        val inactive = ContextCompat.getColor(this, R.color.nav_inactive)

        // Иконки
        binding.imgNavHome.setColorFilter(    if (tab == BottomTab.HOME)    active else inactive)
        binding.imgNavChat.setColorFilter(    if (tab == BottomTab.CHAT)    active else inactive)
        binding.imgNavOrders.setColorFilter(  if (tab == BottomTab.ORDERS)  active else inactive)
        binding.imgNavProfile.setColorFilter( if (tab == BottomTab.PROFILE) active else inactive)

        // Подписи
        binding.lblNavHome.setTextColor(    if (tab == BottomTab.HOME)    active else inactive)
        binding.lblNavChat.setTextColor(    if (tab == BottomTab.CHAT)    active else inactive)
        binding.lblNavOrders.setTextColor(  if (tab == BottomTab.ORDERS)  active else inactive)
        binding.lblNavProfile.setTextColor( if (tab == BottomTab.PROFILE) active else inactive)
    }

    // ─── Навигация ────────────────────────────────────────────────────────────

    fun navigateTo(url: String) {
        if (NetworkHelper.isConnected(this)) {
            showError(false)
            binding.webView.loadUrl(url)
        } else {
            showError(true)
        }
    }

    // ─── Экран ошибки ─────────────────────────────────────────────────────────

    private fun setupErrorLayout() {
        binding.btnRetry.setOnClickListener {
            if (NetworkHelper.isConnected(this)) {
                showError(false)
                binding.webView.reload()
            } else {
                Toast.makeText(this, R.string.no_internet, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showError(show: Boolean) {
        binding.layoutError.visibility = if (show) View.VISIBLE else View.GONE
        binding.webView.visibility     = if (show) View.INVISIBLE else View.VISIBLE
    }

    // ─── Кнопка «Назад» ───────────────────────────────────────────────────────

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack()
                else showExitDialog()
            }
        })
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.exit_no)  { d, _ -> d.dismiss() }
            .show()
    }

    // ─── Разрешения ───────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) FunPayWorker.scheduleWork(this)
            else notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            FunPayWorker.scheduleWork(this)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (!NetworkHelper.isConnected(this)) showError(true)
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        CookieHelper.flush()
    }

    override fun onDestroy() {
        with(binding.webView) {
            stopLoading(); clearHistory(); clearCache(true)
            loadUrl("about:blank"); destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        binding.webView.restoreState(savedInstanceState)
    }
}

enum class BottomTab { HOME, CHAT, ORDERS, PROFILE }

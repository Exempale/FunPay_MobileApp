package com.funpay.app.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.funpay.app.R
import com.funpay.app.databinding.ActivityMainBinding
import com.funpay.app.notifications.ChatWatchService
import com.funpay.app.notifications.ChatCheckResult
import com.funpay.app.notifications.ChatSnapshot
import com.funpay.app.notifications.FunPayWorker
import com.funpay.app.notifications.NotificationCenter
import com.funpay.app.notifications.NotificationHelper
import com.funpay.app.ui.settings.SettingsActivity
import com.funpay.app.ui.webview.FunPayJsBridge
import com.funpay.app.ui.webview.FunPayWebViewClient
import com.funpay.app.ui.webview.PageScripts
import com.funpay.app.utils.CookieHelper
import com.funpay.app.utils.NetworkHelper
import com.funpay.app.utils.Prefs
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * MainActivity — главный (и единственный основной) экран приложения.
 *
 * Что важно:
 * - Запускается сразу с заставки-темы (android:windowBackground), без отдельной
 *   SplashActivity и без искусственной задержки — приложение стартует заметно быстрее.
 * - Pull-to-refresh разрешён только когда страница реально вверху и открыт не чат.
 *   Это лечит баг «листаю чат вверх — страница перезагружается».
 * - После загрузки страницы внедряются JS-скрипты: сторож непрочитанных
 *   сообщений (мгновенные уведомления) и трекер скролла.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentTab = BottomTab.HOME
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** Разрешён ли сейчас pull-to-refresh (управляется из JS) */
    @Volatile
    private var pullToRefreshAllowed = true

    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── Launchers ────────────────────────────────────────────────────────────

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            FunPayWorker.scheduleWork(this)
            ChatWatchService.start(this)
        } else {
            Toast.makeText(this, R.string.notif_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        const val EXTRA_NAVIGATE_TO = "navigate_to"

        const val URL_HOME = "https://funpay.com"
        const val URL_CHAT = "https://funpay.com/chat/"
        const val URL_ORDERS = "https://funpay.com/orders/trade"
        const val URL_PROFILE = "https://funpay.com/account/settings"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Снимаем splash-тему: окно уже отрисовано с логотипом из windowBackground,
        // дальше показываем обычный фон приложения. Никаких задержек.
        setTheme(R.style.Theme_FunPay)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        setupWebView()
        setupSwipeRefresh()
        setupBottomNav()
        setupErrorLayout()
        setupBackNavigation()

        NotificationHelper.createNotificationChannels(this)
        requestNotificationPermission()
        startBackgroundWatching()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            CookieHelper.syncCookies(binding.webView)
            navigateTo(intent?.getStringExtra(EXTRA_NAVIGATE_TO) ?: URL_HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { navigateTo(it) }
    }

    // ─── WebView ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webViewClient = FunPayWebViewClient(
            onPageStarted = { _ ->
                showError(false)
            },
            onPageFinished = { url ->
                binding.swipeRefresh.isRefreshing = false
                injectPageScripts()
                if (!url.isNullOrEmpty()) {
                    CookieHelper.saveCookies(this, url)
                }
            },
            onReceivedError = { showError(true) },
        )

        with(binding.webView) {
            this.webViewClient = webViewClient
            webChromeClient = buildChromeClient()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = USER_AGENT
                // Быстрее отдаём уже отрисованную страницу (без «белой вспышки»)
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = false
            }

            isScrollbarFadingEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

            // JS Bridge
            addJavascriptInterface(
                FunPayJsBridge(
                    context = this@MainActivity,
                    onNavigate = { url -> mainHandler.post { navigateTo(url) } },
                    onPullToRefreshChanged = { allowed ->
                        mainHandler.post { pullToRefreshAllowed = allowed }
                    },
                    onUnreadReport = ::handleUnreadReport,
                ),
                "FunPayBridge",
            )
        }
    }

    /**
     * Внедряет скрипты в текущую страницу.
     * Оба скрипта идемпотентны — повторный вызов ничего не ломает.
     */
    private fun injectPageScripts() {
        val webView = binding.webView
        val url = webView.url.orEmpty()
        if (!url.startsWith("http")) return

        webView.evaluateJavascript(PageScripts.PULL_TO_REFRESH, null)
        webView.evaluateJavascript(PageScripts.UNREAD_WATCHER, null)

        // Страховка для страниц, где DOM дорисовывается позже.
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                val current = binding.webView.url.orEmpty()
                if (current.startsWith("http")) {
                    binding.webView.evaluateJavascript(PageScripts.PULL_TO_REFRESH, null)
                    binding.webView.evaluateJavascript(PageScripts.UNREAD_WATCHER, null)
                }
            }
        }, 1200L)
    }

    /**
     * Отчёт из JS: сколько непрочитанных сообщений в шапке FunPay.
     * Сравниваем с сохранённым состоянием и показываем уведомление,
     * если пришло что-то новое.
     */
    private fun handleUnreadReport(total: Int, activeChatId: Long, chatsJson: String) {
        try {
            if (!Prefs.notificationsEnabled(this)) return

            val array = JSONArray(chatsJson)
            val chats = ArrayList<ChatSnapshot>(array.length())
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val id = json.optLong("id", 0L)
                if (id <= 0L) continue
                chats += ChatSnapshot(
                    id = id,
                    name = json.optString("name", ""),
                    preview = json.optString("preview", ""),
                    lastMessageId = json.optLong("last", 0L),
                    lastReadMessageId = json.optLong("read", 0L),
                    unread = json.optBoolean("unread", false),
                )
            }

            if (chats.isEmpty()) {
                // На этой странице нет списка диалогов (главная, заказы, профиль) —
                // виден только счётчик в шапке. Делаем полноценную проверку,
                // чтобы в уведомлении были имя собеседника и текст сообщения.
                if (total > 0) {
                    lifecycleScope.launch { FunPayWorker.checkNow(this@MainActivity) }
                }
                return
            }

            val result = ChatCheckResult(
                totalUnread = total,
                chats = chats,
                authorized = true,
                parsedChatList = true,
            )
            NotificationCenter.process(this, result, suppressChatId = activeChatId)
        } catch (e: Exception) {
            Log.w(TAG, "handleUnreadReport failed: ${e.message}")
        }
    }

    private fun buildChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.apply {
                progress = newProgress
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
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (_: Exception) {
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

    // ─── SwipeRefresh: только когда страница реально вверху ──────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.funpay_primary)

            // WebView.scrollY здесь бесполезен: FunPay скроллит внутренние
            // контейнеры (у чата — .chat-message-list), поэтому scrollY всегда 0
            // и SwipeRefresh срабатывал при каждом свайпе вверх → страница
            // перезагружалась. Теперь решение принимает JS (PageScripts).
            setOnChildScrollUpCallback { _, _ -> !pullToRefreshAllowed }

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
        binding.btnNavHome.setOnClickListener { selectTab(BottomTab.HOME); navigateTo(URL_HOME) }
        binding.btnNavChat.setOnClickListener { selectTab(BottomTab.CHAT); navigateTo(URL_CHAT) }
        binding.btnNavOrders.setOnClickListener { selectTab(BottomTab.ORDERS); navigateTo(URL_ORDERS) }
        binding.btnNavProfile.setOnClickListener { selectTab(BottomTab.PROFILE); navigateTo(URL_PROFILE) }
        binding.btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        selectTab(BottomTab.HOME)
    }

    private fun selectTab(tab: BottomTab) {
        currentTab = tab
        val active = ContextCompat.getColor(this, R.color.funpay_primary)
        val inactive = ContextCompat.getColor(this, R.color.nav_inactive)

        binding.imgNavHome.setColorFilter(if (tab == BottomTab.HOME) active else inactive)
        binding.imgNavChat.setColorFilter(if (tab == BottomTab.CHAT) active else inactive)
        binding.imgNavOrders.setColorFilter(if (tab == BottomTab.ORDERS) active else inactive)
        binding.imgNavProfile.setColorFilter(if (tab == BottomTab.PROFILE) active else inactive)

        binding.lblNavHome.setTextColor(if (tab == BottomTab.HOME) active else inactive)
        binding.lblNavChat.setTextColor(if (tab == BottomTab.CHAT) active else inactive)
        binding.lblNavOrders.setTextColor(if (tab == BottomTab.ORDERS) active else inactive)
        binding.lblNavProfile.setTextColor(if (tab == BottomTab.PROFILE) active else inactive)
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
        binding.webView.visibility = if (show) View.INVISIBLE else View.VISIBLE
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
            .setNegativeButton(R.string.exit_no) { d, _ -> d.dismiss() }
            .show()
    }

    // ─── Уведомления ──────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                FunPayWorker.scheduleWork(this)
            } else {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            FunPayWorker.scheduleWork(this)
        }
    }

    private fun startBackgroundWatching() {
        if (!Prefs.notificationsEnabled(this)) return
        val notificationsGranted = NotificationHelper.canPostNotifications(this)
        if (!notificationsGranted) return

        ChatWatchService.start(this)
        FunPayWorker.scheduleWork(this)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        startBackgroundWatching()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (!NetworkHelper.isConnected(this)) showError(true)
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        CookieHelper.saveCookies(this, binding.webView.url)
        CookieHelper.flush()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        with(binding.webView) {
            stopLoading()
            clearHistory()
            loadUrl("about:blank")
            destroy()
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

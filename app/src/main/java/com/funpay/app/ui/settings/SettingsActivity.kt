package com.funpay.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.funpay.app.BuildConfig
import com.funpay.app.R
import com.funpay.app.databinding.ActivitySettingsBinding
import com.funpay.app.notifications.ChatWatchService
import com.funpay.app.notifications.FunPayWorker
import com.funpay.app.notifications.NotificationHelper
import com.funpay.app.utils.CookieHelper
import com.funpay.app.utils.NetworkHelper
import com.funpay.app.utils.Prefs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SettingsActivity — настройки приложения.
 *
 * Секции:
 * 1. Уведомления (мастер-переключатель, мгновенный режим, интервал,
 *    ручная проверка, тестовое уведомление)
 * 2. Данные и кэш
 * 3. О приложении
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            NotificationHelper.showTestNotification(this)
            applyNotificationSettings()
        } else {
            toast(R.string.notif_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ─── Toolbar ──────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ─── Загрузка настроек ────────────────────────────────────────────────────

    private fun loadSettings() {
        val notifsOn = Prefs.notificationsEnabled(this)
        binding.switchNotifications.isChecked = notifsOn

        binding.switchInstant.isChecked = Prefs.instantWatchEnabled(this)
        binding.switchInstant.isEnabled = notifsOn

        binding.sliderInterval.isEnabled = notifsOn

        // Slider требует значение, кратное stepSize (5). Старые настройки
        // могли содержать, например, 7 минут — подгоняем, иначе будет краш.
        val minutes = snapToStep(Prefs.workIntervalMinutes(this))
        binding.sliderInterval.value = minutes.toFloat()
        updateIntervalLabel(minutes)

        binding.btnCheckNow.isEnabled = notifsOn
        binding.btnTestNotification.isEnabled = notifsOn

        binding.tvAppVersion.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        updateStatus()
    }

    // ─── Listeners ────────────────────────────────────────────────────────────

    private fun setupListeners() {

        // Мастер-переключатель уведомлений
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationsEnabled(this, isChecked)
            binding.sliderInterval.isEnabled = isChecked
            binding.switchInstant.isEnabled = isChecked
            binding.btnCheckNow.isEnabled = isChecked
            binding.btnTestNotification.isEnabled = isChecked

            if (isChecked) {
                if (ensureNotificationPermission()) {
                    applyNotificationSettings()
                    toast(R.string.notifications_enabled)
                }
            } else {
                ChatWatchService.stop(this)
                FunPayWorker.cancelWork(this)
                NotificationHelper.cancelAll(this)
                toast(R.string.notifications_disabled)
            }
        }

        // Мгновенные уведомления (foreground-сервис)
        binding.switchInstant.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setInstantWatchEnabled(this, isChecked)
            if (isChecked && Prefs.notificationsEnabled(this)) {
                ChatWatchService.start(this)
                toast(R.string.instant_enabled)
            } else if (!isChecked) {
                ChatWatchService.stop(this)
                toast(R.string.instant_disabled)
            }
        }

        // Интервал резервной проверки (минимум WorkManager — 15 минут)
        binding.sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val minutes = value.toLong()
                updateIntervalLabel(minutes)
                Prefs.setWorkIntervalMinutes(this, minutes)
                if (Prefs.notificationsEnabled(this)) FunPayWorker.scheduleWork(this)
            }
        }

        // Проверить сейчас
        binding.btnCheckNow.setOnClickListener { checkNow() }

        // Тестовое уведомление
        binding.btnTestNotification.setOnClickListener {
            if (ensureNotificationPermission()) {
                NotificationHelper.showTestNotification(this)
                toast(R.string.test_notification_sent)
            }
        }

        // Очистить кэш
        binding.btnClearCache.setOnClickListener {
            WebView(this).clearCache(true)
            WebStorage.getInstance().deleteAllData()
            toast(R.string.cache_cleared)
        }

        // Очистить cookies
        binding.btnClearCookies.setOnClickListener {
            CookieHelper.clearAll(this)
            com.funpay.app.notifications.NotificationCenter.resetState(this)
            toast(R.string.cookies_cleared)
        }

        // Проверить соединение
        binding.btnCheckConnection.setOnClickListener {
            val ok = NetworkHelper.isConnected(this)
            val type = NetworkHelper.getConnectionType(this)
            toast(
                if (ok) getString(R.string.connection_ok, type.name)
                else getString(R.string.connection_error),
            )
        }
    }

    // ─── Проверка сейчас ──────────────────────────────────────────────────────

    private fun checkNow() {
        if (!NetworkHelper.isConnected(this)) {
            toast(R.string.connection_error)
            return
        }

        binding.btnCheckNow.isEnabled = false
        binding.btnCheckNow.text = getString(R.string.checking)

        lifecycleScope.launch {
            val outcome = FunPayWorker.checkNow(this@SettingsActivity)

            binding.btnCheckNow.isEnabled = Prefs.notificationsEnabled(this@SettingsActivity)
            binding.btnCheckNow.text = getString(R.string.check_now)
            updateStatus()

            when {
                outcome == null -> toast(R.string.check_result_error)
                !outcome.authorized -> toast(R.string.check_result_login)
                outcome.totalUnread > 0 ->
                    toast(getString(R.string.check_result_unread, outcome.totalUnread))
                else -> toast(R.string.check_result_empty)
            }
        }
    }

    // ─── Уведомления ──────────────────────────────────────────────────────────

    /** true, если разрешение уже есть либо запрос отправлен. */
    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        return if (granted) {
            true
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            false
        }
    }

    private fun applyNotificationSettings() {
        if (!Prefs.notificationsEnabled(this)) return
        FunPayWorker.scheduleWork(this)
        if (Prefs.instantWatchEnabled(this)) ChatWatchService.start(this) else ChatWatchService.stop(this)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun updateStatus() {
        val prefs = Prefs.get(this)
        val lastCheck = prefs.getLong(Prefs.KEY_LAST_CHECK_AT, 0L)
        val unread = prefs.getInt(Prefs.KEY_LAST_TOTAL_UNREAD, 0)

        binding.tvNotifStatus.text = if (lastCheck <= 0L) {
            getString(R.string.notif_status_never)
        } else {
            val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(lastCheck))
            getString(R.string.notif_status, time, unread)
        }
    }

    /** Приводит значение к сетке слайдера: 15, 20, 25 … 60. */
    private fun snapToStep(value: Long): Long {
        val stepped = Math.round(value / 5.0).toLong() * 5L
        return stepped.coerceIn(
            Prefs.MIN_WORK_INTERVAL_MINUTES,
            Prefs.MAX_WORK_INTERVAL_MINUTES,
        )
    }

    private fun updateIntervalLabel(minutes: Long) {
        binding.tvIntervalValue.text = getString(R.string.interval_minutes, minutes)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

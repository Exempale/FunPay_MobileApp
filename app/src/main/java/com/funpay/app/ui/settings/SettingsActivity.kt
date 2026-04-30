package com.funpay.app.ui.settings

import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.funpay.app.R
import com.funpay.app.databinding.ActivitySettingsBinding
import com.funpay.app.notifications.FunPayWorker
import com.funpay.app.utils.CookieHelper
import com.funpay.app.utils.NetworkHelper

/**
 * SettingsActivity — настройки приложения.
 *
 * Секции:
 * 1. Уведомления (вкл/выкл + интервал проверки)
 * 2. Данные и кэш (очистка кэша, cookies, проверка сети)
 * 3. О приложении
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        loadSettings()
        setupListeners()
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
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ─── Загрузка настроек ────────────────────────────────────────────────────

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Уведомления
        val notifsOn = prefs.getBoolean(KEY_NOTIF_ENABLED, true)
        binding.switchNotifications.isChecked = notifsOn
        binding.sliderInterval.isEnabled      = notifsOn

        // Интервал
        val interval = prefs.getLong(FunPayWorker.KEY_INTERVAL_MINUTES, 15L)
        binding.sliderInterval.value = interval.toFloat()
        updateIntervalLabel(interval)

        // Версия
        binding.tvAppVersion.text = getString(R.string.app_version)
    }

    // ─── Listeners ────────────────────────────────────────────────────────────

    private fun setupListeners() {

        // Уведомления
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            savePrefBool(KEY_NOTIF_ENABLED, isChecked)
            binding.sliderInterval.isEnabled = isChecked
            if (isChecked) { FunPayWorker.scheduleWork(this); toast(R.string.notifications_enabled) }
            else           { FunPayWorker.cancelWork(this);   toast(R.string.notifications_disabled) }
        }

        // Интервал проверки
        binding.sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val min = value.toLong()
                updateIntervalLabel(min)
                savePrefLong(FunPayWorker.KEY_INTERVAL_MINUTES, min)
                if (binding.switchNotifications.isChecked) FunPayWorker.scheduleWork(this)
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
            toast(R.string.cookies_cleared)
        }

        // Проверить соединение
        binding.btnCheckConnection.setOnClickListener {
            val ok  = NetworkHelper.isConnected(this)
            val typ = NetworkHelper.getConnectionType(this)
            toast(if (ok) getString(R.string.connection_ok, typ.name) else getString(R.string.connection_error))
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun updateIntervalLabel(min: Long) {
        binding.tvIntervalValue.text = getString(R.string.interval_minutes, min)
    }

    private fun savePrefBool(key: String, value: Boolean) =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(key, value).apply()

    private fun savePrefLong(key: String, value: Long) =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putLong(key, value).apply()

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val PREFS_NAME = "funpay_prefs"
        const val KEY_NOTIF_ENABLED = "notifications_enabled"
    }
}

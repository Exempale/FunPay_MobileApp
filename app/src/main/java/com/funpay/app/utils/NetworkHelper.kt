package com.funpay.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * NetworkHelper — утилита для проверки сетевого соединения.
 * Поддерживает современный API (Android 6+) и устаревший.
 */
object NetworkHelper {

    /**
     * Проверяет наличие активного интернет-соединения.
     * @return true если есть подключение к интернету
     */
    fun isConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Определяет тип соединения (WiFi / Мобильный интернет / Ethernet).
     */
    fun getConnectionType(context: Context): ConnectionType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return ConnectionType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return ConnectionType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            else -> ConnectionType.OTHER
        }
    }

    /**
     * Регистрирует слушатель изменений сети.
     * Позволяет реагировать на подключение/отключение в реальном времени.
     *
     * @param context Application context
     * @param onAvailable Вызывается при появлении сети
     * @param onLost Вызывается при потере сети
     * @return NetworkCallback для последующей отмены регистрации
     */
    fun registerNetworkCallback(
        context: Context,
        onAvailable: () -> Unit,
        onLost: () -> Unit
    ): ConnectivityManager.NetworkCallback {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable()
            }

            override fun onLost(network: Network) {
                onLost()
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        return callback
    }

    /**
     * Отменяет регистрацию слушателя сети.
     */
    fun unregisterNetworkCallback(
        context: Context,
        callback: ConnectivityManager.NetworkCallback
    ) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Игнорируем если уже отменён
        }
    }

    /**
     * Типы сетевых соединений.
     */
    enum class ConnectionType {
        NONE,       // Нет соединения
        WIFI,       // Wi-Fi
        CELLULAR,   // Мобильный интернет
        ETHERNET,   // Проводное соединение
        OTHER       // Другой тип
    }
}

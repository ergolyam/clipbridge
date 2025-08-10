package io.ergolyam.clipbridge.data

import android.content.Context

object Prefs {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("clipbridge_prefs", Context.MODE_PRIVATE)

    fun getHost(ctx: Context): String =
        prefs(ctx).getString("host", "192.168.0.100") ?: "192.168.0.100"

    fun getPort(ctx: Context): Int =
        prefs(ctx).getInt("port", 28900)

    fun saveEndpoint(ctx: Context, host: String, port: Int) {
        prefs(ctx).edit().apply {
            putString("host", host)
            putInt("port", port)
            apply()
        }
    }

    fun getAutoConnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean("auto_connect", false)

    fun setAutoConnect(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().apply {
            putBoolean("auto_connect", enabled)
            apply()
        }
    }
}


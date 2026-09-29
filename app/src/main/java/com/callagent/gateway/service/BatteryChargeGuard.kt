package com.callagent.gateway.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.callagent.gateway.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Pixel 4a charge hysteresis. Runs only on the gateway's background executor. */
class BatteryChargeGuard(private val context: Context) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "BatteryChargeGuard").apply { isDaemon = true }
    }
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun start() {
        worker.scheduleWithFixedDelay(::check, 0, 30, TimeUnit.SECONDS)
    }

    fun refresh() {
        worker.execute(::check)
    }

    fun stop() {
        worker.shutdownNow()
        if (prefs.getBoolean(KEY_OWNED, false)) {
            if (setDisabled(false)) {
                prefs.edit().putBoolean(KEY_OWNED, false).commit()
            }
        }
    }

    private fun check() {
        // This sysfs path is specific to the tested Pixel 4a.
        if (Build.DEVICE != "sunfish") return

        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val owned = prefs.getBoolean(KEY_OWNED, false)
        if (!enabled && !owned) return

        val current = RootShell.execForOutput("cat $DISABLE 2>/dev/null").trim().toIntOrNull()
        if (current !in 0..1) {
            Log.w(TAG, "Charging switch unavailable; leaving it unchanged")
            return
        }

        if (!enabled) {
            // Release a gate that *this* feature set. Do not interfere with
            // another charge manager when this feature has never controlled it.
            if (current == 0 || setDisabled(false)) {
                prefs.edit().putBoolean(KEY_OWNED, false).commit()
            }
            return
        }

        val start = prefs.getInt(KEY_START, 35)
        val stop = prefs.getInt(KEY_STOP, 65)
        if (start !in 5..90 || stop !in 10..100 || stop - start < 5) {
            Log.e(TAG, "Invalid battery thresholds: $start–$stop")
            return
        }
        val capacity = RootShell.execForOutput("cat $CAPACITY 2>/dev/null")
            .trim().toIntOrNull()
        if (capacity == null || capacity !in 0..100) {
            Log.w(TAG, "Battery capacity unavailable; leaving charger unchanged")
            return
        }

        val shouldDisable = when {
            capacity >= stop -> true
            capacity <= start -> false
            else -> return // Keep the previous state inside the hysteresis band.
        }
        if (current == if (shouldDisable) 1 else 0) return
        if (setDisabled(shouldDisable)) {
            if (shouldDisable) prefs.edit().putBoolean(KEY_OWNED, true).commit()
            Log.i(TAG, "Charge ${if (shouldDisable) "paused" else "resumed"} at $capacity% ($start–$stop%)")
        }
    }

    private fun setDisabled(disabled: Boolean): Boolean {
        val value = if (disabled) 1 else 0
        val ok = RootShell.exec("echo $value > $DISABLE", timeoutMs = 8000) == 0 &&
            RootShell.execForOutput("cat $DISABLE 2>/dev/null").trim() == value.toString()
        if (!ok) Log.e(TAG, "Could not set charge_disable=$value")
        return ok
    }

    companion object {
        private const val TAG = "BatteryChargeGuard"
        private const val DISABLE = "/sys/class/power_supply/smb5/charge_disable"
        private const val CAPACITY = "/sys/class/power_supply/battery/capacity"
        const val KEY_ENABLED = "battery_protection"
        const val KEY_START = "battery_start_threshold"
        const val KEY_STOP = "battery_stop_threshold"
        private const val KEY_OWNED = "battery_control_owned"
    }
}

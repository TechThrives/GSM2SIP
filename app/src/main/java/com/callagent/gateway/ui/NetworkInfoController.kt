package com.callagent.gateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callagent.gateway.R
import com.callagent.gateway.RootShell

/** Owns the network status cards and the mobile/Wi-Fi diagnostics dialog. */
class NetworkInfoController(private val activity: AppCompatActivity) {
    private val mobileView: TextView = activity.findViewById(R.id.tvNetMobile)
    private val wifiView: TextView = activity.findViewById(R.id.tvNetWifi)
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5_000)
        }
    }

    init {
        mobileView.setOnClickListener { showDetails(mobile = true) }
        wifiView.setOnClickListener { showDetails(mobile = false) }
    }

    fun startPolling() {
        handler.removeCallbacks(poll)
        poll.run()
    }

    fun stopPolling() {
        handler.removeCallbacks(poll)
    }

    fun refresh() {
        mobileView.text = mobileSummary()
        wifiView.text = wifiSummary()
    }

    private fun currentWifiInfo(): WifiInfo? {
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        return caps?.transportInfo as? WifiInfo
    }

    private fun mobileSummary(): String {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return "mobile: permission required"
        return try {
        val tm = activity.getSystemService(TelephonyManager::class.java)
        val name = tm.networkOperatorName?.ifEmpty { "No service" } ?: "No service"
        val type = when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
            else -> "?"
        }
        val dbm = tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
        if (dbm != null && dbm != Int.MAX_VALUE) "$type $name ${dbm}dBm" else "$type $name"
        } catch (_: Exception) {
            "mobile: n/a"
        }
    }

    private fun wifiSummary(): String = try {
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        val info = currentWifiInfo()
        when {
            !wm.isWifiEnabled -> "WiFi off"
            !onWifi -> "WiFi not connected"
            else -> {
                val raw = info?.ssid?.trim('"').orEmpty()
                val ssid = if (raw.isEmpty() || raw.contains("unknown", true)) "WiFi" else raw
                val freq = info?.frequency ?: 0
                val speed = info?.linkSpeed ?: -1
                val rssi = info?.rssi ?: 0
                val band = if (freq > 4000) "5G" else "2.4G"
                if (speed > 0) "$ssid $band ${speed}Mbps ${rssi}dBm"
                else "$ssid $band ${rssi}dBm"
            }
        }
    } catch (_: Exception) {
        "wifi: n/a"
    }

    private fun showDetails(mobile: Boolean) {
        val body = TextView(activity).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(48, 24, 48, 24)
            text = if (mobile) mobileDetailsFast() else wifiDetailsFast()
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (mobile) "Mobile network" else "WiFi")
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()

        fun append(line: String) = activity.runOnUiThread {
            if (dialog.isShowing) body.append(line)
        }

        Thread({
            if (mobile) {
                append("\n" + describeCells())
            } else {
                val extra = RootShell.execForOutput(
                    "echo MAC=$(cat /sys/class/net/wlan0/address 2>/dev/null); " +
                        "echo GW=$(ip route get 8.8.8.8 2>/dev/null | grep -oE 'via [0-9.]+' | awk '{print $2}')",
                    timeoutMs = 8_000,
                )
                val fields = extra.lines().mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to it.substring(i + 1).trim() else null
                }.toMap()
                val gateway = fields["GW"].orEmpty()
                append(
                    "Phone MAC    : ${fields["MAC"]?.ifEmpty { null } ?: "—"}\n" +
                        "Router IP    : ${gateway.ifEmpty { "—" }}\n"
                )
                if (gateway.isNotEmpty()) {
                    val mac = RootShell.execForOutput(
                        "ip neigh show $gateway 2>/dev/null | grep -oE '([0-9a-f]{2}:){5}[0-9a-f]{2}' | head -1",
                        timeoutMs = 5_000,
                    ).trim()
                    append("Router MAC   : ${mac.ifEmpty { "—" }}\n")
                }
                append("\n— reachability —\n")
                if (gateway.isNotEmpty()) append("Router  : ${pingAvg(gateway)}\n")
                val server = activity.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                    .getString("server", "") ?: ""
                if (server.isNotEmpty()) append("SIP srv : ${pingAvg(server)}  ($server)\n")
            }
        }, "link-details").start()
    }

    @SuppressLint("MissingPermission")
    private fun mobileDetailsFast(): String = buildString {
        try {
            val tm = activity.getSystemService(TelephonyManager::class.java)
            appendLine("Operator     : ${tm.networkOperatorName.ifEmpty { "—" }}")
            appendLine("MCC/MNC      : ${tm.networkOperator.ifEmpty { "—" }}")
            appendLine("Country      : ${tm.networkCountryIso.uppercase().ifEmpty { "—" }}")
            appendLine("SIM operator : ${tm.simOperatorName.ifEmpty { "—" }}")
            appendLine("SIM state    : ${simStateName(tm.simState)}")
            appendLine("Roaming      : ${if (tm.isNetworkRoaming) "yes" else "no"}")
            appendLine("Data network : ${networkTypeName(tm.dataNetworkType)}")
            appendLine("Voice network: ${networkTypeName(tm.voiceNetworkType)}")
            tm.signalStrength?.cellSignalStrengths?.forEachIndexed { i, signal ->
                appendLine(
                    "Signal[$i]    : ${signal.dbm} dBm, level ${signal.level}/4 " +
                        "(${signal.javaClass.simpleName.removePrefix("CellSignalStrength")})"
                )
            }
        } catch (e: Exception) {
            appendLine("telephony: ${e.message}")
        }
    }

    private fun wifiDetailsFast(): String = buildString {
        try {
            val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            appendLine("Enabled      : ${if (wm.isWifiEnabled) "yes" else "no"}")
            val info = currentWifiInfo()
            val rawSsid = info?.ssid?.trim('"').orEmpty()
            appendLine(
                "SSID         : " +
                    if (rawSsid.isEmpty() || rawSsid.contains("unknown", true)) "—" else rawSsid
            )
            val bssid = info?.bssid.orEmpty()
            appendLine(
                "BSSID (AP)   : " +
                    if (bssid.isEmpty() || bssid.startsWith("02:00:00")) "—" else bssid
            )
            appendLine("Security     : ${wifiSecurityName(info)}")
            val frequency = info?.frequency ?: 0
            appendLine("Link speed   : ${info?.linkSpeed ?: -1} Mbps")
            appendLine("Frequency    : $frequency MHz (${if (frequency > 4000) "5 GHz" else "2.4 GHz"})")
            appendLine("RSSI         : ${info?.rssi ?: 0} dBm")
            val cm = activity.getSystemService(ConnectivityManager::class.java)
            val link = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            val ip = link?.linkAddresses?.firstOrNull { it.address.hostAddress?.contains('.') == true }
            appendLine("IP address   : ${ip?.address?.hostAddress ?: "—"}")
            val dns = link?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ")
            appendLine("DNS          : ${dns?.ifEmpty { null } ?: "—"}")
        } catch (e: Exception) {
            appendLine("wifi: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun describeCells(): String {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return "needs location permission (granted by the Magisk module on boot)"
        return try {
            val cells = activity.getSystemService(TelephonyManager::class.java).allCellInfo
            if (cells.isNullOrEmpty()) "none reported" else cells.take(6).joinToString("\n") { describeCell(it) }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    private fun describeCell(info: CellInfo): String = buildString {
        fun row(name: String, value: String) = appendLine(name.padEnd(13) + ": " + value)
        fun num(value: Int) = if (value == Int.MAX_VALUE) "—" else value.toString()
        fun signal(value: android.telephony.CellSignalStrength) = "${value.dbm} dBm (${value.level}/4)"
        val role = if (info.isRegistered) "serving cell" else "neighbour"
        when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                row("Type", "LTE ($role)"); row("Cell ID", num(id.ci)); row("PCI", num(id.pci))
                row("TAC", num(id.tac)); row("EARFCN", num(id.earfcn))
                row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                row("Signal", signal(info.cellSignalStrength))
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityNr
                row("Type", "5G NR ($role)"); row("NCI", id?.nci?.toString() ?: "—")
                row("PCI", num(id?.pci ?: Int.MAX_VALUE)); row("TAC", num(id?.tac ?: Int.MAX_VALUE))
                row("NRARFCN", num(id?.nrarfcn ?: Int.MAX_VALUE))
                row("MCC/MNC", "${id?.mccString ?: "—"}/${id?.mncString ?: "—"}")
                row("Signal", signal(info.cellSignalStrength))
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                row("Type", "WCDMA ($role)"); row("Cell ID", num(id.cid)); row("LAC", num(id.lac))
                row("PSC", num(id.psc)); row("UARFCN", num(id.uarfcn))
                row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                row("Signal", signal(info.cellSignalStrength))
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                row("Type", "GSM ($role)"); row("Cell ID", num(id.cid)); row("LAC", num(id.lac))
                row("ARFCN", num(id.arfcn)); row("BSIC", num(id.bsic))
                row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                row("Signal", signal(info.cellSignalStrength))
            }
            else -> {
                row("Type", "${info.javaClass.simpleName.removePrefix("CellInfo")} ($role)")
                row("Signal", signal(info.cellSignalStrength))
            }
        }
    }

    private fun wifiSecurityName(info: WifiInfo?): String {
        if (info == null) return "—"
        return try {
            when (info.currentSecurityType) {
                WifiInfo.SECURITY_TYPE_OPEN -> "open (none)"
                WifiInfo.SECURITY_TYPE_WEP -> "WEP"
                WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-PSK"
                WifiInfo.SECURITY_TYPE_EAP -> "WPA-EAP"
                WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
                WifiInfo.SECURITY_TYPE_OWE -> "OWE (enhanced open)"
                WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
                WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3-Enterprise 192-bit"
                WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
                WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
                else -> "unknown"
            }
        } catch (_: Exception) {
            "—"
        }
    }

    private fun pingAvg(host: String): String {
        val output = RootShell.execForOutput(
            "ping -c 3 -W 2 $host 2>&1 | tail -2",
            timeoutMs = 12_000,
        )
        val average = Regex("= [0-9.]+/([0-9.]+)/").find(output)?.groupValues?.getOrNull(1)
        val loss = Regex("([0-9]+)% packet loss").find(output)?.groupValues?.getOrNull(1)
        return when {
            average != null -> "$average ms avg" + (loss?.let { ", $it% loss" } ?: "")
            loss == "100" -> "no reply (100% loss)"
            else -> output.lines().firstOrNull { it.isNotBlank() } ?: "unreachable"
        }
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        else -> "unknown($state)"
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "Unknown"
    }
}

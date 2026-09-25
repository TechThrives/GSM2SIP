package com.callagent.gateway.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.callagent.gateway.OwnNumber
import com.callagent.gateway.R
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.service.GatewayService

/** Owns the settings screen, per-SIM own numbers, and recents maintenance. */
class ConfigController(
    private val activity: AppCompatActivity,
    private val showHome: () -> Unit,
    private val showConfig: () -> Unit,
    private val refreshHome: () -> Unit,
    private val appendLog: (String) -> Unit,
) {
    data class SimInfo(
        val slot: Int,
        val subId: Int,
        val carrier: String,
        val caption: String,
    )

    private val ownNumberFields = mutableListOf<Pair<Int, EditText>>()

    private fun ownNumberKey(slot: Int) = "own_number_slot_$slot"

    @SuppressLint("MissingPermission")
    fun ownNumberForDisplay(
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ): String {
        val named = OwnNumber.isValidSubscription(subId)
        val prefs = activity.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val forSub = if (named) subId else SubscriptionManager.getDefaultSubscriptionId()
        val slot = runCatching {
            activity.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(forSub)?.simSlotIndex
        }.getOrNull()
        val fromBox = slot?.let { prefs.getString(ownNumberKey(it), "") }?.trim().orEmpty()
        return OwnNumber.fromSim(activity, forSub) ?: fromBox
    }

    @SuppressLint("MissingPermission")
    fun activeSims(): List<SimInfo> = try {
        val sm = activity.getSystemService(SubscriptionManager::class.java)
        val tm = activity.getSystemService(TelephonyManager::class.java)
        (sm?.activeSubscriptionInfoList ?: emptyList()).map { info ->
            val slot = info.simSlotIndex
            val subId = info.subscriptionId
            val carrier = (info.carrierName ?: info.displayName ?: "").toString().trim()
            // The IMEI belongs to the radio, so it names the slot; the ICCID
            // names the card in it.  Either tells two otherwise identical
            // rows apart, so show whichever the platform will hand over.
            val imei = runCatching { tm?.getImei(slot) }.getOrNull()
            val ident = imei
                ?.takeIf { it.isNotBlank() }?.let { "IMEI $it" }
                ?: info.iccId?.takeIf { it.isNotBlank() }
                    ?.let { "ICCID …${it.takeLast(6)}" }
                ?: ""
            SimInfo(
                slot = slot,
                subId = subId,
                carrier = carrier,
                caption = listOfNotNull(
                    "SIM ${slot + 1}",
                    carrier.ifEmpty { null },
                    ident.ifEmpty { null },
                ).joinToString("  ·  "),
            )
        }.sortedBy { it.slot }
    } catch (e: Exception) {
        android.util.Log.w("ConfigController", "Could not enumerate SIMs: ${e.message}")
        emptyList()
    }

    fun bind() {
        activity.findViewById<View>(R.id.btnCfgSave).setOnClickListener { save() }
        activity.findViewById<View>(R.id.btnCfgClearRecents).setOnClickListener {
            confirmClearRecents()
        }
    }

    fun open() {
        val prefs = activity.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        activity.findViewById<EditText>(R.id.etCfgServer).setText(prefs.getString("server", ""))
        activity.findViewById<EditText>(R.id.etCfgPort).setText(prefs.getInt("port", 5060).toString())
        activity.findViewById<EditText>(R.id.etCfgUser).setText(prefs.getString("user", ""))
        activity.findViewById<EditText>(R.id.etCfgPass).setText(prefs.getString("pass", ""))
        buildOwnNumberFields(prefs)
        activity.findViewById<CheckBox>(R.id.cbCfgAutoconnect).isChecked =
            prefs.getBoolean("autoconnect", true)
        activity.findViewById<CheckBox>(R.id.cbCfgUseStun).isChecked =
            prefs.getBoolean("use_stun", true)
        activity.findViewById<CheckBox>(R.id.cbCfgTranslit).isChecked =
            prefs.getBoolean("translit_ascii", false)
        val cbTls = activity.findViewById<CheckBox>(R.id.cbCfgTls)
        val cbSrtp = activity.findViewById<CheckBox>(R.id.cbCfgSrtp)
        cbTls.isChecked = prefs.getBoolean("sip_tls", false)
        cbSrtp.isChecked = prefs.getBoolean("srtp_enabled", false)

        fun syncSrtpEnabled() {
            cbSrtp.isEnabled = cbTls.isChecked
            activity.findViewById<TextView>(R.id.tvCfgSrtpHint).text = if (cbTls.isChecked) {
                "SDES-keyed SRTP (RFC 3711). Audio is encrypted when the server agrees; " +
                    "if it answers without SRTP the call continues unencrypted and the log says so."
            } else {
                "Requires TLS. The keys travel inside the SIP signalling, so over plain UDP " +
                    "they would be readable by anyone on the path."
            }
        }
        syncSrtpEnabled()
        cbTls.setOnCheckedChangeListener { _, checked ->
            syncSrtpEnabled()
            val port = activity.findViewById<EditText>(R.id.etCfgPort)
            val current = port.text.toString().trim().toIntOrNull()
            if (checked && current == 5060) {
                port.setText("5061")
                Toast.makeText(activity, "Port switched to 5061 for TLS", Toast.LENGTH_SHORT).show()
            } else if (!checked && current == 5061) {
                port.setText("5060")
                Toast.makeText(activity, "Port switched back to 5060", Toast.LENGTH_SHORT).show()
            }
        }
        activity.findViewById<RadioButton>(
            when (prefs.getString("codec", "g722")) {
                "g711" -> R.id.rbCodecG711
                "both" -> R.id.rbCodecBoth
                else -> R.id.rbCodecG722
            },
        ).isChecked = true

        val volume = activity.findViewById<SeekBar>(R.id.sbCfgAgentVolume)
        val volumeLabel = activity.findViewById<TextView>(R.id.tvCfgAgentVolume)
        fun stepText(step: Int) = if (step > 0) "+$step" else step.toString()
        volume.progress = prefs.getInt("agent_vol_step", 0).coerceIn(-3, 3) + 3
        volumeLabel.text = stepText(volume.progress - 3)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                volumeLabel.text = stepText(value - 3)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        showConfig()
    }

    private fun buildOwnNumberFields(prefs: SharedPreferences) {
        val container = activity.findViewById<LinearLayout>(R.id.llCfgOwnNumbers)
        container.removeAllViews()
        ownNumberFields.clear()
        for (sim in activeSims()) {
            val row = activity.layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val field = row.findViewById<EditText>(R.id.etSimNumber)
            field.setText(prefs.getString(ownNumberKey(sim.slot), "").orEmpty())
            row.findViewById<TextView>(R.id.tvSimCaption).text = sim.caption
            container.addView(row)
            ownNumberFields += sim.slot to field
        }
    }

    private fun confirmClearRecents() {
        val count = try {
            CallLogStore.getEntries(activity).size
        } catch (e: Exception) {
            android.util.Log.w("ConfigController", "Could not count recents: ${e.message}")
            0
        }
        if (count == 0) {
            Toast.makeText(activity, "Nothing to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Clear recents?")
            .setMessage("Removes all $count calls and messages from the list. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                Thread {
                    val cleared = try {
                        CallLogStore.clear(activity)
                        true
                    } catch (e: Exception) {
                        android.util.Log.w("ConfigController", "Could not clear recents: ${e.message}")
                        false
                    }
                    activity.runOnUiThread {
                        if (cleared) {
                            refreshHome()
                            Toast.makeText(activity, "Recents cleared", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Could not clear recents", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val server = activity.findViewById<EditText>(R.id.etCfgServer).text.toString().trim()
        val port = activity.findViewById<EditText>(R.id.etCfgPort).text.toString().trim().toIntOrNull() ?: 5060
        val user = activity.findViewById<EditText>(R.id.etCfgUser).text.toString().trim()
        val pass = activity.findViewById<EditText>(R.id.etCfgPass).text.toString().trim()
        val auto = activity.findViewById<CheckBox>(R.id.cbCfgAutoconnect).isChecked
        val stun = activity.findViewById<CheckBox>(R.id.cbCfgUseStun).isChecked
        val translit = activity.findViewById<CheckBox>(R.id.cbCfgTranslit).isChecked
        val tls = activity.findViewById<CheckBox>(R.id.cbCfgTls).isChecked
        val srtp = activity.findViewById<CheckBox>(R.id.cbCfgSrtp).isChecked
        val volumeStep = activity.findViewById<SeekBar>(R.id.sbCfgAgentVolume).progress - 3
        val codec = when (activity.findViewById<RadioGroup>(R.id.rgCfgCodec).checkedRadioButtonId) {
            R.id.rbCodecG711 -> "g711"
            R.id.rbCodecBoth -> "both"
            else -> "g722"
        }
        if (server.isEmpty() || user.isEmpty()) {
            Toast.makeText(activity, "Server and username are required", Toast.LENGTH_LONG).show()
            return
        }
        activity.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putString("server", server)
            .putInt("port", port)
            .putString("user", user)
            .putString("pass", pass)
            .also { editor ->
                ownNumberFields.forEach { (slot, field) ->
                    editor.putString(ownNumberKey(slot), field.text.toString().trim())
                }
            }
            .putBoolean("autoconnect", auto)
            .putBoolean("use_stun", stun)
            .putBoolean("translit_ascii", translit)
            .putBoolean("sip_tls", tls)
            .putBoolean("srtp_enabled", srtp)
            .putString("codec", codec)
            .putInt("agent_vol_step", volumeStep)
            .apply()
        appendLog(
            "Config saved: $user@$server:$port (codec=$codec, stun=${if (stun) "on" else "off"}, " +
                "tls=${if (tls) "on" else "off"}, srtp=${if (srtp && tls) "on" else "off"}, " +
                "ascii=${if (translit) "on" else "off"}, agent volume $volumeStep)"
        )
        Toast.makeText(activity, "Saved — reconnecting", Toast.LENGTH_SHORT).show()
        activity.startService(Intent(activity, GatewayService::class.java).apply {
            action = GatewayService.ACTION_APPLY_CONFIG
        })
        showHome()
    }
}

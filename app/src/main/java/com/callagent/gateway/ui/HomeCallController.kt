package com.callagent.gateway.ui

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.callagent.gateway.R
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.service.GatewayService
import java.util.Locale

/** Owns the active-call card, call timer, mute, monitor, and end-call actions. */
class HomeCallController(
    private val activity: AppCompatActivity,
    private val appendLog: (String) -> Unit,
    private val renderTraffic: () -> Unit,
    private val ownNumber: () -> String,
) {
    private val card: View = activity.findViewById(R.id.homeCallCard)
    private val direction: TextView = activity.findViewById(R.id.tvHomeCallDirection)
    private val timer: TextView = activity.findViewById(R.id.tvHomeCallTimer)
    private val from: TextView = activity.findViewById(R.id.tvHomeCallFrom)
    private val to: TextView = activity.findViewById(R.id.tvHomeCallTo)
    private val statusPill: TextView = activity.findViewById(R.id.tvHomeStatusPill)
    private val tlsBadge: TextView = activity.findViewById(R.id.tvHomeTlsBadge)
    private val srtpBadge: TextView = activity.findViewById(R.id.tvHomeSrtpBadge)
    private val muteButton: Button = activity.findViewById(R.id.btnHomeMute)
    private val monitorButton: Button = activity.findViewById(R.id.btnHomeSnoop)
    private val endButton: Button = activity.findViewById(R.id.btnHomeEnd)

    private var muted = false
    private var monitoring = false
    private var online = false
    private var registered = false
    private var callStart = 0L
    private var serviceCallStart = 0L
    private var timerHandle = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (callStart <= 0) return
            val elapsedMs = System.currentTimeMillis() - callStart
            val elapsed = elapsedMs / 1000
            timer.text = if (elapsed >= 3600) {
                String.format(Locale.US, "%d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
            } else {
                String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            }
            timerHandle.postDelayed(this, 1000 - (elapsedMs % 1000))
        }
    }

    fun bind() {
        muteButton.setOnClickListener { toggleMute() }
        monitorButton.setOnClickListener { toggleMonitor() }
        endButton.setOnClickListener { endCall() }
    }

    fun isOnline(): Boolean = online

    fun isRegistered(): Boolean = registered

    fun onGatewayStatus(state: String, info: String, isRegistered: Boolean, callStartMillis: Long) {
        registered = isRegistered
        serviceCallStart = callStartMillis
        val isOnline = when (state) {
            "STOPPED", "ERROR", "STARTING" -> false
            "IDLE" -> isRegistered
            else -> true
        }
        online = isOnline
        statusPill.text = if (isOnline) "● Online" else "● Offline"
        statusPill.setTextColor(Color.parseColor(if (isOnline) "#34D399" else "#F87171"))
        val config = activity.getSharedPreferences("gateway", android.content.Context.MODE_PRIVATE)
        val tls = config.getBoolean("sip_tls", false)
        tlsBadge.visibility = if (tls) View.VISIBLE else View.GONE
        srtpBadge.visibility = if (tls && config.getBoolean("srtp_enabled", false)) View.VISIBLE else View.GONE

        val callVisible = state == "BRIDGED" && GsmCallManager.isCallActive
        if (callVisible) {
            card.visibility = View.VISIBLE
            from.text = GsmCallManager.currentNumber ?: info
            val destination = ownNumber()
            to.text = if (destination.isNotEmpty()) "Connected to $destination" else "Connected"
            direction.text = "GSM → SIP"
            startTimer()
        } else {
            card.visibility = View.GONE
            callStart = 0L
            timerHandle.removeCallbacks(timerTick)
            if (muted) {
                muted = false
                setButton(muteButton, "Mute", R.drawable.ic_fa_volume_xmark)
            }
            if (monitoring) {
                monitoring = false
                updateMonitorButton()
            }
            renderTraffic()
        }
    }

    fun resume() {
        if (callStart > 0) timerTick.run()
    }

    fun pause() {
        timerHandle.removeCallbacks(timerTick)
    }

    private fun startTimer() {
        callStart = if (serviceCallStart > 0) serviceCallStart else System.currentTimeMillis()
        timerHandle.removeCallbacks(timerTick)
        timerTick.run()
    }

    private fun toggleMute() {
        muted = !muted
        activity.startService(Intent(activity, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MUTE_AGENT
            putExtra(GatewayService.EXTRA_MUTE_ON, muted)
        })
        setButton(
            muteButton,
            if (muted) "Unmute" else "Mute",
            if (muted) R.drawable.ic_fa_volume_high else R.drawable.ic_fa_volume_xmark,
        )
        appendLog(if (muted) "Agent muted to caller" else "Agent unmuted")
    }

    private fun toggleMonitor() {
        monitoring = !monitoring
        activity.startService(Intent(activity, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MONITOR
            putExtra(GatewayService.EXTRA_MONITOR_ON, monitoring)
        })
        updateMonitorButton()
        appendLog(if (monitoring) "Snoop on — both sides on the speaker" else "Snoop off")
    }

    private fun updateMonitorButton() {
        setButton(
            monitorButton,
            if (monitoring) "Stop" else "Snoop",
            if (monitoring) R.drawable.ic_fa_circle_stop else R.drawable.ic_fa_headphones,
        )
    }

    private fun endCall() {
        if (GsmCallManager.activeCall != null) GsmCallManager.hangupCall()
    }

    private fun setButton(button: Button, label: String, icon: Int) {
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
    }
}

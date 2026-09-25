package com.callagent.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callagent.gateway.R
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.sms.SmsOutbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the home traffic list and SMS detail presentation. */
class TrafficController(
    private val activity: AppCompatActivity,
    private val config: ConfigController,
) {
    private val list: LinearLayout = activity.findViewById(R.id.homeTrafficList)
    private val empty: TextView = activity.findViewById(R.id.tvHomeTrafficEmpty)
    private val allButton: Button = activity.findViewById(R.id.btnFilterAll)
    private val incomingButton: Button = activity.findViewById(R.id.btnFilterIncoming)
    private val outgoingButton: Button = activity.findViewById(R.id.btnFilterOutgoing)
    private var filter = "all"

    fun bind() {
        allButton.setOnClickListener { setFilter("all") }
        incomingButton.setOnClickListener { setFilter("in") }
        outgoingButton.setOnClickListener { setFilter("out") }
    }

    fun setFilter(value: String) {
        filter = value
        val selected = ContextCompat.getColor(activity, R.color.accent)
        val selectedText = ContextCompat.getColor(activity, R.color.accent_on)
        val normal = ContextCompat.getColor(activity, R.color.btn_secondary)
        val normalText = ContextCompat.getColor(activity, R.color.text_primary)
        for ((button, name) in listOf(
            allButton to "all",
            incomingButton to "in",
            outgoingButton to "out",
        )) {
            val active = name == value
            button.backgroundTintList = ColorStateList.valueOf(if (active) selected else normal)
            button.setTextColor(if (active) selectedText else normalText)
        }
        render()
    }

    fun restoreFilter() = setFilter(filter)

    fun render() {
        val entries = try { CallLogStore.getEntries(activity) } catch (_: Exception) { emptyList() }
        val visible = when (filter) {
            "in" -> entries.filter { it.direction == "IN" }
            "out" -> entries.filter { it.direction != "IN" }
            else -> entries
        }
        list.removeAllViews()
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm", Locale.US)
        for (entry in visible.take(30)) {
            val row = activity.layoutInflater.inflate(R.layout.item_call_row, list, false)
            val incoming = entry.direction == "IN"
            row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(
                if (incoming) R.drawable.ic_call_incoming else R.drawable.ic_call_outgoing,
            )
            row.findViewById<TextView>(R.id.tvRowNumber).text = entry.number
            val sms = entry.type == CallLogStore.TYPE_SMS
            row.findViewById<TextView>(R.id.tvRowSub).text = when {
                sms -> entry.text.replace('\n', ' ').trim().ifEmpty { "(no text)" }
                entry.durationSec > 0 -> if (incoming) "GSM → SIP" else "SIP → GSM"
                else -> "Not connected"
            }
            val duration = row.findViewById<TextView>(R.id.tvRowDuration)
            when {
                sms -> {
                    duration.text = "SMS"
                    duration.setTextColor(Color.parseColor("#60A5FA"))
                }
                entry.durationSec > 0 -> {
                    duration.text = String.format(
                        "%02d:%02d",
                        entry.durationSec / 60,
                        entry.durationSec % 60,
                    )
                    duration.setTextColor(Color.parseColor("#34D399"))
                }
                else -> {
                    duration.text = "—"
                    duration.setTextColor(Color.parseColor("#F87171"))
                }
            }
            val date = Date(entry.timestamp)
            val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).format(date) == today
            row.findViewById<TextView>(R.id.tvRowTime).text =
                if (sameDay) time.format(date) else "Earlier"
            if (sms) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { showSmsDetails(entry) }
            }
            list.addView(row)
        }
    }

    private fun showSmsDetails(entry: CallLogEntry) {
        val outgoing = entry.direction != "IN"
        val live = entry.smsId.takeIf { it.isNotEmpty() }
            ?.let { runCatching { SmsOutbox.get(activity, it) }.getOrNull() }
        val subId = if (entry.subId >= 0) entry.subId else live?.subId ?: -1
        val own = config.ownNumberForDisplay(subId)
        val sim = config.activeSims().firstOrNull { it.subId == subId }
        val parts = maxOf(entry.parts, live?.parts ?: 0)
        // Both SMS directions always write a status: inbound starts at
        // "pending" and moves to forwarded/discarded/queued, outbound starts at
        // "pending" and moves to sent/delivered/failed.  So the stored value is
        // authoritative and no fallback is needed.
        val status = entry.status
        val error = entry.error.ifEmpty { live?.lastError.orEmpty() }
        fun dash(value: String) = value.ifEmpty { "—" }
        fun row(label: String, value: String) = label.padEnd(9) + ": " + dash(value) + "\n"
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(entry.timestamp))
        val header = buildString {
            append(row("Direction", if (outgoing) "Outgoing" else "Incoming"))
            append(row("SIM", sim?.let {
                listOfNotNull("${it.slot + 1}", it.carrier.ifEmpty { null }).joinToString(" · ")
            }.orEmpty()))
            append(row("From", if (outgoing) own else entry.number))
            append(row("To", if (outgoing) entry.number else own))
            append(row("SMSC", entry.smsc))
            append(row("Date", stamp))
            append(row("Format", entry.encoding))
            append(row("Length", "${entry.text.length} chars" + if (parts > 0) ", $parts part${if (parts == 1) "" else "s"}" else ""))
            if (outgoing) {
                append(row("Status", status))
                if (error.isNotEmpty()) append(row("Error", error))
            }
        }
        val body = TextView(activity).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            text = header + "\n" + entry.text.ifEmpty { "(no text)" }
        }
        AlertDialog.Builder(activity)
            .setTitle("Message detail")
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SMS detail", body.text))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}

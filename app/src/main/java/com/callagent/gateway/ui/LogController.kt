package com.callagent.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.callagent.gateway.R
import com.callagent.gateway.service.GatewayService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the logs tab and the service log buffer presentation. */
class LogController(private val activity: AppCompatActivity) {
    private val view: TextView = activity.findViewById(R.id.tvLog)
    private val scroll: ScrollView = activity.findViewById(R.id.svLog)

    fun bind() {
        activity.findViewById<View>(R.id.btnLogsCopy).setOnClickListener { copy() }
        activity.findViewById<View>(R.id.btnLogsClear).setOnClickListener { clear() }
        view.text = ""
    }

    fun scrollToBottom() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun append(message: String) {
        appendRaw("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $message")
    }

    fun appendRaw(line: String) {
        activity.runOnUiThread {
            view.append("$line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun showSnapshot(lines: List<String>) {
        if (lines.isEmpty()) return
        view.text = lines.joinToString("\n", postfix = "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clear() {
        view.text = ""
        GatewayService.clearLogBuffer()
        Toast.makeText(activity, "Log cleared", Toast.LENGTH_SHORT).show()
    }

    private fun copy() {
        val text = view.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(activity, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("callagent log", text))
        Toast.makeText(activity, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

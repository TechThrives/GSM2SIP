package com.callagent.gateway

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.callagent.gateway.ui.ConfigController
import com.callagent.gateway.ui.HomeCallController
import com.callagent.gateway.ui.LogController
import com.callagent.gateway.ui.NetworkInfoController
import com.callagent.gateway.ui.TrafficController
import com.callagent.gateway.service.GatewayService

class MainActivity : AppCompatActivity() {

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (currentTab == "logs") {
                switchTab("config")
            } else if (currentTab != "home") {
                switchTab("home")
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val dialerRoleLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                logController.append("Set as default phone app")
            } else {
                logController.append("WARN: Not set as default phone app — GSM call handling disabled")
            }
        }

    // Tab containers + bottom bar
    private lateinit var tabHome: View
    private lateinit var tabConfig: View
    private lateinit var tvHomeStatusPill: TextView
    private lateinit var tvHomeTlsBadge: TextView
    private lateinit var tvHomeSrtpBadge: TextView
    private lateinit var networkController: NetworkInfoController
    private lateinit var logController: LogController
    private lateinit var configController: ConfigController
    private lateinit var trafficController: TrafficController
    private lateinit var homeCallController: HomeCallController
    private lateinit var tabLogs: LinearLayout
    private var currentTab = ""
    private var running = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatewayService.STATUS_ACTION -> {
                    val state = intent.getStringExtra("state") ?: return
                    val info = intent.getStringExtra("info") ?: ""
                    val registered = intent.getBooleanExtra("registered", homeCallController.isRegistered())
                    val callStart = intent.getLongExtra("call_start", 0L)
                    homeCallController.onGatewayStatus(state, info, registered, callStart)
                    running = state != "STOPPED" && state != "ERROR"
                    logController.append("[$state] $info")
                }
                GatewayService.LOG_ACTION -> logController.append(intent.getStringExtra("msg") ?: return)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, where AppCompat builds its decor.  Without
        // this the delegate ignores the manifest theme, falls back to
        // Theme.AppCompat.Empty -> DeviceDefault.Light.DarkActionBar, and
        // PhoneWindow installs a platform action bar whose inflation dies on
        // ?android:attr/colorPrimary.
        setTheme(R.style.Theme_SipGsmGateway)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        // Portrait lock, enforced at runtime as well as in the manifest.
        // A priv-app APK replaced in place is not always re-parsed by
        // PackageManager, so the manifest's screenOrientation can silently
        // stay at its previous value (dumpsys reports UNSPECIFIED).  Asking
        // for it here is immune to that staleness.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        // Tab containers
        logController = LogController(this)
        logController.bind()
        tabHome = findViewById(R.id.tabHome)
        tabConfig = findViewById(R.id.tabConfig)
        findViewById<View>(R.id.btnConfigBack).setOnClickListener { switchTab("home") }
        configController = ConfigController(
            this,
            showHome = { switchTab("home") },
            showConfig = { switchTab("config") },
            refreshHome = { refreshHome() },
            appendLog = { logController.append(it) },
        )
        configController.bind()
        networkController = NetworkInfoController(this)
        trafficController = TrafficController(this, configController)
        trafficController.bind()
        homeCallController = HomeCallController(
            this,
            appendLog = { logController.append(it) },
            renderTraffic = { trafficController.render() },
            ownNumber = { configController.ownNumberForDisplay() },
        )
        homeCallController.bind()
        tvHomeStatusPill = findViewById(R.id.tvHomeStatusPill)
        findViewById<View>(R.id.btnHomeMenu).setOnClickListener { configController.open() }
        // Tapping the status pill retries the connection, the way the old
        // settings screen's reconnect button did.
        tvHomeStatusPill.setOnClickListener {
            if (homeCallController.isOnline()) {
                // Already registered — reconnecting would drop a working
                // registration and send SIP the server did not need.
                Toast.makeText(this, "Registered", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            logController.append("Reconnect requested")
            Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RECONNECT
            })
        }

        // Named and versioned at the top of settings.  It is the first thing
        // asked for when a change appears not to have taken, and this deploy
        // path can leave the running build and the file on disk disagreeing.
        findViewById<TextView>(R.id.tvCfgVersion).text = "v${BuildConfig.VERSION_NAME}"

        // Logs view — opened from the Settings header, back returns there
        // rather than to home, so the icon behaves like a drill-down.
        tabLogs = findViewById(R.id.tabLogs)
        findViewById<View>(R.id.btnCfgLogs).setOnClickListener { switchTab("logs") }
        findViewById<View>(R.id.btnLogsBack).setOnClickListener { switchTab("config") }
        requestPermissions()
        requestBatteryOptimizationExemption()
        requestDefaultDialerRole()

        // Nothing is visible until a tab is selected — switchTab() returns
        // early when the requested tab is already current, so the initial
        // state has to be applied explicitly.
        switchTab("home")
        trafficController.setFilter("all")

        // Auto-start gateway if autoconnect enabled and credentials configured
        autoStartGateway()
    }

    private fun autoStartGateway() {
        if (running) return
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        if (!prefs.getBoolean("autoconnect", true)) return
        val server = prefs.getString("server", "") ?: ""
        val user = prefs.getString("user", "") ?: ""
        if (server.isEmpty() || user.isEmpty()) return
        val port = prefs.getInt("port", 5060)
        val pass = prefs.getString("pass", "") ?: ""
        GatewayService.start(this, server, port, user, pass)
        running = true
        logController.append("Auto-starting gateway: $user@$server:$port")
    }

    // ── Tab Navigation ───────────────────────────────────

    /**
     * Show one of the top-level views.  Navigation is the header menu on the
     * home view now; there is no bottom bar.
     */
    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        currentTab = tab

        tabHome.visibility = if (tab == "home") View.VISIBLE else View.GONE
        tabConfig.visibility = if (tab == "config") View.VISIBLE else View.GONE
        tabLogs.visibility = if (tab == "logs") View.VISIBLE else View.GONE

        when (tab) {
            "home" -> refreshHome()
            // The view scrolls as lines arrive, but only while it is visible;
            // opening it has to jump to the newest entry itself.
            "logs" -> logController.scrollToBottom()
        }
    }

    private fun refreshHome() {
        trafficController.render()
        networkController.refresh()
    }

    /**
     * Both network legs the gateway depends on: the modem carries the GSM call,
     * WiFi carries SIP and RTP.  A problem on either shows up as a broken call,
     * so it is worth seeing them side by side.
     */
    /**
     * Everything we can learn about one of the two links.
     *
     * What the framework can answer is shown immediately; the parts that need
     * a shell — MAC addresses, cell identity — and the pings are appended as
     * they arrive, so the dialog is never blank while a ping runs.
     *
     * SSID, BSSID and cell identity are gated behind location permission for
     * an ordinary app.  This one has root instead, so it reads them from the
     * system rather than holding a permission a gateway has no business with.
     */
    // Back navigation handled by backCallback in onCreate

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(GatewayService.STATUS_ACTION)
            addAction(GatewayService.LOG_ACTION)
        }
        // NOT_EXPORTED: the service sends these with setPackage(), so nothing
        // outside the app has any business delivering them — exported, any
        // installed app could feed the UI fabricated status and log lines.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        // Replay any log messages buffered while activity was paused
        // Re-render from the service's buffer rather than consuming it: the
        // lines are already stamped with when each event happened, and
        // replacing the view means a recreated activity shows the full recent
        // history instead of whatever it happened to witness.
        val buffered = GatewayService.logSnapshot()
        logController.showSnapshot(buffered)

        // Refresh the traffic list if it is the visible one.  This used to be
        // guarded on the old Calls tab, so after that tab went the home list
        // stopped being refreshed here at all and showed a stale view until
        // something else rebuilt it.
        if (currentTab == "home") {
            refreshHome()
        }

        // Re-apply the chip highlight: the visual state is set in code, so it
        // has to be restored whenever the view comes back.
        trafficController.restoreFilter()

        homeCallController.resume()
        networkController.startPolling()

        // Ask the service where it is.  Status is only pushed on change, so
        // opening the app onto an already-running gateway would otherwise show
        // "Offline" until something happened.
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STATUS
        })
    }

    override fun onPause() {
        super.onPause()
        homeCallController.pause()
        networkController.stopPolling()
        unregisterReceiver(statusReceiver)
    }

    // ── Permissions ─────────────────────────────────────

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG
            // Deliberately not location.  A gateway has no business asking for
            // it, and the two things that use it are cosmetic: the cell-id
            // readout in the info dialog, and the WiFi SSID (getSSID() has
            // returned "<unknown ssid>" without location since Android 8.1).
            // Both degrade to a placeholder instead.
        )
        // READ_PHONE_NUMBERS exists from API 30, below minSdk 31, so its
        // version check is gone.  POST_NOTIFICATIONS stays gated: it is a
        // genuine API 33 addition and requesting it on an older build throws.
        perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requestDefaultDialerRole() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (packageName == tm.defaultDialerPackage) return

        // minSdk 31 >= API 29, so RoleManager is the only path.  The
        // pre-Q ACTION_CHANGE_DEFAULT_DIALER broadcast it replaces was
        // deprecated in Android 10 and is ignored by modern builds, so the
        // whole @Suppress("DEPRECATION") fallback goes with the version check.
        val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            !rm.isRoleHeld(RoleManager.ROLE_DIALER)
        ) {
            dialerRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                logController.append("WARN: Denied permissions: ${denied.joinToString()}")
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 100
    }
}

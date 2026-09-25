package com.callagent.gateway.gsm

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * InCallService implementation: intercepts all GSM calls on the device.
 *
 * When registered as the default dialer (or with BIND_INCALL_SERVICE permission
 * on rooted device), Android routes all call events through this service.
 *
 * Based on the telon-org/react-native-tele InCallService approach.
 */
class GsmCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        // Call.getState() was deprecated in API 31 — exactly this app's minSdk —
        // in favour of Call.Details.getState(), which is where it read from
        // anyway.  The handle two lines up already treats a null Details as
        // possible, and STATE_NEW is the value GsmCallManager initialises
        // activeCallState with, so a missing Details cannot invent a state the
        // rest of the code would not already accept as "not yet connected".
        val state = call.details?.state ?: Call.STATE_NEW
        Log.i(TAG, "Call added: number=$number state=$state")

        call.registerCallback(callCallback)
        GsmCallManager.onCallAdded(call, this)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "Call removed")
        call.unregisterCallback(callCallback)
        GsmCallManager.onCallRemoved(call)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        GsmCallManager.onServiceUnbound(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GsmCallManager.onServiceUnbound(this)
        super.onDestroy()
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val stateStr = when (state) {
                Call.STATE_DIALING -> "DIALING"
                Call.STATE_RINGING -> "RINGING"
                Call.STATE_ACTIVE -> "ACTIVE"
                Call.STATE_HOLDING -> "HOLDING"
                Call.STATE_DISCONNECTED -> "DISCONNECTED"
                Call.STATE_CONNECTING -> "CONNECTING"
                Call.STATE_DISCONNECTING -> "DISCONNECTING"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_ACCOUNT"
                else -> "UNKNOWN($state)"
            }
            Log.i(TAG, "Call state changed: $stateStr")
            GsmCallManager.onCallStateChanged(call, state)
        }
    }

    companion object {
        private const val TAG = "GsmCallService"
    }
}

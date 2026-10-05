package com.callagent.gateway.sip

import android.content.Context
import android.os.Build
import android.util.Log
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.OwnNumber
import com.callagent.gateway.gsm.GsmCallManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Announces this gateway to the server after REGISTER via page-mode MESSAGE.
 * Body is JSON mirrored into X-GW-* headers; text/plain is required by res_pjsip.
 */
object GatewayAnnouncement {

    private const val TAG = "GatewayAnnouncement"

    /** Marks the message as a lifecycle announcement, not an SMS. */
    const val HEADER_EVENT = "X-GW-Event"

    /** The only event value sent today. */
    const val EVENT_REGISTERED = "registered"

    /** Send the announcement. Blocking — call off the receive thread. Failures are logged, not retried. */
    fun announceRegistered(context: Context, sip: SipClient, username: String) {
        val fields = describe(context)
        val headers = mutableListOf(
            "$HEADER_EVENT: $EVENT_REGISTERED",
            "X-GW-Id: ${fields.id}",
            "X-GW-Version: ${BuildConfig.VERSION_NAME}",
            "X-GW-Manufacturer: ${fields.manufacturer}",
            "X-GW-Model: ${fields.model}",
            "X-GW-Device: ${fields.device}",
            "X-GW-Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "X-GW-Profile: ${fields.profile}",
            "X-GW-Transport: ${if (sip.useTls) "TLS" else "UDP"}",
            "X-GW-SRTP: ${if (sip.srtpEnabled) "on" else "off"}",
            "X-GW-Codec: ${SipBuilder.codecMode}",
            "X-GW-Local-Ip: ${sip.localIp}",
            "X-GW-Public-Ip: ${sip.publicIp}",
            "X-GW-At: ${isoNow()}"
        )
        // Only SIMs with a strict E.164 number; others omitted.
        val numbers = ownNumbers(context)
        numbers.forEachIndexed { index, number ->
            headers += "X-GW-Number-$index: $number"
        }
        headers += "X-GW-Numbers: ${numbers.size}"

        val body = JSONObject().apply {
            put("event", EVENT_REGISTERED)
            put("id", fields.id)
            put("version", BuildConfig.VERSION_NAME)
            put("manufacturer", fields.manufacturer)
            put("model", fields.model)
            put("device", fields.device)
            put("android", Build.VERSION.RELEASE)
            put("api", Build.VERSION.SDK_INT)
            put("profile", fields.profile)
            put("transport", if (sip.useTls) "TLS" else "UDP")
            put("srtp", sip.srtpEnabled)
            put("codec", SipBuilder.codecMode)
            put("localIp", sip.localIp)
            put("publicIp", sip.publicIp)
            put("numbers", numbers.toList())
            put("at", isoNow())
        }.toString()

        // To our own AOR; the dialplan exten comes from the Request-URI user.
        val code = sip.sendSipMessage(
            targetUri = "sip:$username@${sip.serverDomain}",
            fromUser = username,
            body = body,
            extraHeaders = headers,
            contentType = "text/plain;charset=UTF-8"
        )
        when {
            code == 200 || code == 202 ->
                Log.i(TAG, "Announced registration to $username: profile=${fields.profile} " +
                    "numbers=${numbers.size} (SIP $code)")
            // 415 means wrong content type — a bug here, not a server problem.
            code == 415 -> Log.e(TAG, "Registration announcement refused 415 — " +
                "res_pjsip requires text/plain on an out-of-dialog MESSAGE")
            code == 0 -> Log.w(TAG, "Registration announcement sent to $username: no response")
            else -> Log.w(TAG, "Registration announcement to $username refused ($code)")
        }
    }

    /** Stable handset id for logs/routing, not a credential. */
    private fun describe(context: Context): Fields {
        val device = (Build.DEVICE ?: "").ifBlank { Build.MODEL ?: "unknown" }
        return Fields(
            id = "$device-${BuildConfig.VERSION_NAME}",
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            device = device,
            // Audio path: digital vs speaker/mic fallback.
            profile = GsmCallManager.profile.name
        )
    }

    private data class Fields(
        val id: String,
        val manufacturer: String,
        val model: String,
        val device: String,
        val profile: String,
    )

    /** Active SIM numbers via OwnNumber — same lookup as the call path. */
    private fun ownNumbers(context: Context): List<String> {
        val out = mutableListOf<String>()
        for (info in OwnNumber.activeSubscriptions(context)) {
            val subId = info.subscriptionId
            if (!OwnNumber.isValidSubscription(subId)) continue
            val raw = OwnNumber.numberForSubscriptionId(context, subId)
            if (raw.isNullOrBlank()) {
                Log.w(TAG, "SIM slot ${info.simSlotIndex} has no resolvable number — omitted")
                continue
            }
            val e164 = OwnNumber.toE164(context, raw, subId)
            if (e164.isNullOrBlank() || !OwnNumber.isE164(e164)) {
                Log.w(TAG, "SIM slot ${info.simSlotIndex} number '$raw' is not +E.164 — omitted")
                continue
            }
            if (e164 !in out) out += e164
        }
        if (out.isEmpty()) Log.w(TAG, "No SIM number resolved; announcing with none")
        return out
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
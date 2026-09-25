package com.callagent.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.callagent.gateway.OwnNumber

/**
 * Hands an outbound SMS to the modem and keeps the paperwork straight.
 *
 * Android reports a long message per *part*: one sent result and one delivery
 * report for each of them.  A caller wants one answer per message, so the
 * parts are counted back in and only a complete set produces a report.
 */
object SmsSender {
    private const val TAG = "SmsSender"

    const val ACTION_SENT = "com.callagent.gateway.SMS_SENT"
    const val ACTION_DELIVERED = "com.callagent.gateway.SMS_DELIVERED"
    const val EXTRA_ID = "sms_id"
    const val EXTRA_PART = "sms_part"

    /**
     * Request codes for PendingIntents, handed out in strictly increasing
     * order and persisted across process restarts.
     *
     * These used to be derived from `id.hashCode()` — a 32-bit hash of a
     * UUID string, folded into an Int.  `PendingIntent.getBroadcast` matches
     * on request code + action + component and ignores extras, so two
     * messages whose hashes collided on the same code would silently
     * *overwrite* each other's extras under FLAG_UPDATE_CURRENT.  The
     * carrier's callback for the first message would then arrive carrying
     * the second message's id/part and book the delivery against the wrong
     * record.  With many messages in flight over a long-running gateway the
     * birthday bound makes that a matter of time, not luck.
     *
     * A counter cannot collide.  It is seeded from prefs because the
     * platform's PendingIntent table survives the app process: restarting at
     * zero would reissue codes the system still holds entries for, which is
     * the same overwrite in a different costume.  Prefs are read once and
     * written back with commit(), synchronously: apply()'s write is async,
     * so a process killed between the increment and it landing on disk would
     * restart one code behind and could reissue codes the system still
     * holds.  One write per outbound SMS part is well off the hot path, so
     * the synchronous cost is free where it matters.
     *
     * Negative codes are legal but avoided: masking to 31 bits keeps every
     * code a clean positive Int, which is easier to read in a bug report.
     */
    private val requestCodeSeq = java.util.concurrent.atomic.AtomicInteger(-1)

    private fun nextRequestCode(context: Context): Int {
        val prefs = context.getSharedPreferences("sms_pending", Context.MODE_PRIVATE)
        val seq = requestCodeSeq
        if (seq.get() < 0) {
            // Seed from the persisted counter, one ahead of anything already
            // handed out, so a restart cannot reuse a live code.
            seq.compareAndSet(-1, prefs.getInt("next_request_code", 0).coerceAtLeast(1))
        }
        val code = (seq.getAndIncrement() and 0x7fffffff).coerceAtLeast(1)
        prefs.edit().putInt("next_request_code", seq.get()).commit()
        return code
    }

    /**
     * Send one queued message.  Returns false if the modem refused it outright,
     * in which case nothing will ever call back and the caller must report the
     * failure itself.
     */
    fun dispatch(context: Context, sms: OutboundSms): Boolean {
        return try {
            val manager = smsManagerFor(context, sms.subId)
            val parts = manager.divideMessage(sms.text)
            val count = parts.size

            SmsOutbox.update(context, sms.id) { it.copy(parts = count) }

            val sentIntents = ArrayList<PendingIntent>(count)
            val deliveryIntents = ArrayList<PendingIntent>(count)
            for (i in 0 until count) {
                sentIntents.add(pendingIntent(context, ACTION_SENT, sms.id, i))
                deliveryIntents.add(pendingIntent(context, ACTION_DELIVERED, sms.id, i))
            }

            manager.sendMultipartTextMessage(sms.to, null, parts, sentIntents, deliveryIntents)
            Log.i(TAG, "Dispatched ${sms.id} to ${sms.to} ($count part(s), sub=${sms.subId})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch of ${sms.id} failed: ${e.message}", e)
            SmsOutbox.update(context, sms.id) {
                it.copy(sentFailed = maxOf(it.parts, 1), lastError = describe(e))
            }
            false
        }
    }

    /**
     * The SmsManager bound to the SIM the server asked for.
     *
     * A dual-SIM device has no meaningful "default" for a gateway: a reply has
     * to leave by the SIM the conversation is on, so an explicit subscription
     * is used whenever the request names one.
     */
    private fun smsManagerFor(context: Context, subId: Int): SmsManager {
        // context.getSystemService(SmsManager::class.java) IS the replacement
        // that SmsManager.getDefault() has pointed at since it was deprecated
        // in API 31, and minSdk is 31 — so the deprecated fallback is dead
        // weight now.  Dropping it also changes what a null means: instead of
        // silently falling back to the default-SIM manager (which on a
        // dual-SIM device can send the reply out of the wrong box — the exact
        // failure this function exists to prevent), it fails loudly.  A null
        // here means the platform never registered the service at all.
        val base = context.getSystemService(SmsManager::class.java)
            ?: error("SmsManager system service unavailable")
        if (!OwnNumber.isValidSubscription(subId)) {
            error("No valid SIM subscription for SMS source")
        }
        return runCatching { base.createForSubscriptionId(subId) }
            .getOrElse { error("Could not bind SmsManager to subscription $subId: ${it.message}") }
    }

    /**
     * Distinct PendingIntents per part.  Without a unique request code the
     * platform hands every part the same one and the parts become
     * indistinguishable.
     *
     * FLAG_MUTABLE is not a lapse here, it is the requirement: telephony
     * reports its results by *filling in* extras — the network's cause code on
     * a failure, and the status report's PDU on delivery — and an immutable
     * PendingIntent drops both without a word.  That is why failures read as a
     * bare "modem_err" with no reason, and why every delivery report parsed as
     * status=unknown.  The intent is explicit — our own package, our own
     * receiver class — so nothing else can be targeted through it.
     *
     * Each call takes its own request code from [nextRequestCode] rather than
     * one derived from the message id: `PendingIntent` matches on request
     * code + action + component and ignores extras, so two live codes that
     * collide make FLAG_UPDATE_CURRENT overwrite the first message's extras
     * with the second's, and the first callback is then booked against the
     * wrong record.
     */
    private fun pendingIntent(context: Context, action: String, id: String, part: Int): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            setClass(context, SmsSendReceiver::class.java)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_PART, part)
        }
        val requestCode = nextRequestCode(context)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Android's send-failure codes, as something a dialplan can read.
     *
     * The RIL range (100+) is where the interesting failures live — a bare
     * "error_111" says nothing, while "modem_err" plus the network's own
     * cause value says whether to retry, fix the number, or call the carrier.
     */
    fun sentResultName(resultCode: Int): String = when (resultCode) {
        android.app.Activity.RESULT_OK -> "ok"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit_exceeded"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short_code_not_allowed"
        100 -> "radio_not_available"
        101 -> "send_fail_retry"
        102 -> "network_reject"
        103 -> "invalid_state"
        104 -> "invalid_arguments"
        105 -> "no_memory"
        106 -> "request_rate_limited"
        107 -> "invalid_sms_format"
        108 -> "system_err"
        109 -> "encoding_err"
        110 -> "invalid_smsc_address"
        111 -> "modem_err"
        112 -> "network_err"
        113 -> "internal_err"
        114 -> "request_not_supported"
        115 -> "invalid_modem_state"
        116 -> "network_not_ready"
        117 -> "operation_not_allowed"
        118 -> "no_resources"
        119 -> "cancelled"
        120 -> "sim_absent"
        else -> "error_$resultCode"
    }

    /**
     * The network's own reason, from GSM 04.11 — carried in the sent
     * broadcast's "errorCode" extra when the failure came from the network
     * rather than the framework.
     */
    fun networkCauseName(cause: Int): String = when (cause) {
        1 -> "unassigned_number"
        8 -> "operator_determined_barring"
        10 -> "call_barred"
        21 -> "sms_transfer_rejected"
        27 -> "destination_out_of_order"
        28 -> "unidentified_subscriber"
        29 -> "facility_rejected"
        30 -> "unknown_subscriber"
        38 -> "network_out_of_order"
        41 -> "temporary_failure"
        42 -> "congestion"
        47 -> "resources_unavailable"
        69 -> "facility_not_implemented"
        95 -> "semantically_incorrect"
        96 -> "invalid_mandatory_information"
        111 -> "protocol_error"
        127 -> "interworking"
        else -> "cause_$cause"
    }

    private fun describe(e: Exception): String =
        "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
}

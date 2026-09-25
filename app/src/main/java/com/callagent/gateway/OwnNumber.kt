package com.callagent.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * The gateway's own number as the *platform* reports it, for one subscription.
 *
 * Both directions of the bridge have to agree on this value: CallOrchestrator
 * uses it to address an inbound call, GatewayService uses it to address an
 * SMS.  A message and a call that disagree about which SIM they were on would
 * be mapped to two different assistants by the same server, so the lookup is
 * written once here rather than twice and left to drift — which is exactly how
 * the SMS path ended up not reading the SIM at all while the call path did.
 *
 * The chain is the same three steps everywhere, each only consulted when the
 * one before it had nothing:
 *
 *   1. what the platform publishes for this subscription  — this file
 *   2. `own_number_slot_<slot>`, the box the operator typed for that SIM
 *   3. the SIP account — loud, but at least it says so
 *
 * There is no step that borrows *another* SIM's box: a borrowed value would
 * be non-empty but belong to a different SIM, which routing cannot tell from
 * the right one.  Each subscription is answered only from its own platform
 * value or its own `own_number_slot_<slot>`; a miss stays empty and the
 * caller reports it.
 */
object OwnNumber {

    private const val TAG = "OwnNumber"

    /**
     * The MSISDN the platform publishes for [subId], or null when it publishes
     * none — which most carriers do not, and which is why this can only ever
     * be the first step.
     *
     * On Android 13 and above [SubscriptionManager.getPhoneNumber] takes the
     * subscription directly and there is nothing to get wrong.  Below that the
     * only accessor is `TelephonyManager.line1Number`, and the handle returned
     * by `getSystemService(TELEPHONY_SERVICE)` is bound to the **default**
     * subscription: it answers with SIM 1's number for a call that arrived on
     * SIM 2.  That answer is non-empty, so the caller returns it there and the
     * per-slot box is never reached — a confidently wrong DID with nothing in
     * the log to say so, which is the precise failure the box exists to
     * prevent.  [TelephonyManager.createForSubscriptionId] rebinds the handle
     * to the subscription the call actually came in on; it is API 22 against
     * this app's minSdk of 31, so it needs no version guard of its own.
     *
     * [subId] of [SubscriptionManager.INVALID_SUBSCRIPTION_ID] means the caller
     * had no SIM to name, which keeps the pre-existing single-SIM behaviour of
     * asking about the default subscription.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun fromSim(context: Context, subId: Int): String? = try {
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val subMgr = context.getSystemService(SubscriptionManager::class.java)
            if (isValidSubscription(subId)) subMgr?.getPhoneNumber(subId)
            else subMgr?.getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
        } else {
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val scoped = if (isValidSubscription(subId)) {
                runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
            } else base
            scoped.line1Number
        }
        number?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "SIM number unavailable: ${e.message}")
        null
    }

    /** Resolve a source number to one active subscription. */
    @SuppressLint("MissingPermission")
    fun subscriptionIdForNumber(context: Context, number: String): Int? {
        val matches = subscriptions(context).filter { info ->
            val own = numberForSubscriptionId(context, info.subscriptionId)
            val target = normalizeNumber(context, number, info.subscriptionId)
            own != null && target != null && numbersMatch(
                target = target,
                candidate = normalizeNumber(context, own, info.subscriptionId)
            )
        }

        return when (matches.size) {
            1 -> matches.single().subscriptionId
            0 -> {
                Log.w(TAG, "No SIM matches source number $number")
                null
            }
            else -> {
                Log.w(TAG, "Source number $number matches ${matches.size} SIMs")
                null
            }
        }
    }

    private fun numbersMatch(target: String, candidate: String?): Boolean {
        if (candidate == null) return false
        val targetDigits = target.filter(Char::isDigit)
        val candidateDigits = candidate.filter(Char::isDigit)
        val targetNational = targetDigits.trimStart('0').ifEmpty { targetDigits }
        val candidateNational = candidateDigits.trimStart('0').ifEmpty { candidateDigits }
        return targetDigits.isNotEmpty() && candidateDigits.isNotEmpty() &&
            (targetDigits == candidateDigits ||
                candidateDigits == targetNational ||
                targetDigits == candidateNational ||
                targetDigits.endsWith(candidateNational) ||
                candidateDigits.endsWith(targetNational))
    }

    private fun normalizeNumber(
        context: Context,
        number: String,
        subId: Int?
    ): String? {
        val trimmed = number.trim().filterNot { it in " -/" }
        if (trimmed.startsWith("+")) return trimmed.filter(Char::isDigit)
        if (trimmed.startsWith("00")) {
            return trimmed.removePrefix("00").filter(Char::isDigit).ifEmpty { null }
        }

        val iso = subId?.let {
            runCatching {
                val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (isValidSubscription(it)) {
                    base.createForSubscriptionId(it).simCountryIso
                } else {
                    base.simCountryIso
                }
            }.getOrNull()
        }?.uppercase()?.ifEmpty { null }

        return if (iso != null) {
            PhoneNumberUtils.formatNumberToE164(trimmed, iso)
                ?.filter(Char::isDigit)
                ?.ifEmpty { null }
        } else {
            trimmed.filter(Char::isDigit).ifEmpty { null }
        }
    }

    /** Return the configured/platform number for one subscription. */
    fun numberForSubscriptionId(context: Context, subId: Int): String? {
        val subscription = if (isValidSubscription(subId)) {
            subId
        } else {
            SubscriptionManager.getDefaultSubscriptionId()
        }
        val slot = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subscription)
                ?.simSlotIndex
        }.getOrNull()
        return fromSim(context, subscription)
            ?: slot?.let {
                context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                    .getString("own_number_slot_$it", "")
                    ?.trim()
                    ?.ifEmpty { null }
            }
    }

    private fun subscriptions(context: Context) =
        context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            .orEmpty()

    /**
     * Whether [subId] identifies a subscription that can be queried.
     *
     * The invalid and negative sentinel values mean "ask about the default
     * subscription" rather than naming a specific SIM.
     */
    fun isValidSubscription(subId: Int): Boolean =
        subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            SubscriptionManager.isValidSubscriptionId(subId)
}

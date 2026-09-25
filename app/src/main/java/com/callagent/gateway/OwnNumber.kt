package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

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

    /** Active subscriptions, or an empty list when phone-state access is absent. */
    fun activeSubscriptions(context: Context): List<SubscriptionInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE is not granted; cannot enumerate subscriptions")
            return emptyList()
        }
        return runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
        }.getOrElse {
            Log.w(TAG, "Could not enumerate subscriptions: ${it.message}")
            emptyList()
        }
    }

    /** SIM slot for a subscription, or null when it cannot be queried. */
    fun simSlotForSubscription(context: Context, subId: Int): Int? {
        val subscription = if (isValidSubscription(subId)) {
            subId
        } else {
            SubscriptionManager.getDefaultSubscriptionId()
        }
        if (!isValidSubscription(subscription) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subscription)
                ?.simSlotIndex
        }.getOrNull()
    }

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
        number?.let { toE164(context, it, subId) }
    } catch (e: Exception) {
        Log.w(TAG, "SIM number unavailable: ${e.message}")
        null
    }

    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    fun isE164(number: String): Boolean = E164.matches(number.trim())

    /**
     * Convert national, spaced, 00-prefixed, or already international input to
     * strict +E.164 using the selected SIM's country. Returns null rather than
     * forwarding an ambiguous value when the platform cannot normalize it.
     */
    fun toE164(
        context: Context,
        number: String,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    ): String? {
        val trimmed = number.trim().filterNot { it in " -/()" }
        if (isE164(trimmed)) return trimmed
        val international = if (trimmed.startsWith("00") && trimmed.length > 4) {
            "+${trimmed.drop(2).filter(Char::isDigit)}"
        } else {
            trimmed
        }
        if (isE164(international)) return international
        if (trimmed.isEmpty() || trimmed.any { !it.isDigit() && it != '+' }) return null

        val iso = runCatching {
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val scoped = if (isValidSubscription(subId)) {
                runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
            } else {
                base
            }
            scoped.simCountryIso?.uppercase()?.ifEmpty { null }
        }.getOrNull()
        if (iso == null) return null
        return PhoneNumberUtils.formatNumberToE164(trimmed, iso)
            ?.takeIf(::isE164)
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
    ): String? = toE164(context, number, subId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    /** Return the configured/platform number for one subscription. */
    fun numberForSubscriptionId(context: Context, subId: Int): String? {
        val subscription = if (isValidSubscription(subId)) {
            subId
        } else {
            SubscriptionManager.getDefaultSubscriptionId()
        }
        val slot = simSlotForSubscription(context, subscription)
        return fromSim(context, subscription)
            ?: slot?.let {
                context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                    .getString("own_number_slot_$it", "")
                    ?.let { configured -> toE164(context, configured, subscription) }
            }
    }

    private fun subscriptions(context: Context) = activeSubscriptions(context)

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

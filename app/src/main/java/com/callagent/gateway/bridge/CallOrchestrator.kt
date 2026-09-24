package com.callagent.gateway.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.OwnNumber
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.SrtpContext
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 *
 * Two call flows:
 *
 * INBOUND (someone calls the Israeli SIM):
 *   1. GSM rings → keep ringing, place SIP call to Asterisk
 *   2. Asterisk/agent answers SIP → answer GSM call
 *   3. GSM goes active → RTP starts immediately
 *   4. Audio flows: GSM speaker/mic ↔ RTP/SIP (shared hardware)
 *   5. Either side hangs up → terminate both
 *
 *   Caller hears normal ringing until the agent is ready, then
 *   picks up and hears the agent immediately — no dead air.
 *
 * OUTBOUND (Asterisk wants to call an Israeli number):
 *   1. SIP INVITE arrives with X-GSM-Forward header
 *   2. Dial GSM call to the destination
 *   3. GSM answers → SIP 200 OK
 *   4. Audio flows: SIP RTP ↔ GSM speaker/mic (shared hardware)
 *   5. Either side hangs up → terminate both
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    // Every field below is written from the Telecom callback (main thread),
    // the SIP receive thread (onRtpReady / onCallTerminated) and the spawned
    // SIP-OutCall / RTP-Start threads, and read from all of them plus
    // setAgentMuted / setMonitorEnabled.  The generation counter protects
    // *ordering* — a stale timer must not tear down a newer call — but it
    // says nothing about *visibility*: without a fence there is no guarantee
    // a write on one thread is ever observed by another, and the JVM is free
    // to keep these in a register or a CPU cache indefinitely.  That is the
    // shape of the logged "GSM active but no pending RTP info" case, where
    // the value was set moments earlier by a different thread.
    @Volatile private var activeRtpSession: RtpSession? = null
    @Volatile private var activeSipCall: SipCall? = null
    @Volatile private var activeGsmCall: Call? = null
    @Volatile private var diallerInitiated = false
    @Volatile private var lastStateChangeTime = 0L

    // Pending RTP info: saved when SIP answers before GSM is picked up.
    // onGsmCallActive reads these to start RTP immediately after GSM pickup.
    // Written by the SIP receive thread, read by the Telecom callback.
    @Volatile private var pendingRtpAddr: String? = null
    @Volatile private var pendingRtpPort: Int = 0
    @Volatile private var pendingPayloadType: Int = 0
    @Volatile private var pendingLocalRtpPort: Int = 0

    // SIP call retry: if SIP fails while GSM is ringing, retry before giving up.
    // Transient network issues or socket races can kill the first attempt.
    // Read and written from SIP-received and timer threads alike.
    @Volatile private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /**
     * Start a fresh SIP-retry allowance for a new call attempt.
     *
     * [sipCallRetries] used to be zeroed only in [onIncomingGsmCall], but the
     * counter gates [onCallTerminated]'s retry path, which is also reachable
     * from the dialler-initiated flow — and that flow never passes through
     * onIncomingGsmCall.  Once an earlier inbound call had burned through
     * MAX_SIP_RETRIES, every later dialler call started with the counter
     * already exhausted and got zero retries on its first SIP failure, until
     * the process restarted.  Called from each place a call attempt begins,
     * plus the teardown paths so no attempt can inherit another's allowance.
     */
    private fun resetSipRetries(reason: String) {
        if (sipCallRetries != 0) {
            Log.d(TAG, "Resetting SIP retry counter ($sipCallRetries → 0) for $reason")
        }
        sipCallRetries = 0
    }

    /** One thread for every deferred bridge action, instead of a fresh Thread
     *  per dial, per INVITE and per retry. */
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bridge-timers").apply { isDaemon = true }
        }

    /** Bumped every time the bridge returns to IDLE.
     *
     *  Timers used to check nothing but [bridgeState], which is shared by every
     *  call there has ever been.  A dial that failed at t+5s left its 45s
     *  timeout running; a different call answered at t+20s was still setting up
     *  when that timer fired, saw a non-IDLE state and tore the new call down
     *  as "GSM dial timeout".  Each timer now captures the generation it was
     *  scheduled in and does nothing if the bridge has moved on since. */
    @Volatile private var generation = 0L

    /** Run [action] after [delayMs], unless the bridge has moved on. */
    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        timers.schedule({
            if (generation != gen) return@schedule
            try { action() } catch (e: Exception) {
                Log.w(TAG, "Timer action failed: ${e.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,        // GSM answered, placing SIP call
        SIP_CALLING,         // SIP INVITE sent, waiting for answer
        SIP_RINGING,         // SIP ringing at Asterisk
        BRIDGED,             // Both sides active, audio flowing
        GSM_DIALING,         // Outbound: dialing GSM number
        TEARING_DOWN         // Hanging up
    }

    fun start() {
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        sipClient.listener = null
        GsmCallManager.listener = null
        timers.shutdownNow()
    }

    /**
     * The number this gateway answers on, sent as the SIP destination.
     *
     * The server maps an incoming number to an assistant the same way it does
     * a Fritz!Box DID, so it needs the MSISDN of our SIM here.  Addressing our
     * own SIP account instead makes the platform see account N calling account
     * N, match it as a local peer and ring us straight back — the INVITE comes
     * back, the orchestrator rejects it as busy, and the GSM leg is never
     * answered.
     *
     * [subId] identifies the SIM that actually took the call.  On a dual-SIM
     * gateway the default subscription is the wrong question to ask: a call
     * arriving on the non-default SIM would be announced as the default SIM's
     * DID and routed to the wrong assistant.  Passing the subscription in
     * mirrors what [com.callagent.gateway.service.GatewayService] already does
     * for SMS via ownNumberForSub().  When the caller cannot say which SIM it
     * was, the default subscription is the fallback, as before.
     *
     * Asks the platform first; many carriers do not publish the number on the
     * SIM, in which case the configured value is used.
     */
    private fun inboundSipDestination(subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID): String {
        simNumber(subId)?.let {
            val intl = toInternational(it)
            Log.i(TAG, "Own number from SIM (sub=$subId): $it → $intl")
            return intl
        }
        val configured = configuredOwnNumber(subId)
        if (configured.isNotEmpty()) {
            val intl = toInternational(configured)
            Log.i(TAG, "Own number from settings (sub=$subId): $configured → $intl")
            return intl
        }
        Log.w(
            TAG,
            "No own number known (sub=$subId: SIM silent and slot box empty) — " +
                "addressing our own extension, which loops back"
        )
        return sipClient.username
    }

    /**
     * The SIM's number for one subscription, falling back to the default.
     *
     * Thin enough to look like it does nothing, and kept only because this is
     * the name the call flow already reads as.  The body lives in OwnNumber
     * with the SMS path's copy of it, so the two can never pick different
     * numbers for the same SIM; that file also carries why the pre-Android 13
     * branch has to be scoped to [subId] instead of left on the default
     * subscription.
     */
    private fun simNumber(subId: Int): String? = OwnNumber.fromSim(context, subId)

    /**
     * The configured MSISDN for one subscription.
     *
     * Same slot-keyed lookup the SMS path uses: resolve the subscription to a
     * SIM slot and read `own_number_slot_<slot>`, which is the key Settings
     * writes per SIM.  This returns empty rather than falling back to another
     * slot's box — see OwnNumber for why a cross-slot fallback is the silent
     * wrong-DID bug, and note that inboundSipDestination() already reports an
     * empty result before it settles on the SIP account.
     */
    @SuppressLint("MissingPermission")
    private fun configuredOwnNumber(subId: Int): String {
        val prefs = gatewayPrefs(context)
        // No SIM to name, or a slot we cannot resolve: nothing to look up, so
        // say so downstream instead of guessing which SIM was meant.
        if (!isValidSubscription(subId)) return ""
        val slot = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.simSlotIndex
        }.getOrNull() ?: return ""
        return prefs.getString("own_number_slot_$slot", "")?.trim().orEmpty()
    }

    private fun isValidSubscription(subId: Int): Boolean =
        subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            SubscriptionManager.isValidSubscriptionId(subId)

    /**
     * Which SIM a Telecom [Call] belongs to, or the invalid id when unknown.
     *
     * This is the one step in the whole lookup that is genuinely build
     * specific, so it is resolved several ways before giving up.  AOSP
     * registers one PhoneAccount per subscription and names it after the
     * subscription id; other builds have named the same account after the
     * ICCID, after a compound string, or after nothing parseable at all.
     * Getting this wrong is not a lost nicety — it collapses the entire
     * multi-SIM path onto the default subscription, which is precisely the
     * silent wrong-DID failure every other part of this file exists to
     * prevent.
     *
     * Every branch logs which strategy won, because for a handset nobody has
     * in front of them the only way to learn how it names its accounts is to
     * read one line of its log.  The branches are ordered and additive: a
     * handle AOSP would have parsed is still parsed first, so an unfamiliar
     * device can end up no worse off than it is today, only ever better.
     *
     * Returns [SubscriptionManager.INVALID_SUBSCRIPTION_ID] when nothing
     * matched, which is the pre-existing "ask for the default subscription"
     * behaviour.
     */
    private fun subscriptionIdFor(call: Call): Int {
        val handle = try {
            call.details?.accountHandle
        } catch (e: Exception) {
            Log.w(TAG, "PhoneAccountHandle unavailable: ${e.message}")
            null
        }
        if (handle == null) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val handleId = handle.id?.trim().orEmpty()

        // 1. AOSP: the id *is* the subscription id, written as a decimal.
        handleId.toIntOrNull()?.takeIf { isValidSubscription(it) }?.let {
            // Logged on the success path deliberately: "no log line appeared"
            // is a much weaker claim to check than a line showing the raw
            // handle and the id it parsed to.
            Log.i(TAG, "Call account handle '$handleId' → subscription $it")
            return it
        }

        val active = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()

        // 2. A build that names the account after its ICCID.  A subscription
        //    id is a small int, so an 18-22 digit run can only be a card
        //    identifier and can never have been caught by step 1; comparing
        //    digits on both sides tolerates separators and the formatting
        //    platforms add around the number.
        val digits = handleId.filter { it.isDigit() }
        if (digits.length in 18..22) {
            active.firstOrNull { info ->
                info.iccId?.filter { c -> c.isDigit() } == digits
            }?.let {
                Log.i(
                    TAG,
                    "Call account handle '$handleId' matched ICCID → " +
                        "subscription ${it.subscriptionId} (slot ${it.simSlotIndex})"
                )
                return it.subscriptionId
            }
        } else if (digits.isNotEmpty()) {
            // 3. A compound id such as "2:voice".  Deliberately not attempted
            //    on ICCID-shaped ids, because every run of digits inside one
            //    would qualify here and step 2 is the branch that owns them.
            //    Membership of the live subscription list is required rather
            //    than merely a well-formed number, so a stray component of
            //    some other string cannot be mistaken for a SIM.
            active.firstOrNull { it.subscriptionId.toString() == digits }?.let {
                Log.i(
                    TAG,
                    "Call account handle '$handleId' → " +
                        "subscription ${it.subscriptionId} (compound id)"
                )
                return it.subscriptionId
            }
        }

        // 4. Whatever the account happens to be called, some builds publish
        //    its MSISDN as the PhoneAccount address.  Matching that against
        //    the numbers already resolved per subscription identifies the SIM
        //    without ever learning what the id meant — so this branch runs
        //    even when the id held no digits at all.  The length floor is
        //    there because a short component of an unrelated URI must not be
        //    allowed to match.
        val addressed = runCatching {
            context.getSystemService(TelecomManager::class.java)
                ?.getPhoneAccount(handle)?.address?.toString()
        }.getOrNull()?.filter { it.isDigit() }.orEmpty()
        if (addressed.length >= 8) {
            active.firstOrNull { info ->
                val subId = info.subscriptionId
                listOfNotNull(
                    OwnNumber.fromSim(context, subId),
                    gatewayPrefs(context)
                        .getString("own_number_slot_${info.simSlotIndex}", "")
                ).any { configured ->
                    val own = digitsOf(configured)
                    own == addressed || own.endsWith(addressed) || addressed.endsWith(own)
                }
            }?.let {
                Log.i(
                    TAG,
                    "Call account '$handleId' resolved by its address → " +
                        "subscription ${it.subscriptionId} (slot ${it.simSlotIndex})"
                )
                return it.subscriptionId
            }
        }

        // Deliberately not Log.d: this is the case where the whole multi-SIM
        // path is silently inert, and debug-level lines are the first thing a
        // default filter drops.  Saying how many SIMs were visible and what
        // the handle actually contained is what makes an untested handset
        // diagnosable from a single line.
        Log.w(
            TAG,
            "Call account '$handleId' matched no strategy " +
                "(${active.size} active SIMs) — using default SIM"
        )
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /**
     * The destination always goes out in international form.  What we have to
     * start from varies: the platform may hand back E.164, the settings field
     * may hold a national number ("015112345678"), and either may use a 00
     * prefix.  PhoneNumberUtils resolves the national case against the SIM's
     * country rather than us guessing a dialling code.
     *
     * The caller number in From is deliberately NOT put through this — that one
     * is passed on exactly as the carrier delivered it.
     */
    private fun toInternational(number: String): String {
        val trimmed = number.trim().filterNot { it == ' ' || it == '-' || it == '/' }
        if (trimmed.startsWith("+")) return trimmed
        if (trimmed.startsWith("00")) return "+" + trimmed.substring(2)
        val iso = try {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .simCountryIso?.uppercase()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "SIM country unavailable: ${e.message}")
            null
        }
        if (iso != null) {
            PhoneNumberUtils.formatNumberToE164(trimmed, iso)?.let { return it }
        }
        Log.w(TAG, "Cannot make '$trimmed' international (SIM country=$iso) — sending as is")
        return trimmed
    }

    /** The SIM's own number for the default subscription, when the carrier
     *  publishes it — many do not. */
    @SuppressLint("MissingPermission")
    private fun simNumber(): String? = simNumber(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    /** Cut the agent's audio to the caller, leaving the call itself up. */
    fun setAgentMuted(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            listener?.onError("No active call to mute")
            return
        }
        session.setAgentMuted(on)
    }

    /** Listen in on the active call through the phone's speaker.
     *
     *  Plays both sides mixed; the microphone stays muted, so nothing the room
     *  says reaches either the caller or the agent. */
    fun setMonitorEnabled(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            Log.w(TAG, "Monitor requested with no active call")
            listener?.onError("No active call to monitor")
            return
        }
        session.setMonitorEnabled(on)
    }

    /** Initiate an outgoing GSM call from the dialler, then bridge to SIP */
    fun initiateDiallerCall(number: String) {
        // MMI/USSD is not a call — it never produces a Telecom Connection, so
        // arming the bridge for it would strand us in GSM_DIALING until the
        // stale-state timeout.
        if (GsmCallManager.isMmiCode(number)) {
            Log.i(TAG, "MMI code $number — sending as USSD, not bridging")
            GsmCallManager.sendMmi(context, number) { result ->
                Log.i(TAG, "MMI result: $result")
                listener?.onStateChanged(bridgeState, "MMI: $result")
            }
            return
        }
        if (bridgeState != BridgeState.IDLE) {
            // Check for stale state: if bridge has been non-IDLE for too long
            // without reaching BRIDGED, force a reset.  This happens on cold boot
            // when InCallService isn't bound yet and call events never arrive.
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                Log.w(TAG, "Bridge stuck in $bridgeState for ${staleMs/1000}s — force resetting")
                forceReset("Stale state: $bridgeState for ${staleMs/1000}s")
            } else {
                Log.w(TAG, "Busy ($bridgeState) — cannot dial from dialler")
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        Log.i(TAG, "Dialler-initiated call to $number")
        // The dialler path reaches onCallTerminated without ever passing
        // through onIncomingGsmCall, so it has to reset the counter itself —
        // otherwise a previous inbound call that used up its retries leaves
        // this one with none.
        resetSipRetries("dialler call to $number")
        diallerInitiated = true
        lastStateChangeTime = System.currentTimeMillis()
        bridgeState = BridgeState.GSM_DIALING
        listener?.onStateChanged(bridgeState, "Dialing $number")
        GsmCallManager.makeCall(context, number)

        // Timeout: if GSM doesn't go active within 45s, tear down.
        // On cold boot, InCallService may not be bound, so call events
        // never arrive and the bridge gets stuck in GSM_DIALING.
        schedule(GSM_DIAL_TIMEOUT_MS) {
            if (bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no call events in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout")
            }
        }
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        if (bridgeState == BridgeState.IDLE) {
            Log.i(TAG, "SIP registered — ready for calls")
            listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
        } else {
            Log.i(TAG, "SIP registered — keeping active call state $bridgeState")
        }
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}, gsm_forward=${call.gsmForwardNumber}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call 486")
            call.reject(486, "Busy Here")
            sipClient.removeCall(call.callId)
            return
        }

        val gsmDest = outboundDestination(call)
        if (gsmDest != null) {
            // OUTBOUND flow: Asterisk wants us to dial a GSM number
            handleOutboundFlow(call, gsmDest)
        } else {
            // Nothing to dial: no X-GSM-Forward, and no number in the
            // Request-URI either.  Answering used to look harmless, but a 200
            // tells the server the call is up and leaves it bridged to
            // silence for as long as it cares to wait.  Say we cannot take it.
            Log.w(TAG, "SIP INVITE names no GSM destination " +
                "(uri=${call.originalInvite?.requestUri}) — rejecting 488")
            listener?.onError("INVITE with no GSM destination — rejected")
            call.reject(488, "Not Acceptable Here")
            sipClient.removeCall(call.callId)
        }
    }

    /**
     * The number an INVITE asks us to dial.
     *
     * X-GSM-Forward first, then the user part of the Request-URI — the same
     * order the SMS path resolves a recipient in, so that
     * Dial(SIP/<peer>/+49...) addresses a call the way it already addresses a
     * message, and a dialplan does not have to know that calls are the
     * exception.
     *
     * The Request-URI carries the account name whenever the server addresses
     * the peer rather than a number, and our own MSISDN when it routes the
     * SIM's DID back to us.  Dialling either would be a loop, so both are
     * ruled out before what is left is treated as a destination.
     */
    private fun outboundDestination(call: SipCall): String? {
        call.gsmForwardNumber?.trim()?.ifEmpty { null }?.let { return it }

        val invite = call.originalInvite ?: return null
        val user = invite.requestUri
            ?.let { invite.extractUser(it) }
            ?.trim()?.ifEmpty { null } ?: return null

        if (user.equals(sipClient.username, ignoreCase = true)) return null
        // Compare against every own number the gateway can answer on, not
        // just the default SIM's: on a dual-SIM gateway the server routes
        // each DID back to this same peer, and a Request-URI carrying the
        // non-default SIM's MSISDN would otherwise pass this check and be
        // dialled — ringing the gateway's own second SIM.
        if (isOwnNumber(user)) return null
        if (!looksDialable(user)) return null

        Log.i(TAG, "No X-GSM-Forward — destination taken from the Request-URI")
        return user
    }

    /**
     * Strict on purpose: a Request-URI user is a number only when that is all
     * it is.  An account name made of digits is already ruled out above; one
     * with a letter in it was never a number to begin with.
     */
    private fun looksDialable(user: String): Boolean =
        user.all { it.isDigit() || it in "+-.()" } &&
            digitsOf(user).length >= MIN_DIALABLE_DIGITS

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }

    /**
     * Is this Request-URI user one of our own MSISDNs?
     *
     * The loop guard in [outboundDestination].  It has to cover both SIMs
     * plus the configured fallbacks, because the server cannot tell this
     * gateway's two DIDs apart from any other number it might forward.
     */
    @SuppressLint("MissingPermission")
    private fun isOwnNumber(user: String): Boolean {
        val digits = digitsOf(user)
        if (digits.isEmpty()) return false

        // Every active subscription's published number.
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            for (info in sm?.activeSubscriptionInfoList ?: emptyList()) {
                val subId = info.subscriptionId
                // Prefer the number the platform reports for the
                // subscription, then the configured per-slot value — the
                // same two-step lookup inboundSipDestination() uses.  Both
                // halves now come from OwnNumber, so the guard recognises
                // exactly the numbers routing would have chosen, and the
                // pre-Android 13 branch here can no longer disagree with it.
                val candidates = mutableListOf<String?>(
                    OwnNumber.fromSim(context, subId),
                    gatewayPrefs(context).getString("own_number_slot_${info.simSlotIndex}", "")
                )
                if (candidates.any { it != null && digitsOf(it) == digits }) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "own-number enumeration failed: ${e.message}")
        }

        // Legacy single-key config, and the default SIM as a last resort.
        val prefs = gatewayPrefs(context)
        // listOfNotNull() has already dropped the nulls, so `it` is non-null
        // inside the lambda; an explicit `it != null` there reads as dead
        // logic and the compiler reports it as such.
        listOfNotNull(
            prefs.getString("own_number", null),
            runCatching { simNumber(subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID) }
                .getOrNull()
        ).forEach { if (digitsOf(it) == digits) return true }

        return false
    }

    /** The gateway's settings.  Called [gatewayPrefs] rather than
     *  getSharedPreferences() so a call site doesn't read as a call to the
     *  two-arg [Context] method this wraps. */
    private fun gatewayPrefs(context: Context) =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    /** Handles termination from both SipClient.Listener and SipCall.Listener */
    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        // If GSM is still ringing and we haven't exhausted retries, try again.
        // Transient network issues or socket races can kill the first SIP attempt.
        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            // Retry after a short delay to let any transient issue settle
            schedule(1000) {
                if (bridgeState != BridgeState.SIP_CALLING &&
                    bridgeState != BridgeState.SIP_RINGING) return@schedule
                activeGsmCall?.let { handleInboundFlow(it) }
                    ?: Log.e(TAG, "SIP retry: GSM call gone, aborting")
            }
            return
        }

        tearDown("SIP call ended")
    }

    // ── GsmCallManager.Listener ─────────────────────────

    /** Incoming GSM call — this is the INBOUND flow trigger */
    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call from $number")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting GSM call")
            GsmCallManager.rejectCall(call)
            return
        }

        resetSipRetries("incoming GSM call")
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        // Don't answer GSM yet — place SIP call to Asterisk first.
        // When the agent answers on SIP, we'll answer GSM so the caller
        // hears the agent immediately with no dead air.
        // The caller hears normal ringing in the meantime.
        Log.i(TAG, "GSM ringing from $number — placing SIP call first")
        Thread({ handleInboundFlow(call) }, "SIP-OutCall").start()
    }

    /** GSM call is now active (answered) */
    override fun onGsmCallActive(call: Call) {
        Log.i(TAG, "GSM call active")
        activeGsmCall = call

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                // INBOUND flow: GSM answered (triggered from onRtpReady).
                // SIP agent is ready — start RTP immediately so caller
                // hears the agent from the first moment.
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        startRtp(localPort, addr, port, pt)
                        // Guard: tearDown may have run while startRtp was blocking
                        // (AudioRecord retries take 30+ seconds on cold boot).
                        // Don't overwrite IDLE — that causes "Busy" on next call.
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established — zero dead air")
                    }, "RTP-Start").start()
                } else {
                    // Edge case: GSM answered but SIP RTP info not ready yet.
                    // This shouldn't happen in normal flow since we answer GSM
                    // from onRtpReady, but handle gracefully.
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                if (diallerInitiated) {
                    // DIALLER flow: GSM active → place SIP call to Asterisk (like inbound)
                    diallerInitiated = false
                    bridgeState = BridgeState.GSM_ANSWERED
                    listener?.onStateChanged(bridgeState, "GSM answered, calling Asterisk")
                    Thread({ handleInboundFlow(call) }, "SIP-OutCall").start()
                } else {
                    // SIP-initiated OUTBOUND flow: GSM destination answered → start audio bridge
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (outbound)")

                    // Answer the SIP call off the main thread
                    Thread({
                        activeSipCall?.let { sipCall ->
                            val rtpPort = allocateRtpPort()
                            sipCall.listener = this
                            sipCall.accept(rtpPort)

                            val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                            val port = sipCall.remoteRtpPort
                            val pt = sipCall.negotiatedPayloadType
                            if (port > 0) {
                                startRtp(rtpPort, addr, port, pt)
                            }
                        }
                        Log.i(TAG, "Outbound bridge established")
                    }, "SIP-Bridge").start()
                }
            }
            else -> {}
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        }
    }

    /**
     * The SIP status that says why a GSM leg never connected.
     *
     * A provider that cannot complete a call answers the INVITE with a final
     * response the dialplan can branch on — busy, declined, unobtainable.
     * We used to send BYE instead, which is not a valid way to end an INVITE
     * that was never answered: the server replies 481 and then waits out its
     * own timer, so a busy number, a declined call and a dead SIM all looked
     * alike and all looked like a timeout.
     */
    private fun sipStatusFor(cause: DisconnectCause?): Pair<Int, String> =
        when (cause?.code) {
            DisconnectCause.BUSY -> 486 to "Busy Here"
            DisconnectCause.REJECTED -> 603 to "Decline"
            DisconnectCause.RESTRICTED -> 403 to "Forbidden"
            DisconnectCause.MISSED -> 480 to "Temporarily Unavailable"
            DisconnectCause.CANCELED, DisconnectCause.LOCAL -> 487 to "Request Terminated"
            DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> 503 to "Service Unavailable"
            DisconnectCause.ERROR -> 500 to "Server Internal Error"
            // REMOTE covers both "they hung up" and the causes the platform
            // does not break out, so it stays the generic unobtainable.
            else -> 480 to "Temporarily Unavailable"
        }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        // Tear down if this is our tracked call, OR if we're in a call state
        // but activeGsmCall was never set (call failed before going ACTIVE)
        if (call == activeGsmCall ||
            (activeGsmCall == null && bridgeState != BridgeState.IDLE)) {
            tearDown("GSM call ended",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        }
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    // onCallTerminated is already implemented above (shared by SipClient.Listener and SipCall.Listener)

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: $remoteRtpAddr:$remoteRtpPort codec=$codecName bridgeState=$bridgeState")

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            // Check if GSM is already active (dialler-initiated calls).
            // For inbound calls GSM is still ringing — answer it and wait for
            // onGsmCallActive to start RTP.  For dialler calls GSM is already
            // active so onGsmCallActive won't fire again — start RTP now.
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                // INBOUND flow: SIP/agent answered — save RTP info and answer GSM.
                // When GSM goes active (onGsmCallActive), RTP starts immediately
                // so the caller hears the agent from the first moment.
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingLocalRtpPort = call.localRtpPort

                Log.i(TAG, "SIP answered (codec=$codecName) — answering GSM call now")
                activeGsmCall?.let { GsmCallManager.answerCall(it) }
                    ?: Log.e(TAG, "SIP answered but no active GSM call to answer!")
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            // Edge case: GSM was already answered (e.g. user picked up manually)
            // before SIP was ready.  Start RTP now.
            val localRtpPort = call.localRtpPort
            startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            // Neither a bridge setup nor an answerable state — there is
            // nothing established here, and the old "Inbound bridge
            // established — GSM was already active" line that used to follow
            // this logged a success this branch cannot produce.  It was
            // left over from a refactor and never reflected reality.
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState (expected SIP_CALLING or SIP_RINGING)")
            listener?.onError("RTP ready but bridge state wrong: $bridgeState")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(gsmCall: Call) {
        val callerNumber = gsmCall.details?.handle?.schemeSpecificPart ?: "unknown"
        // The SIM that is actually ringing decides the DID we announce, not
        // whichever SIM the platform happens to call default.
        val subId = subscriptionIdFor(gsmCall)
        Log.i(TAG, "Inbound flow: placing SIP call for GSM caller $callerNumber (sub=$subId)")

        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk for $callerNumber")

        val rtpPort = allocateRtpPort()
        val sipCall = sipClient.makeCall(
            targetExtension = inboundSipDestination(subId),
            localRtpPort = rtpPort,
            callerIdNumber = callerNumber,
            callerIdName = callerNumber
        )
        sipCall.listener = this
        activeSipCall = sipCall

        Log.i(TAG, "SIP INVITE sent to Asterisk (caller=$callerNumber, rtp=$rtpPort)")

        // Timeout: if Asterisk doesn't answer within 30s, tear down
        schedule(SIP_CALL_TIMEOUT_MS) {
            if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
                Log.w(TAG, "SIP call timeout — Asterisk didn't answer in ${SIP_CALL_TIMEOUT_MS / 1000}s")
                tearDown("Asterisk not answering")
            }
        }
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(sipCall: SipCall, gsmDestination: String) {
        Log.i(TAG, "Outbound flow: dialing GSM $gsmDestination")

        resetSipRetries("outbound call to $gsmDestination")
        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        listener?.onStateChanged(bridgeState, "Dialing $gsmDestination")

        // Send 180 Ringing to SIP caller while GSM dials
        sipCall.originalInvite?.let { invite ->
            val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
            sipClient.sendTo(ringing, sipCall.remoteContactAddress ?: sipClient.serverAddress)
        }

        // Dial via GSM SIM
        GsmCallManager.makeCall(context, gsmDestination)
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startRtp(localPort: Int, remoteAddr: String, remotePort: Int,
                         payloadType: Int = RtpPacket.PT_G722) {
        // Not @Synchronized, deliberately: tearDown() is, and it is reached
        // from the Telecom callback on the main thread, so sharing a monitor
        // with this method — which blocks for as long as AudioRecord takes to
        // come up — would hold the UI thread for seconds.  The generation
        // counter gives the same protection without the lock.
        val gen = generation
        // Re-assert RECORD_AUDIO appops SYNCHRONOUSLY before AudioRecord
        // creation.  Must complete before RtpSession.start() so AudioFlinger
        // sees "allow" when the record thread begins reading.  Running async
        // caused a race: AudioRecord started reading silence (denied) before
        // the appops command finished.  RtpSession also periodically re-asserts
        // appops in its timeoutLoop for screen-off resilience.
        forceAllowRecordAudio()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down before RTP setup — not starting")
            return
        }

        activeRtpSession?.stop()
        val session = RtpSession(context, localPort, remoteAddr, remotePort, payloadType)

        // Attach the negotiated SRTP keys, if this call has any.  Done before
        // start() so no packet is ever sent or accepted unprotected on a call
        // that agreed to be protected.
        activeSipCall?.let { call ->
            val local = call.localSrtpKeys
            val remote = call.remoteSrtpKeys
            if (local != null && remote != null) {
                session.srtpSend = SrtpContext(local)
                session.srtpRecv = SrtpContext(remote)
                Log.i(TAG, "SRTP enabled for this call (${local.suite.sdpName})")
            }
        }
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                Log.i(TAG, "RTP session stopped")
            }
            override fun onRtpError(error: String) {
                Log.e(TAG, "RTP error: $error")
                listener?.onError("RTP: $error")
            }
            override fun onRtpTimeout() {
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onRtpStats(stats)
            }
        }
        // Published before start() so a teardown arriving mid-setup can find
        // and stop it rather than leaving an orphaned session holding the
        // audio devices and the RTP socket.
        activeRtpSession = session
        session.start()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down during RTP start — stopping orphaned session")
            session.stop()
            if (activeRtpSession === session) activeRtpSession = null
        }
    }

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(reason: String, sipStatus: Pair<Int, String>? = null) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        bridgeState = BridgeState.TEARING_DOWN
        diallerInitiated = false
        Log.i(TAG, "Tearing down bridge: $reason")

        try {
            activeRtpSession?.stop()
            activeRtpSession = null

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) {
                        // An INVITE we accepted is ended with BYE; one we never
                        // answered has to be turned down with a final response
                        // instead, which is also the only place the server ever
                        // learns why the GSM leg did not come up.
                        if (it.direction == SipCall.Direction.INBOUND &&
                            it.state != SipCall.State.ANSWERED) {
                            val (code, phrase) = sipStatus ?: (480 to "Temporarily Unavailable")
                            it.reject(code, phrase)
                        } else {
                            it.hangup()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending SIP call: ${e.message}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            activeGsmCall?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  If the SIP
                    // call fails before GSM is answered, the ringing GSM call
                    // was left dangling (S4 Mini: "second call never answered").
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
            pendingRtpAddr = null
        } finally {
            bridgeState = BridgeState.IDLE
            generation++
            lastStateChangeTime = System.currentTimeMillis()
            // A completed call must not leave its retry count behind for the
            // next one — the counter is per-attempt state, not per-process.
            resetSipRetries("tearDown ($reason)")
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    /**
     * Pick a free even UDP port for RTP.
     *
     * The scan starts at a random offset rather than always at 30000.  The
     * port is probed by binding and closing, so there is a gap before
     * RtpSession binds it for real; starting from the same place every time
     * meant consecutive calls raced each other for the very same port, which
     * is the one way that gap reliably loses.
     */
    private fun allocateRtpPort(): Int {
        val span = (RTP_PORT_MAX - RTP_PORT_MIN) / 2
        val start = (Math.random() * span).toInt()
        for (i in 0 until span) {
            val port = RTP_PORT_MIN + ((start + i) % span) * 2
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                // Expected and uninteresting: this port is in use, or the
                // bind was refused.  Tried span/2 more before giving up, so
                // a line here would only ever repeat the same thing.
                continue
            }
        }
        throw RuntimeException("No free RTP port available")
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService revokes RECORD_AUDIO (app op 27) for
     * foreground services when the screen is off.  This must be
     * re-asserted before EVERY call, not just at startup.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = context.packageName
            // minSdk 31 >= API 29, so --uid is unconditional: the appops
            // CLI only gained that flag in Android 10 — before it, only the
            // package mode was addressable from the shell.
            val uidProbe = "--uid "
            // This sits directly between the call being answered and the first
            // frame of audio, so ask before acting: one `appops get` costs a
            // single root round-trip, where the grant sequence below is eight
            // commands and forks an app_process for each of pm/appops/cmd.
            // In the steady state the permission is already allowed and this
            // returns immediately.
            val probe = RootShell.execForOutput(
                "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
            )
            if (RootShell.recordAudioAllowed(probe)) {
                Log.i(TAG, "appops RECORD_AUDIO already allow — skipping grant")
                return
            }
            Log.w(TAG, "appops RECORD_AUDIO not allowed [$probe] — granting")
            val t0 = System.currentTimeMillis()
            // Capture all output (2>&1) for diagnosis.  appops get is LAST
            // so exit code reflects verification, not a stray killall.
            // minSdk 31 >= API 30, so the auto-revoke opt-out always applies.
            val autoRevoke =
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; "
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidProbe}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = RootShell.recordAudioAllowed(result)
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidProbe}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed: ${e.message}")
        }
    }

    /** Force-reset bridge to IDLE, clearing all state.  Used to recover from
     *  stale states where the normal tearDown path was never triggered. */
    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        try {
            activeRtpSession?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "forceReset: error stopping RTP session: ${e.message}")
        }
        activeRtpSession = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "forceReset: error ending SIP call: ${e.message}")
        }
        activeSipCall = null
        try {
            activeGsmCall?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "forceReset: error disconnecting GSM call: ${e.message}")
        }
        activeGsmCall = null
        pendingRtpAddr = null
        diallerInitiated = false
        bridgeState = BridgeState.IDLE
        generation++
        lastStateChangeTime = System.currentTimeMillis()
        resetSipRetries("forceReset ($reason)")
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        /** If bridge is non-IDLE for this long, consider it stale */
        private const val STALE_STATE_TIMEOUT_MS = 60_000L

        private const val RTP_PORT_MIN = 30000
        private const val RTP_PORT_MAX = 40000

        /** Shortest Request-URI user we will believe is a number to dial. */
        private const val MIN_DIALABLE_DIGITS = 3
    }
}

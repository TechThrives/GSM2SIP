package com.callagent.gateway.sip

import java.security.MessageDigest

/**
 * SIP Digest Authentication (RFC 2617).
 * Ported from the Python SIP implementation.
 */
object SipAuth {

    /** Parse WWW-Authenticate header parameters */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val authHeader = msg.header("www-authenticate") ?: msg.header("proxy-authenticate")
            ?: return null

        if (!authHeader.lowercase().startsWith("digest")) return null

        val params = mutableMapOf<String, String>()
        // Strip "Digest " prefix (case-insensitive)
        val digestIdx = authHeader.lowercase().indexOf("digest")
        val paramStr = if (digestIdx >= 0) authHeader.substring(digestIdx + 6).trim() else return null
        paramStr.split(",")
            .forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).trim().lowercase()
                    val value = pair.substring(eq + 1).trim().trim('"')
                    params[key] = value
                }
            }

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return AuthParams(realm, nonce, params["opaque"], params["qop"])
    }

    /**
     * The qop directive to answer with, or null for the RFC 2069 formula.
     *
     * Servers advertise a comma-separated list ("auth,auth-int").  Only
     * `auth` is implemented: `auth-int` hashes the entity body, which this
     * caller never passes in, so claiming it would produce a digest that is
     * wrong in a harder-to-diagnose way than not claiming it at all.  A
     * server that offers *only* auth-int gets the qop-less formula and will
     * keep rejecting — which is the same failure it produces today, no
     * worse.  A server that mandates `qop="auth"` (Kamailio, FreeSWITCH,
     * most hosted trunks) is the case this fixes.
     */
    private fun pickQop(qop: String?): String? =
        qop?.split(",")?.map { it.trim().trim('"') }?.firstOrNull { it.equals("auth", true) }

    /**
     * Answer a digest challenge, with or without qop.
     *
     * Two different formulas, and getting them mixed up is an instant
     * permanent 401/407 loop with nothing in the log saying why:
     *
     *   RFC 2069 (no qop):  MD5(HA1:nonce:HA2)
     *   RFC 2617 (qop=auth): MD5(HA1:nonce:nc:cnonce:qop:HA2)
     *
     * `parseChallenge` has always extracted `qop` and `buildAuthHeader` has
     * always ignored it, so every registrar that offers qop got the 2069
     * formula back.  That works against a bare chan_sip/Asterisk default and
     * fails against most everything else.
     */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String {
        val ha1 = md5("$username:${params.realm}:$password")
        val ha2 = md5("$method:$uri")
        val qop = pickQop(params.qop)

        val response: String
        val qopFields: String
        if (qop == null) {
            response = md5("$ha1:${params.nonce}:$ha2")
            qopFields = ""
        } else {
            // Nonce count, 8 hex digits, strictly increasing for the life of
            // the process.  RFC 2617 scopes it per-nonce; a global counter
            // never goes backwards, which is the property servers actually
            // check, and it needs no state that expires.
            val nc = "%08x".format(nonceCount.getAndIncrement())
            val cnonce = md5("${System.nanoTime()}:${java.util.UUID.randomUUID()}")
            response = md5("$ha1:${params.nonce}:$nc:$cnonce:$qop:$ha2")
            qopFields = "qop=\"$qop\", nc=$nc, cnonce=\"$cnonce\", "
        }

        return buildString {
            append("Authorization: Digest username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append(qopFields)
            append("response=\"$response\", ")
            append("algorithm=MD5")
            if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
            append("\r\n")
        }
    }

    /** Monotonic nc source; see buildAuthHeader. */
    private val nonceCount = java.util.concurrent.atomic.AtomicInteger(1)

    /** Build Authorization header for INVITE */
    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String = buildAuthHeader("INVITE", uri, username, password, params)

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?
    )
}

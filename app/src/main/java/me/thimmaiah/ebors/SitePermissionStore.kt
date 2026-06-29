/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * All rights reserved. This source file is part of Ebors.
 */
package me.thimmaiah.ebors

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.util.Locale

/**
 * Per-site, per-capability permission memory for camera, microphone,
 * and location.
 *
 * The browser's [android.webkit.WebChromeClient] gets a permission
 * request every time a page calls `getUserMedia` /
 * `geolocation.getCurrentPosition`. Without a memory the user is
 * re-prompted on every single call — maps re-asks for location on
 * every pan, a video site re-asks for the camera on every join. This
 * store lets the user pick "Allow always" / "Block" for a given
 * origin so the prompt only appears the first time.
 *
 * # Decision model
 *
 *  - [Decision.ALLOW]  — grant this origin this capability without
 *    prompting (the browser still has to hold the matching Android
 *    runtime permission; see MainActivity).
 *  - [Decision.BLOCK]  — deny this origin this capability without
 *    prompting.
 *  - [Decision.ASK]    — no remembered decision; show the prompt.
 *    This is the absence of a stored value, never written explicitly.
 *
 * # Keying
 *
 * Decisions are keyed by `(origin, kind)`. The origin is the web
 * origin — scheme + host + port — exactly as the platform reports it
 * (`PermissionRequest.getOrigin()` / the geolocation `origin`
 * argument), lower-cased for stability. We deliberately key on the
 * full origin rather than the registrable domain: `https://a.example`
 * and `https://b.example` are different trust surfaces and should be
 * remembered independently, matching Chrome's site-settings model.
 *
 * # Privacy
 *
 * Private (incognito) tabs never read or write this store — their
 * permission grants are session-only, matching the rest of the
 * private-mode carve-outs. MainActivity gates the calls on
 * `tab.isPrivate`.
 *
 * Backed by its own SharedPreferences file so "Clear browsing data"
 * and a future "reset site permissions" control can wipe it
 * independently of the main prefs.
 */
object SitePermissionStore {

    enum class Kind(val storageToken: String) {
        CAMERA("cam"),
        MICROPHONE("mic"),
        LOCATION("loc"),
    }

    enum class Decision {
        ALLOW,
        BLOCK,
        ASK,
    }

    private const val PREFS_NAME = "ebors_site_permissions"
    private const val VALUE_ALLOW = "allow"
    private const val VALUE_BLOCK = "block"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reduce a raw origin/URL string to the stable `scheme://host[:port]`
     * key we store under. Returns null when the input has no usable
     * host (e.g. `about:blank`, a malformed string) — callers treat a
     * null key as "can't remember, always ask".
     */
    private fun originKey(rawOrigin: String?): String? {
        if (rawOrigin.isNullOrBlank()) return null
        val uri = try {
            Uri.parse(rawOrigin.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host.isBlank()) return null
        val port = uri.port
        return if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
    }

    private fun storageKey(originKey: String, kind: Kind): String =
        "${originKey}|${kind.storageToken}"

    /**
     * The remembered decision for [rawOrigin] + [kind], or
     * [Decision.ASK] when nothing is stored / the origin is unusable.
     */
    fun decisionFor(rawOrigin: String?, kind: Kind): Decision {
        val key = originKey(rawOrigin) ?: return Decision.ASK
        val stored = prefs()?.getString(storageKey(key, kind), null) ?: return Decision.ASK
        return when (stored) {
            VALUE_ALLOW -> Decision.ALLOW
            VALUE_BLOCK -> Decision.BLOCK
            else -> Decision.ASK
        }
    }

    /** Persist "always allow" for this origin + capability. */
    fun remember(rawOrigin: String?, kind: Kind, allow: Boolean) {
        val key = originKey(rawOrigin) ?: return
        prefs()?.edit {
            putString(storageKey(key, kind), if (allow) VALUE_ALLOW else VALUE_BLOCK)
        }
    }

    /** Forget every remembered site permission. Wired into
     *  "Clear browsing data". */
    fun clearAll() {
        prefs()?.edit { clear() }
    }
}

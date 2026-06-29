/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.thimmaiah.ebors

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads, verifies, and installs an updated ad/tracker block list.
 *
 * # Pipeline
 *
 *  1. **Gate.** No-op unless [BlocklistUpdateConfig.isConfigured] (an
 *     operator wired up a URL + signing key) and — for the automatic
 *     startup check — enough time has passed since the last attempt.
 *  2. **Download.** Fetch the signed `blocklist.json` and its detached
 *     `blocklist.json.sig`, each capped at [MAX_BLOCKLIST_BYTES] so a
 *     hostile or broken endpoint can't exhaust memory.
 *  3. **Verify.** [BlocklistSignature.verifyBase64] checks the signature
 *     against the bundled public key. A failure here aborts — there is
 *     no unverified-adopt path.
 *  4. **Validate.** Parse the JSON, require the expected
 *     `schemaVersion`, require a numeric `version`, require the data
 *     objects to be present. Reject anything malformed.
 *  5. **Compare.** Adopt only when the downloaded `version` is strictly
 *     greater than [BrowserBlocker.currentBlocklistVersion].
 *  6. **Install.** Write the *verified, original* payload bytes
 *     atomically to `filesDir/blocklist-cache.json`, then call
 *     [BrowserBlocker.reload] so the new rules take effect without an
 *     app restart.
 *
 * Every failure mode is a [Result] value, never an exception escaping
 * to the caller — a broken update must never break the browser.
 *
 * # Threading
 *
 * [checkForUpdate] returns immediately; the work runs on a private
 * single-thread executor and the [Result] is posted back on the main
 * thread. Callers that don't care about the result (the silent startup
 * check) can pass a no-op callback.
 */
object BlocklistUpdater {

    /** Outcome of a single [checkForUpdate] run. */
    sealed interface Result {
        /** Build has no update source / key wired up — see [BlocklistUpdateConfig]. */
        data object NotConfigured : Result

        /** Automatic check skipped because the last attempt was too recent. */
        data object Skipped : Result

        /** Downloaded list's version was not newer than what's installed. */
        data object UpToDate : Result

        /** A newer list was verified and installed. */
        data class Updated(val newVersion: Int) : Result

        /** Something went wrong; [reason] is a short, loggable description. */
        data class Failed(val reason: String) : Result
    }

    private const val LOG_TAG = "BlocklistUpdater"

    /** Hard ceiling on either download. The real list is ~15 KB; this is
     *  generous headroom while still bounding a hostile response. */
    private const val MAX_BLOCKLIST_BYTES = 5L * 1024L * 1024L

    /** Automatic startup checks are rate-limited to once per this window.
     *  A manual "check now" from Settings passes `force = true` and
     *  bypasses it. */
    private val MIN_AUTO_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "blocklist-updater").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // followSslRedirects=false rejects an HTTPS → HTTP downgrade.
            // The signature check on the downloaded bytes already makes
            // content tampering impossible — but a misconfigured or
            // hostile CDN that bounces us to http:// would leak
            // fact-of-fetch (and the requesting IP) in the clear.
            // Refuse the downgrade so the request fails cleanly instead.
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Kick off a block-list update check on a background thread.
     *
     * @param force when true, bypass the [MIN_AUTO_CHECK_INTERVAL_MS]
     *   rate limit (used by the Settings "check now" button). The
     *   automatic startup check passes false.
     * @param onResult invoked on the **main thread** with the outcome.
     */
    fun checkForUpdate(
        context: Context,
        force: Boolean,
        onResult: (Result) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                runCheck(appContext, force)
            } catch (error: Exception) {
                // Defensive: runCheck is written to return Result.Failed
                // rather than throw, but a bug there must still not crash
                // the updater thread.
                Log.w(LOG_TAG, "Unexpected error during block-list update", error)
                Result.Failed("unexpected error")
            }
            mainHandler.post { onResult(result) }
        }
    }

    private fun runCheck(appContext: Context, force: Boolean): Result {
        if (!BlocklistUpdateConfig.isConfigured()) {
            return Result.NotConfigured
        }

        val prefs = BrowserPreferences.from(appContext)
        if (!force) {
            val sinceLast = System.currentTimeMillis() - prefs.blocklistLastCheckedAt
            if (sinceLast in 0 until MIN_AUTO_CHECK_INTERVAL_MS) {
                return Result.Skipped
            }
        }
        // Record the attempt up front so a hang / crash mid-check still
        // counts against the rate limit (no tight retry loop on failure).
        prefs.blocklistLastCheckedAt = System.currentTimeMillis()

        val publicKey = BlocklistSignature.parsePublicKey(BlocklistUpdateConfig.PUBLIC_KEY_BASE64)
            ?: return Result.Failed("configured public key is malformed")

        val payloadBytes = download(BlocklistUpdateConfig.UPDATE_URL)
            ?: return Result.Failed("could not download block list")
        val signatureUrl = BlocklistUrls.signatureUrlFor(BlocklistUpdateConfig.UPDATE_URL)
            ?: return Result.Failed("misconfigured update URL")
        val signatureText = download(signatureUrl)
            ?.toString(Charsets.UTF_8)
            ?: return Result.Failed("could not download signature")

        if (!BlocklistSignature.verifyBase64(payloadBytes, signatureText, publicKey)) {
            // This is the security-critical branch: a mismatch means the
            // file isn't what the list's publisher signed. Refuse it.
            return Result.Failed("signature verification failed")
        }

        // Signature is good — now make sure the *content* is something
        // this build can actually use.
        val root = try {
            JSONObject(payloadBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return Result.Failed("downloaded list is not valid JSON")
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != BrowserBlocker.SUPPORTED_SCHEMA_VERSION) {
            return Result.Failed("unsupported schemaVersion $schemaVersion")
        }
        val remoteVersion = root.optInt("version", -1)
        if (remoteVersion < 0) {
            return Result.Failed("downloaded list has no version field")
        }
        // Shape check — the loader expects these three. Catch a
        // structurally-wrong (but validly-signed) file here rather than
        // letting BrowserBlocker.parseBlockList throw later.
        if (!root.has("adAndTrackerDomains") ||
            !root.has("essentialAllowList") ||
            !root.has("adPathSignals")
        ) {
            return Result.Failed("downloaded list is missing required sections")
        }

        if (remoteVersion <= BrowserBlocker.currentBlocklistVersion) {
            return Result.UpToDate
        }

        // Install: atomic write of the *verified original bytes* (not a
        // re-serialised JSONObject — re-serialising could subtly change
        // the file and we want what we verified).
        val cacheFile = File(appContext.filesDir, BrowserBlocker.BLOCKLIST_CACHE_NAME)
        val tmpFile = File(appContext.filesDir, "${BrowserBlocker.BLOCKLIST_CACHE_NAME}.tmp")
        try {
            tmpFile.writeBytes(payloadBytes)
            if (!tmpFile.renameTo(cacheFile)) {
                // Cross-filesystem rename can fail; fall back to copy.
                tmpFile.copyTo(cacheFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (error: Exception) {
            tmpFile.delete()
            Log.w(LOG_TAG, "Failed to write block-list cache", error)
            return Result.Failed("could not save downloaded list")
        }

        BrowserBlocker.reload(appContext)
        Log.i(LOG_TAG, "Block list updated to version $remoteVersion")
        return Result.Updated(remoteVersion)
    }

    /**
     * GET [url] and return the body bytes, or null on any
     * non-2xx / network error / oversize response. Reads through a
     * capped buffer so a body that lies about (or omits) its
     * Content-Length still can't exceed [MAX_BLOCKLIST_BYTES].
     */
    private fun download(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).header("Accept", "*/*").build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "Download of $url failed: HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                // Early reject if the server is honest about an oversize body.
                if (body.contentLength() > MAX_BLOCKLIST_BYTES) {
                    Log.w(LOG_TAG, "Download of $url rejected: declared size too large")
                    return null
                }
                readCapped(body.byteStream(), MAX_BLOCKLIST_BYTES)
            }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Download of $url errored", error)
            null
        }
    }

    /** Read [stream] fully into a byte array, or null if it exceeds [maxBytes]. */
    private fun readCapped(stream: java.io.InputStream, maxBytes: Long): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

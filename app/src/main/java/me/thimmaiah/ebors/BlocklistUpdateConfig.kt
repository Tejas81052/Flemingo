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

/**
 * Operator (build-time) configuration for the network block-list updater.
 *
 * This is intentionally **not** in [BrowserPreferences] — it's not a user
 * setting, it's a decision made by whoever builds and ships the app:
 * "where do updated block lists come from, and which key signs them".
 *
 * # Default state: inert
 *
 * Both fields are empty by default, so [isConfigured] returns false and
 * [BlocklistUpdater] is a complete no-op (`Result.NotConfigured`). The
 * app ships and runs perfectly fine on just the bundled
 * `assets/blocklist.json`. Nothing about the network path activates
 * until an operator fills both fields in.
 *
 * # To enable network updates
 *
 *  1. Generate an RSA keypair and host a signed list — see the operator
 *     workflow in [BlocklistSignature]'s KDoc.
 *  2. Set [UPDATE_URL] to the URL of the hosted `blocklist.json`. The
 *     detached signature must live at the same URL with a `.sig` suffix
 *     (`blocklist.json` → `blocklist.json.sig`).
 *  3. Paste the base64 body of the public key into [PUBLIC_KEY_BASE64].
 *  4. Bump the `version` field inside the hosted JSON every time its
 *     contents change — the updater only adopts strictly-newer versions.
 *
 * Keep [UPDATE_URL] `https://`. The signature already guarantees
 * integrity + authenticity, but TLS keeps the *fact that the user is
 * fetching a block list* (and from where) off the wire.
 */
object BlocklistUpdateConfig {

    /**
     * HTTPS URL of the hosted, signed `blocklist.json`. The detached
     * signature is expected at this URL + `.sig`.
     *
     * Empty by default → updater inert. Example once configured:
     * `https://lists.example.org/effbrowser/blocklist.json`
     */
    const val UPDATE_URL: String = ""

    /**
     * Base64-encoded X.509 SubjectPublicKeyInfo (the body of a PEM
     * `PUBLIC KEY` block) of the RSA key that signs the hosted list.
     * PEM armor and whitespace are tolerated by
     * [BlocklistSignature.parsePublicKey], so pasting the whole PEM
     * block also works.
     *
     * Empty by default → updater inert.
     */
    const val PUBLIC_KEY_BASE64: String = ""

    /**
     * True only when both the URL and the signing key are present.
     * [BlocklistUpdater] checks this first and short-circuits to
     * `NotConfigured` otherwise — there is no "download but don't
     * verify" path by design.
     */
    fun isConfigured(): Boolean =
        UPDATE_URL.isNotBlank() && PUBLIC_KEY_BASE64.isNotBlank()
}

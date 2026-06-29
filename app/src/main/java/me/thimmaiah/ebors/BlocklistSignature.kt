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

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Detached-signature verification for the network-fetched block list.
 *
 * # Why a signature at all
 *
 * The block list is downloaded over the network and then *trusted* — its
 * contents decide which requests the browser drops. A network attacker
 * who could swap the file out can't get remote code execution, but they
 * *can* selectively un-block their own trackers or over-block to break
 * sites. Requiring a signature from a key the app ships means only the
 * list's publisher can produce a list the app will adopt.
 *
 * # Scheme
 *
 * RSA + SHA-256 (`SHA256withRSA`). Chosen over Ed25519 because
 * `java.security`'s Ed25519 support is API 33+ and this app's `minSdk`
 * is 29; `SHA256withRSA` works on every supported level and in plain
 * JVM unit tests. The signature is *detached*: the publisher hosts
 * `blocklist.json` and `blocklist.json.sig` (the raw signature bytes,
 * base64-encoded) side by side. Detached-over-raw-bytes sidesteps every
 * JSON-canonicalisation headache — the bytes signed are exactly the
 * bytes downloaded.
 *
 * # Operator workflow (documented here so it's next to the verifier)
 *
 *   # one-time: generate a keypair
 *   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
 *       -out blocklist-private.pem
 *   openssl rsa -pubout -in blocklist-private.pem -out blocklist-public.pem
 *   # take the base64 body of blocklist-public.pem (between the PEM
 *   # header/footer lines) and paste it into BlocklistUpdateConfig.
 *
 *   # each release of the list:
 *   openssl dgst -sha256 -sign blocklist-private.pem \
 *       -out blocklist.json.sig.bin blocklist.json
 *   base64 -w0 blocklist.json.sig.bin > blocklist.json.sig
 *   # host blocklist.json and blocklist.json.sig together.
 *
 * This object is pure `java.security` — no Android types — so it is
 * fully exercised by [BlocklistSignatureTest] in plain JVM unit tests.
 */
object BlocklistSignature {

    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val KEY_ALGORITHM = "RSA"

    /**
     * Parse a base64-encoded X.509 `SubjectPublicKeyInfo` (the body of a
     * PEM `PUBLIC KEY` block) into a [PublicKey]. Returns null on any
     * malformed input rather than throwing — the caller treats null as
     * "not configured / can't verify" and refuses the update.
     */
    fun parsePublicKey(base64Spki: String): PublicKey? {
        val trimmed = base64Spki.trim()
        if (trimmed.isEmpty()) return null
        // PKCS#1 keys (`-----BEGIN RSA PUBLIC KEY-----`, the output of
        // `openssl genrsa`-style flows) decode to a different DER shape
        // than the X.509 SubjectPublicKeyInfo this verifier expects, so
        // X509EncodedKeySpec would throw and we'd return null with a
        // confusing "configured public key is malformed" message. Detect
        // and reject early so the operator gets a hint to re-run with
        // `openssl rsa -pubout` (which emits SPKI).
        if (trimmed.contains("BEGIN RSA PUBLIC KEY")) {
            return null
        }
        return try {
            // Tolerate keys pasted with PEM armor or internal whitespace.
            val cleaned = trimmed
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(cleaned)
            KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(der))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True iff [signatureBytes] is a valid `SHA256withRSA` signature of
     * [payloadBytes] under [publicKey]. Any exception (malformed
     * signature, wrong key type, …) is treated as "not valid" — a
     * verification failure must never throw into the update path.
     */
    fun verify(payloadBytes: ByteArray, signatureBytes: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(payloadBytes)
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Convenience: decode a base64 signature string and verify it in one
     * call. Returns false if the base64 is malformed.
     */
    fun verifyBase64(payloadBytes: ByteArray, signatureBase64: String, publicKey: PublicKey): Boolean {
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64.trim().replace("\\s".toRegex(), ""))
        } catch (_: Exception) {
            return false
        }
        return verify(payloadBytes, signatureBytes, publicKey)
    }
}

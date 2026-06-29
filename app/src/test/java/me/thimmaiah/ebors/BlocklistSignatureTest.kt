/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package me.thimmaiah.ebors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Exercises [BlocklistSignature] — the security-critical gate on the
 * network block-list updater. Everything here is pure `java.security`,
 * which runs unchanged on the JVM, so the *real* verification path is
 * tested (not a mock).
 *
 * The keypair is generated once for the whole class: RSA keygen is slow
 * (~hundreds of ms) and none of the tests mutate the pair.
 */
class BlocklistSignatureTest {

    companion object {
        private lateinit var keyPair: KeyPair
        private lateinit var otherKeyPair: KeyPair

        @BeforeClass
        @JvmStatic
        fun generateKeys() {
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            keyPair = generator.generateKeyPair()
            otherKeyPair = generator.generateKeyPair()
        }

        /** Sign [payload] with [keyPair]'s private key — i.e. produce
         *  exactly what the operator's `openssl dgst -sign` step would. */
        private fun sign(payload: ByteArray): ByteArray {
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(keyPair.private)
            signer.update(payload)
            return signer.sign()
        }

        private fun publicKeyBase64(pair: KeyPair): String =
            Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    // ---------- parsePublicKey -------------------------------------------

    @Test fun `parsePublicKey accepts a bare base64 SPKI key`() {
        val parsed = BlocklistSignature.parsePublicKey(publicKeyBase64(keyPair))
        assertNotNull(parsed)
        assertTrue(parsed!!.encoded.contentEquals(keyPair.public.encoded))
    }

    @Test fun `parsePublicKey tolerates PEM armor and whitespace`() {
        val pem = buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(publicKeyBase64(keyPair).chunked(64).joinToString("\n"))
            append("\n-----END PUBLIC KEY-----\n")
        }
        assertNotNull(BlocklistSignature.parsePublicKey(pem))
    }

    @Test fun `parsePublicKey returns null for blank or garbage input`() {
        assertNull(BlocklistSignature.parsePublicKey(""))
        assertNull(BlocklistSignature.parsePublicKey("   "))
        assertNull(BlocklistSignature.parsePublicKey("not base64 at all !!!"))
        // Valid base64, but not a valid X.509 key.
        assertNull(BlocklistSignature.parsePublicKey(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
    }

    // ---------- verify (happy path) --------------------------------------

    @Test fun `verify accepts a genuine signature`() {
        val payload = """{"version":2,"data":"…"}""".toByteArray()
        val signature = sign(payload)
        assertTrue(BlocklistSignature.verify(payload, signature, keyPair.public))
    }

    @Test fun `verifyBase64 accepts a genuine base64 signature`() {
        val payload = "blocklist contents".toByteArray()
        val signatureBase64 = Base64.getEncoder().encodeToString(sign(payload))
        assertTrue(BlocklistSignature.verifyBase64(payload, signatureBase64, keyPair.public))
    }

    @Test fun `verifyBase64 tolerates whitespace and newlines in the signature`() {
        val payload = "blocklist contents".toByteArray()
        val wrapped = Base64.getEncoder().encodeToString(sign(payload)).chunked(40).joinToString("\n")
        assertTrue(BlocklistSignature.verifyBase64(payload, "  $wrapped  ", keyPair.public))
    }

    // ---------- verify (the cases that MUST be rejected) -----------------

    @Test fun `verify rejects a tampered payload`() {
        val payload = """{"version":2}""".toByteArray()
        val signature = sign(payload)
        // Flip one byte of the payload — the classic MITM "swap the list".
        val tampered = payload.copyOf().also { it[2] = (it[2] + 1).toByte() }
        assertFalse(BlocklistSignature.verify(tampered, signature, keyPair.public))
    }

    @Test fun `verify rejects a signature made with a different key`() {
        val payload = """{"version":2}""".toByteArray()
        // Signed by otherKeyPair, but verified against keyPair's public key.
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(otherKeyPair.private)
        signer.update(payload)
        val foreignSignature = signer.sign()
        assertFalse(BlocklistSignature.verify(payload, foreignSignature, keyPair.public))
    }

    @Test fun `verify rejects garbage signature bytes`() {
        val payload = """{"version":2}""".toByteArray()
        assertFalse(BlocklistSignature.verify(payload, byteArrayOf(0, 1, 2, 3), keyPair.public))
        assertFalse(BlocklistSignature.verify(payload, ByteArray(0), keyPair.public))
    }

    @Test fun `verifyBase64 rejects malformed base64`() {
        val payload = "x".toByteArray()
        assertFalse(BlocklistSignature.verifyBase64(payload, "%%%not base64%%%", keyPair.public))
    }

    @Test fun `verify rejects an empty payload signed as non-empty`() {
        val signature = sign("real payload".toByteArray())
        assertFalse(BlocklistSignature.verify(ByteArray(0), signature, keyPair.public))
    }
}

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

import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test for the QR/barcode scanner options.
 *
 * `MainActivity.launchQrScanner()` cannot be invoked directly in a JVM unit test
 * (it touches Activity/launcher/resources), so this test mirrors the exact
 * [ScanOptions] builder calls from that method and asserts the resulting options
 * are configured for a fixed-portrait scan.
 *
 * Portrait is achieved NOT via `setOrientationLocked(true)` — that only re-locks
 * to whatever orientation the library's (landscape) CaptureActivity starts in —
 * but by routing the scan through [PortraitCaptureActivity], which is declared
 * `android:screenOrientation="portrait"` in the manifest, with
 * `setOrientationLocked(false)` so the library never overrides that.
 *
 * This is JVM-safe because [ScanOptions] is a plain builder: the orientation flag
 * lives in the `moreExtras` map (exposed via [ScanOptions.getMoreExtras]) under the
 * key [Intents.Scan.ORIENTATION_LOCKED], and the capture activity is exposed via
 * [ScanOptions.getCaptureActivity]. No Android runtime is required to read them back.
 *
 * The prompt is a literal here (instead of `getString(R.string.qr_scan_prompt)`) to
 * avoid needing the resource system; the prompt value is irrelevant to these assertions.
 *
 * Requirement: 5.1, 5.2 — the scanner SHALL be configured for a stable portrait capture.
 */
class ScannerOptionsTest {

    /** Mirror of the ScanOptions construction in `MainActivity.launchQrScanner()`. */
    private fun buildScannerOptions(): ScanOptions = ScanOptions().apply {
        setDesiredBarcodeFormats(
            ScanOptions.QR_CODE,
            ScanOptions.DATA_MATRIX,
            ScanOptions.PDF_417,
            ScanOptions.EAN_13,
            ScanOptions.UPC_A,
            ScanOptions.CODE_128,
        )
        setPrompt("Scan a QR or barcode")
        setBeepEnabled(false)
        setCaptureActivity(PortraitCaptureActivity::class.java)
        setOrientationLocked(false)
    }

    @Test
    fun scannerOptions_usePortraitCaptureActivity() {
        val options = buildScannerOptions()

        assertEquals(
            "launchQrScanner() must route the scan through PortraitCaptureActivity " +
                "so the capture UI is fixed portrait; captureActivity was ${options.captureActivity}",
            PortraitCaptureActivity::class.java,
            options.captureActivity,
        )
    }

    @Test
    fun scannerOptions_orientationNotLibraryLocked() {
        val options = buildScannerOptions()

        // orientationLocked must be false: the portrait manifest entry on
        // PortraitCaptureActivity wins, and the library's CaptureManager must
        // NOT re-lock to the device's current rotation.
        val orientationLocked = options.moreExtras[Intents.Scan.ORIENTATION_LOCKED]

        assertEquals(
            "orientationLocked must be false so the manifest portrait orientation " +
                "is not overridden; moreExtras was ${options.moreExtras}",
            false,
            orientationLocked,
        )
    }
}

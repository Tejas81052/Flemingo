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

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * A capture activity that scans in a fixed portrait orientation.
 *
 * The ZXing library's bundled [CaptureActivity] is declared `sensorLandscape`
 * in the library manifest, so it always opens in landscape — and
 * `ScanOptions.setOrientationLocked(true)` only re-locks to whatever
 * orientation the activity *started* in, which can't undo that. To get a
 * stable portrait scanner we point [com.journeyapps.barcodescanner.ScanOptions.setCaptureActivity]
 * at this subclass, which is declared `android:screenOrientation="portrait"`
 * in the app manifest. Paired with `setOrientationLocked(false)` so the
 * library never overrides the manifest orientation.
 *
 * No behavior is added — it exists solely to carry the portrait manifest entry.
 */
class PortraitCaptureActivity : CaptureActivity()

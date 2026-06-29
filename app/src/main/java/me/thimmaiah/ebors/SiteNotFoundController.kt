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

import android.graphics.drawable.Animatable
import android.net.Uri
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Drives the animated "address not found" screen
 * ([R.layout.activity_site_not_found], included into MainActivity as
 * `@id/site_not_found_overlay`). It owns the hero animation only — the
 * overlay's visibility is managed by MainActivity.
 *
 * Distinct from [NoInternetController]: this is shown when the device IS
 * online but the host doesn't resolve (WebViewClient ERROR_HOST_LOOKUP).
 *
 * The hero AnimatedVectorDrawable doesn't auto-pause, so we stop it when
 * the screen is hidden / the app is backgrounded.
 */
class SiteNotFoundController(private val activity: AppCompatActivity) {

    private val hero: ImageView = activity.findViewById(R.id.site_not_found_hero)
    private val body: TextView = activity.findViewById(R.id.site_not_found_body)
    private val backBtn: Button = activity.findViewById(R.id.site_not_found_back)
    private val retryBtn: Button = activity.findViewById(R.id.site_not_found_retry)

    /**
     * Bind the body copy and handlers, then kick off the animation.
     *
     * @param failedUrl the URL that failed to resolve
     * @param onBack    invoked to navigate back / dismiss
     * @param onRetry   invoked to reload failedUrl
     */
    fun show(failedUrl: String, onBack: () -> Unit, onRetry: () -> Unit) {
        val host = Uri.parse(failedUrl).host ?: failedUrl
        body.text = activity.getString(R.string.site_not_found_body, host)

        backBtn.setOnClickListener { onBack() }
        retryBtn.setOnClickListener { onRetry() }

        startAnimations()
    }

    /** Restart the hero animation — also used when returning from the
     *  background while the screen is still showing. Idempotent. */
    fun startAnimations() {
        (hero.drawable as? Animatable)?.start()
    }

    /** Halt the hero animation to save CPU when the screen isn't visible. */
    fun stopAnimations() {
        (hero.drawable as? Animatable)?.stop()
    }
}

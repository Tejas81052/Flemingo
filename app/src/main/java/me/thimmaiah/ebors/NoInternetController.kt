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

import android.animation.ValueAnimator
import android.graphics.drawable.Animatable
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * Drives the animated offline screen ([R.layout.activity_no_internet],
 * included into MainActivity as `@id/no_internet_overlay`). It owns the
 * animations only — the overlay's visibility is managed by MainActivity.
 *
 *  1. Starts/stops the hero AnimatedVectorDrawable (it doesn't auto-pause,
 *     so we stop it when the screen is hidden / app backgrounded).
 *  2. Spins the refresh icon in the stamp button with a ValueAnimator —
 *     3.4s, flat-then-sweep curve matching the prototype's keyframes.
 */
class NoInternetController(activity: AppCompatActivity) {

    private val hero: ImageView = activity.findViewById(R.id.no_internet_hero)
    private val refreshIcon: ImageView = activity.findViewById(R.id.no_internet_refresh_icon)
    private val tryAgain: Button = activity.findViewById(R.id.no_internet_try_again)

    private var iconSpin: ValueAnimator? = null

    /** Bind the retry handler and kick off the animations. */
    fun show(onRetry: () -> Unit) {
        tryAgain.setOnClickListener { onRetry() }
        startAnimations()
    }

    /** Restart animations — also used when returning from the background
     *  while the screen is still showing. Idempotent. */
    fun startAnimations() {
        (hero.drawable as? Animatable)?.start()
        if (iconSpin == null) {
            // The CSS keyframes hold flat for 60% of the cycle then sweep
            // -360° with ease-in-out; PathInterpolator(0.6,0,0.45,1) is
            // flat-then-curved to match.
            iconSpin = ValueAnimator.ofFloat(0f, -360f).apply {
                duration = 3400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = PathInterpolator(0.6f, 0f, 0.45f, 1f)
                addUpdateListener { refreshIcon.rotation = it.animatedValue as Float }
                start()
            }
        }
    }

    /** Halt animations to save CPU when the screen isn't visible. */
    fun stopAnimations() {
        (hero.drawable as? Animatable)?.stop()
        iconSpin?.cancel()
        iconSpin = null
    }
}

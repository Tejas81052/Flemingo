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

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Owns the HTML5 fullscreen-video overlay: the custom view a page hands us via
 * [WebChromeClient.onShowCustomView], the matching hide callback, and the
 * orientation we restore on exit. Extracted from MainActivity so the activity
 * no longer carries the fullscreen window plumbing — the only state here is the
 * three fields that move and unmove as one.
 */
class FullscreenVideoController(private val activity: Activity) {

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    val isInFullscreen: Boolean get() = fullscreenView != null

    fun enter(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (fullscreenView != null) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        savedOrientation = activity.requestedOrientation

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val decor = activity.window.decorView as ViewGroup
        decor.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        WindowCompat.getInsetsController(activity.window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun exit() {
        val view = fullscreenView ?: return
        val callback = fullscreenCallback
        val orientation = savedOrientation
        fullscreenView = null
        fullscreenCallback = null
        savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            (activity.window.decorView as ViewGroup).removeView(view)
        } catch (_: Exception) {
        }
        try {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
        }
        callback?.onCustomViewHidden()
        activity.requestedOrientation = orientation
    }
}

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
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal AppCompat host that inflates the site-not-found layout, so a
 * [SiteNotFoundController] can be constructed against a real view tree
 * without dragging in MainActivity. Declared in the androidTest manifest.
 */
class SiteNotFoundControllerTestHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_not_found)
    }
}

/**
 * Instrumented tests for [SiteNotFoundController.show].
 *
 * Runs on a connected device/emulator because the controller resolves real
 * Android views via [AppCompatActivity.findViewById], reads a formatted
 * string via [AppCompatActivity.getString], and wires real [android.view.View]
 * click listeners — none of which a plain JVM unit test can exercise (the
 * project has no Robolectric on the test classpath).
 *
 * Validates: Requirements 2.2, 2.6
 */
@RunWith(AndroidJUnit4::class)
class SiteNotFoundControllerTest {

    /** Requirement 2.2: body copy is populated via getString(site_not_found_body, host). */
    @Test
    fun show_populatesBodyCopyWithHostOfFailingUrl() {
        ActivityScenario.launch(SiteNotFoundControllerTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = SiteNotFoundController(activity)

                controller.show("https://thevrege.com/some/path?q=1", onBack = {}, onRetry = {})

                val expected =
                    activity.getString(R.string.site_not_found_body, "thevrege.com")
                val body = activity.findViewById<TextView>(R.id.site_not_found_body)
                assertEquals(expected, body.text.toString())
            }
        }
    }

    /** Requirement 2.6: showing the screen starts the hero animation. */
    @Test
    fun show_startsHeroAnimation() {
        ActivityScenario.launch(SiteNotFoundControllerTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = SiteNotFoundController(activity)

                controller.show("https://thevrege.com", onBack = {}, onRetry = {})

                val hero = activity.findViewById<ImageView>(R.id.site_not_found_hero)
                val drawable = hero.drawable
                assertTrue(
                    "hero drawable must be Animatable so the controller can start it",
                    drawable is Animatable,
                )
                assertTrue(
                    "hero animation should be running after show()",
                    (drawable as Animatable).isRunning,
                )
            }
        }
    }

    /** Requirement 2.6: the back action invokes the supplied onBack lambda. */
    @Test
    fun show_backButtonInvokesOnBack() {
        val backInvoked = AtomicBoolean(false)
        val retryInvoked = AtomicBoolean(false)

        ActivityScenario.launch(SiteNotFoundControllerTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = SiteNotFoundController(activity)
                controller.show(
                    "https://thevrege.com",
                    onBack = { backInvoked.set(true) },
                    onRetry = { retryInvoked.set(true) },
                )

                activity.findViewById<Button>(R.id.site_not_found_back).performClick()

                assertTrue("onBack should fire when the back button is clicked", backInvoked.get())
                assertTrue("onRetry must not fire from a back click", !retryInvoked.get())
            }
        }
    }

    /** Requirement 2.6: the retry action invokes the supplied onRetry lambda. */
    @Test
    fun show_retryButtonInvokesOnRetry() {
        val backInvoked = AtomicBoolean(false)
        val retryInvoked = AtomicBoolean(false)

        ActivityScenario.launch(SiteNotFoundControllerTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = SiteNotFoundController(activity)
                controller.show(
                    "https://thevrege.com",
                    onBack = { backInvoked.set(true) },
                    onRetry = { retryInvoked.set(true) },
                )

                activity.findViewById<Button>(R.id.site_not_found_retry).performClick()

                assertTrue("onRetry should fire when the retry button is clicked", retryInvoked.get())
                assertTrue("onBack must not fire from a retry click", !backInvoked.get())
            }
        }
    }

    /** Requirement 2.2 (edge): a host-less URL falls back to the raw string, no crash. */
    @Test
    fun show_hostlessUrlFallsBackToRawString() {
        ActivityScenario.launch(SiteNotFoundControllerTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = SiteNotFoundController(activity)
                val raw = "not a url"

                controller.show(raw, onBack = {}, onRetry = {})

                val expected = activity.getString(R.string.site_not_found_body, raw)
                val body = activity.findViewById<TextView>(R.id.site_not_found_body)
                assertEquals(expected, body.text.toString())
            }
        }
    }

    /** Requirement 2.2 (edge): a blank URL falls back to the raw (blank) string, no crash. */
    @Test
    fun show_blankUrlFallsBackToRawString() {
        ActivityScenario.launch(SiteNotFoundControllerTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = SiteNotFoundController(activity)
                val blank = ""

                controller.show(blank, onBack = {}, onRetry = {})

                val expected = activity.getString(R.string.site_not_found_body, blank)
                val body = activity.findViewById<TextView>(R.id.site_not_found_body)
                assertEquals(expected, body.text.toString())
            }
        }
    }
}

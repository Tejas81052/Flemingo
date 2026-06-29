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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * Routes navigation intents in and out of the browser:
 *  - [extractIntentUrl] pulls an http(s) URL out of an inbound ACTION_VIEW
 *    intent (launcher deep-link / share target), enforcing the secure-scheme
 *    policy.
 *  - [handleExternalScheme] hands a non-browser URL (tel:, mailto:, intent: …)
 *    to the system after stripping it to a safe shape, honouring a
 *    `browser_fallback_url` escape hatch.
 *
 * Pulled out of MainActivity. The activity keeps the [Activity.onNewIntent]
 * override and delegates the parsing/dispatch here. Collaborators arrive as the
 * activity (for startActivity/getString) plus two callbacks, so the router
 * never reaches back into the activity's private state.
 */
class IntentRouter(
    private val activity: Activity,
    private val loadAddress: (String) -> Unit,
    private val showToast: (String) -> Unit,
) {

    /** http(s) URL from an inbound ACTION_VIEW intent, or null. */
    fun extractIntentUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) {
            return null
        }
        val rawUrl = intent.dataString ?: return null
        val lower = rawUrl.lowercase(java.util.Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null
        }
        return UrlInputUtils.enforceSecureScheme(rawUrl)
    }

    /**
     * Dispatch a non-browser-scheme URL to the system. Always returns true so
     * the WebView treats the navigation as handled and does not try to load the
     * unsupported scheme itself.
     */
    fun handleExternalScheme(targetUrl: String): Boolean {
        val intent = try {
            if (targetUrl.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            }
        } catch (_: Exception) {
            showToast(activity.getString(R.string.unsupported_link_message))
            return true
        }

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            ?.takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }

        if (!sanitizeExternalIntent(intent)) {
            if (fallbackUrl != null) {
                loadAddress(fallbackUrl)
                return true
            }
            showToast(activity.getString(R.string.unsupported_link_message))
            return true
        }

        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (fallbackUrl != null) loadAddress(fallbackUrl) else {
                showToast(activity.getString(R.string.unsupported_link_message))
            }
            true
        } catch (_: SecurityException) {
            if (fallbackUrl != null) loadAddress(fallbackUrl) else {
                showToast(activity.getString(R.string.unsupported_link_message))
            }
            true
        }
    }

    private fun sanitizeExternalIntent(intent: Intent): Boolean {
        intent.flags = 0
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setPackage(null)
        intent.selector = null
        intent.component = null
        intent.categories?.toList()?.forEach { intent.removeCategory(it) }
        intent.removeExtra(Intent.EXTRA_INTENT)
        val action = intent.action ?: return false
        return action in ALLOWED_EXTERNAL_INTENT_ACTIONS
    }

    companion object {
        private val ALLOWED_EXTERNAL_INTENT_ACTIONS = setOf(
            Intent.ACTION_VIEW,
            Intent.ACTION_DIAL,
            Intent.ACTION_SENDTO,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_WEB_SEARCH,
        )
    }
}

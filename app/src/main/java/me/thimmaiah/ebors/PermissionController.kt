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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Owns the runtime/website permission flows: WebRTC camera/mic prompts,
 * geolocation prompts, the notification-permission ask, and the
 * ActivityResultLaunchers that back them.
 *
 * Extracted from MainActivity. The three launchers register at construction
 * (this object is a plain MainActivity field, so registration happens before
 * STARTED, exactly as the inline launchers did). Per-tab in-flight state lives
 * here; the activity reaches it only through [skipWebViewPauseForPermission],
 * [clearInFlight], and [handlePermissionRequestCanceled]. Tabs are looked up via
 * the injected [findTabById] callback rather than owning the tab list.
 */
class PermissionController(
    private val activity: ComponentActivity,
    private val findTabById: (String?) -> Tab?,
    private val activeTab: () -> Tab?,
    private val showToast: (String) -> Unit,
) {

    private var permissionInFlightTabId: String? = null
    private var geolocationInFlightTabId: String? = null
    private var inFlightPermissionDialog: android.app.Dialog? = null

    /**
     * Set just before a runtime-permission round-trip so [MainActivity.onPause]
     * keeps the WebViews alive across the system permission dialog instead of
     * pausing them. Reset in onResume.
     */
    var skipWebViewPauseForPermission = false

    private val websitePermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->

            val tabId = permissionInFlightTabId
            permissionInFlightTabId = null
            val tab = findTabById(tabId) ?: return@registerForActivityResult
            val pending = tab.pendingWebsitePermission ?: return@registerForActivityResult
            tab.pendingWebsitePermission = null

            val grantableResources = pending.resources.filter { resource ->
                requiredAndroidPermissions(resource).all { permission ->
                    grants[permission] == true || hasPermission(permission)
                }
            }

            if (grantableResources.isNotEmpty()) {
                pending.request.grant(grantableResources.toTypedArray())
            } else {
                pending.request.deny()

                val deniedPermissions = pending.resources
                    .flatMap(::requiredAndroidPermissions)
                    .distinct()
                    .filterNot(::hasPermission)
                offerAppSettingsIfPermanentlyDenied(tab, deniedPermissions)
            }
        }

    private val geolocationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val tabId = geolocationInFlightTabId
            geolocationInFlightTabId = null
            val tab = findTabById(tabId) ?: return@registerForActivityResult
            val pending = tab.pendingGeolocation ?: return@registerForActivityResult
            tab.pendingGeolocation = null

            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasAnyLocationPermission()
            pending.callback.invoke(pending.origin, granted, false)
            if (!granted) {
                val deniedPermissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ).filterNot(::hasPermission)
                offerAppSettingsIfPermanentlyDenied(tab, deniedPermissions)
            }
        }

    private val notificationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showToast(activity.getString(R.string.download_notification_permission_denied))
            }
        }

    fun handleWebsitePermissionRequest(tab: Tab, request: PermissionRequest) {
        if (permissionInFlightTabId != null) {
            request.deny()
            if (tab === activeTab()) {
                showToast(activity.getString(R.string.permission_busy))
            }
            return
        }

        val supportedResources = request.resources.filter { resource ->
            resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }

        if (supportedResources.isEmpty()) {
            request.deny()
            if (tab === activeTab()) {
                showToast(activity.getString(R.string.website_permission_unsupported))
            }
            return
        }

        val rawOrigin = request.origin?.toString()
        val kinds = supportedResources.mapNotNull(::resourceToKind).distinct()

        if (!tab.isPrivate && kinds.isNotEmpty()) {
            val decisions = kinds.map { SitePermissionStore.decisionFor(rawOrigin, it) }
            if (decisions.all { it == SitePermissionStore.Decision.ALLOW }) {
                grantWebsitePermission(tab, request, supportedResources)
                return
            }
            if (decisions.any { it == SitePermissionStore.Decision.BLOCK }) {
                request.deny()
                return
            }
        }

        val origin = extractOriginLabel(rawOrigin)
        val requestedAccess = supportedResources.joinToString(separator = activity.getString(R.string.permission_joiner)) {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> activity.getString(R.string.microphone_permission_label)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> activity.getString(R.string.camera_permission_label)
                else -> it
            }
        }

        permissionInFlightTabId = tab.id

        inFlightPermissionDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.website_permission_title, origin))
            .setMessage(activity.getString(R.string.website_permission_message, origin, requestedAccess))
            .setPositiveButton(R.string.allow_always) { _, _ ->
                inFlightPermissionDialog = null
                if (!tab.isPrivate) {
                    kinds.forEach { SitePermissionStore.remember(rawOrigin, it, allow = true) }
                }
                grantWebsitePermission(tab, request, supportedResources)
            }
            .setNeutralButton(R.string.allow_once) { _, _ ->
                inFlightPermissionDialog = null
                grantWebsitePermission(tab, request, supportedResources)
            }
            .setNegativeButton(R.string.deny) { _, _ ->
                inFlightPermissionDialog = null
                request.deny()
                permissionInFlightTabId = null
            }
            .setOnCancelListener {
                inFlightPermissionDialog = null
                request.deny()
                permissionInFlightTabId = null
            }
            .show()
    }

    private fun grantWebsitePermission(
        tab: Tab,
        request: PermissionRequest,
        resources: List<String>,
    ) {
        val missingPermissions = resources
            .flatMap(::requiredAndroidPermissions)
            .distinct()
            .filterNot(::hasPermission)

        if (missingPermissions.isEmpty()) {
            request.grant(resources.toTypedArray())
            permissionInFlightTabId = null
        } else {

            permissionInFlightTabId = tab.id
            tab.pendingWebsitePermission = Tab.PendingWebsitePermission(
                request = request,
                resources = resources,
            )

            skipWebViewPauseForPermission = true
            websitePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun resourceToKind(resource: String): SitePermissionStore.Kind? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermissionStore.Kind.MICROPHONE
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermissionStore.Kind.CAMERA
        else -> null
    }

    private fun offerAppSettingsIfPermanentlyDenied(tab: Tab, deniedPermissions: List<String>) {
        if (tab !== activeTab() || deniedPermissions.isEmpty()) return
        val permanentlyDenied = deniedPermissions.any { !activity.shouldShowRequestPermissionRationale(it) }
        if (!permanentlyDenied) {
            showToast(activity.getString(R.string.website_permission_denied))
            return
        }
        val label = deniedPermissions.joinToString(activity.getString(R.string.permission_joiner)) {
            when (it) {
                Manifest.permission.CAMERA -> activity.getString(R.string.camera_permission_label)
                Manifest.permission.RECORD_AUDIO -> activity.getString(R.string.microphone_permission_label)
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION ->
                    activity.getString(R.string.location_permission_kind)
                else -> it
            }
        }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.permission_blocked_in_android_title)
            .setMessage(activity.getString(R.string.permission_blocked_in_android_message, label))
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppDetailsSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAppDetailsSettings() {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        } catch (_: ActivityNotFoundException) {

        }
    }

    fun handleGeolocationPermissionRequest(
        tab: Tab,
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        if (origin.isNullOrBlank() || callback == null) {
            callback?.invoke(origin, false, false)
            return
        }
        if (geolocationInFlightTabId != null) {
            callback.invoke(origin, false, false)
            if (tab === activeTab()) {
                showToast(activity.getString(R.string.permission_busy))
            }
            return
        }

        if (!tab.isPrivate) {
            when (SitePermissionStore.decisionFor(origin, SitePermissionStore.Kind.LOCATION)) {
                SitePermissionStore.Decision.ALLOW -> {
                    grantGeolocation(tab, origin, callback)
                    return
                }
                SitePermissionStore.Decision.BLOCK -> {
                    callback.invoke(origin, false, false)
                    return
                }
                SitePermissionStore.Decision.ASK -> Unit
            }
        }

        geolocationInFlightTabId = tab.id

        val originLabel = extractOriginLabel(origin)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.location_permission_title, originLabel))
            .setMessage(activity.getString(R.string.location_permission_message, originLabel))
            .setPositiveButton(R.string.allow_always) { _, _ ->
                if (!tab.isPrivate) {
                    SitePermissionStore.remember(origin, SitePermissionStore.Kind.LOCATION, allow = true)
                }
                grantGeolocation(tab, origin, callback)
            }
            .setNeutralButton(R.string.allow_once) { _, _ ->
                grantGeolocation(tab, origin, callback)
            }
            .setNegativeButton(R.string.deny) { _, _ ->

                callback.invoke(origin, false, false)
                geolocationInFlightTabId = null
                showToast(activity.getString(R.string.location_denied))
            }
            .setOnCancelListener {
                callback.invoke(origin, false, false)
                geolocationInFlightTabId = null
            }
            .show()
    }

    private fun grantGeolocation(
        tab: Tab,
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        if (hasAnyLocationPermission()) {

            callback.invoke(origin, true, false)
            geolocationInFlightTabId = null
            if (!isLocationServiceEnabled()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.location_services_off_title)
                    .setMessage(R.string.location_services_off_message)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        try {
                            activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (_: ActivityNotFoundException) {

                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } else {
            geolocationInFlightTabId = tab.id
            tab.pendingGeolocation = Tab.PendingGeolocation(origin, callback)
            geolocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return true
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            true
        }
    }

    fun maybeRequestNotificationPermission(prefs: BrowserPreferences) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (
            prefs.notificationPromptShown ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        prefs.notificationPromptShown = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Relay for WebChromeClient.onPermissionRequestCanceled. */
    fun handlePermissionRequestCanceled(tab: Tab, request: PermissionRequest?) {
        if (tab.pendingWebsitePermission?.request == request) {
            tab.pendingWebsitePermission = null
            if (permissionInFlightTabId == tab.id) permissionInFlightTabId = null
        } else if (permissionInFlightTabId == tab.id && tab.pendingWebsitePermission == null) {

            permissionInFlightTabId = null
            inFlightPermissionDialog?.dismiss()
            inFlightPermissionDialog = null
        }
    }

    /** Clear any in-flight prompt state (call from onDestroy). */
    fun clearInFlight() {
        permissionInFlightTabId = null
        geolocationInFlightTabId = null
        inFlightPermissionDialog = null
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun requiredAndroidPermissions(resource: String): List<String> {
        return when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
            else -> emptyList()
        }
    }

    private fun extractOriginLabel(origin: String?): String {
        val uri = origin?.let(Uri::parse)
        return uri?.host ?: origin ?: activity.getString(R.string.this_site)
    }
}

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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar

/**
 * Tiny day-scoped counter for "trackers blocked today" on the v10 start
 * page.
 *
 * Intentionally lightweight: just a SharedPreferences-backed int plus a
 * date key. Resets at local-day rollover (24h granularity is enough for
 * a number that's shown rounded to the nearest integer on a card).
 *
 * Threading: writes happen on whichever thread the WebView's
 * `shouldInterceptRequest` runs on (a worker pool on modern WebView
 * builds), and reads happen on the UI thread when the start page
 * paints. A single `@Volatile Int` is enough — we don't need
 * atomicity-of-N-increments, only that each increment isn't lost
 * mid-write. The pref `apply()` is async, so even high block rates
 * (hundreds per page) won't block the request thread.
 */
object TrackerStats {

    private const val PREFS_NAME = "effective_browser_tracker_stats"
    private const val KEY_DATE = "key_day_of_year"
    private const val KEY_COUNT = "key_count"

    @Volatile
    private var todayCount: Int = 0

    /** Year * 1000 + dayOfYear; rolls over at local midnight without
     *  needing a heavy Calendar parse on every read. */
    @Volatile
    private var todayKey: Int = 0

    @Volatile
    private var prefs: SharedPreferences? = null

    private val writeLock = Any()

    fun initialize(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs = store
        val today = computeTodayKey()
        todayKey = today
        todayCount = if (store.getInt(KEY_DATE, 0) == today) {
            store.getInt(KEY_COUNT, 0).coerceAtLeast(0)
        } else {
            0
        }
    }

    /** Called by [BrowserBlocker] whenever it returns a non-null
     *  blocking response. Cheap fast-path: a single volatile increment
     *  and an async-persisted pref write. */
    fun recordBlock() {
        val store = prefs ?: return
        synchronized(writeLock) {
            val current = computeTodayKey()
            if (current != todayKey) {
                todayKey = current
                todayCount = 0
            }
            todayCount += 1
            store.edit {
                putInt(KEY_DATE, current)
                putInt(KEY_COUNT, todayCount)
            }
        }
    }

    /** Best-effort live count for the current day. May read just-past-
     *  midnight as 0 even if [recordBlock] hasn't yet fired its rollover
     *  branch on a slow background thread — fine for a display number. */
    fun today(): Int {
        val current = computeTodayKey()
        return if (current == todayKey) todayCount else 0
    }

    private fun computeTodayKey(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }
}

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
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * On-device store for "save for offline reading". Each saved page is the
 * reader-extracted article HTML written to `filesDir/offline/<id>.html`,
 * with a small JSON index of metadata alongside it.
 *
 * Reading back goes through `WebView.loadDataWithBaseURL` (a string load,
 * not a `file://` load) so it works even though the browser keeps
 * `allowFileAccess = false` — the saved bytes never touch a file URL the
 * renderer can see.
 *
 * Writes run on a private single-thread executor (article HTML can be a
 * few hundred KB); reads of the small index are synchronous. All file IO
 * is wrapped so a corrupt index or missing file degrades to "empty"
 * rather than crashing the browser.
 */
object OfflineArticleStore {
    private const val TAG = "OfflineArticleStore"
    private const val DIR = "offline"
    private const val INDEX = "index.json"
    private const val MAX_ARTICLES = 100

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    data class OfflineArticle(
        val id: String,
        val url: String,
        val title: String,
        val savedAt: Long,
    )

    /** Persist [html] for [url]; [onDone] runs on the main thread. */
    fun save(
        context: Context,
        url: String,
        title: String,
        html: String,
        onDone: (Boolean) -> Unit,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val ok = runCatching {
                synchronized(lock) {
                    val dir = File(appContext.filesDir, DIR).apply { mkdirs() }
                    val id = UUID.randomUUID().toString()
                    File(dir, "$id.html").writeText(html, Charsets.UTF_8)
                    val list = readIndexLocked(appContext).toMutableList()
                    list.add(
                        0,
                        OfflineArticle(id, url, title.ifBlank { url }, System.currentTimeMillis()),
                    )
                    // Evict oldest past the cap, deleting their files too.
                    while (list.size > MAX_ARTICLES) {
                        val removed = list.removeAt(list.size - 1)
                        File(dir, "${removed.id}.html").delete()
                    }
                    writeIndexLocked(appContext, list)
                }
                true
            }.getOrElse {
                Log.w(TAG, "Failed to save offline article", it)
                false
            }
            mainHandler.post { onDone(ok) }
        }
    }

    /** Newest-first list of saved articles. Safe to call on the main thread. */
    fun list(context: Context): List<OfflineArticle> =
        synchronized(lock) { readIndexLocked(context.applicationContext) }

    fun readHtml(context: Context, id: String): String? = synchronized(lock) {
        runCatching {
            File(File(context.applicationContext.filesDir, DIR), "$id.html").readText(Charsets.UTF_8)
        }.getOrNull()
    }

    fun delete(context: Context, id: String) {
        val appContext = context.applicationContext
        executor.execute {
            synchronized(lock) {
                runCatching {
                    val dir = File(appContext.filesDir, DIR)
                    File(dir, "$id.html").delete()
                    writeIndexLocked(appContext, readIndexLocked(appContext).filterNot { it.id == id })
                }.onFailure { Log.w(TAG, "Failed to delete offline article", it) }
            }
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            synchronized(lock) {
                runCatching {
                    File(appContext.filesDir, DIR).deleteRecursively()
                }.onFailure { Log.w(TAG, "Failed to clear offline articles", it) }
            }
        }
    }

    private fun readIndexLocked(context: Context): List<OfflineArticle> {
        val file = File(File(context.filesDir, DIR), INDEX)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                OfflineArticle(
                    id = o.getString("id"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    savedAt = o.optLong("savedAt"),
                )
            }
        }.getOrElse {
            Log.w(TAG, "Corrupt offline index; resetting", it)
            emptyList()
        }
    }

    private fun writeIndexLocked(context: Context, list: List<OfflineArticle>) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val array = JSONArray()
        for (a in list) {
            array.put(
                JSONObject()
                    .put("id", a.id)
                    .put("url", a.url)
                    .put("title", a.title)
                    .put("savedAt", a.savedAt),
            )
        }
        File(dir, INDEX).writeText(array.toString(), Charsets.UTF_8)
    }
}

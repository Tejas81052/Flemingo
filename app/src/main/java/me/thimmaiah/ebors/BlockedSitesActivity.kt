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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
// Empty-state holder is a LinearLayout (title + subtitle + button), not a
// TextView. Field is typed as View so the findViewById cast doesn't blow up.
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BlockedSitesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private val adapter = BlockedSitesAdapter { domain -> confirmRemove(domain) }

    private val repoListener = object : BlockedSitesRepository.Listener {
        override fun onBlockedSitesChanged(domains: List<String>) {
            adapter.submitList(domains)
            emptyView.isVisible = domains.isEmpty()
            recyclerView.isVisible = domains.isNotEmpty()
            toolbar.menu.findItem(R.id.action_clear_all)?.isVisible = domains.isNotEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(BrowserPreferences.from(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_sites)

        BlockedSitesRepository.initialize(applicationContext)

        toolbar = findViewById(R.id.blocked_sites_toolbar)
        recyclerView = findViewById(R.id.blocked_sites_list)
        emptyView = findViewById(R.id.blocked_sites_empty)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.blocked_sites_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_blocked -> {
                    showAddDialog()
                    true
                }
                R.id.action_clear_all -> {
                    confirmClearAll()
                    true
                }
                else -> false
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.add_first_site).setOnClickListener { showAddDialog() }
    }

    override fun onStart() {
        super.onStart()
        BlockedSitesRepository.addListener(repoListener)
    }

    override fun onStop() {
        BlockedSitesRepository.removeListener(repoListener)
        super.onStop()
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_blocked_site, null)
        val input = view.findViewById<EditText>(R.id.add_blocked_input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocked_sites_add_title)
            .setView(view)
            .setPositiveButton(R.string.blocked_sites_add_confirm) { _, _ ->
                val added = BlockedSitesRepository.addFromText(input.text?.toString().orEmpty())
                val message = when (added) {
                    0 -> getString(R.string.blocked_sites_added_none)
                    1 -> getString(R.string.blocked_sites_added_one)
                    else -> getString(R.string.blocked_sites_added_many, added)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(domain: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocked_sites_remove_title)
            .setMessage(getString(R.string.blocked_sites_remove_message, domain))
            .setPositiveButton(R.string.blocked_sites_remove_confirm) { _, _ ->
                BlockedSitesRepository.remove(domain)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocked_sites_clear_title)
            .setMessage(R.string.blocked_sites_clear_message)
            .setPositiveButton(R.string.blocked_sites_clear_confirm) { _, _ ->
                BlockedSitesRepository.clear()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * `ListAdapter`-based adapter so add/remove on the blocked-sites list
 * animates row in/out instead of doing a full rebind. The diff cost is
 * trivial — domains are short strings and the list size cap is 500.
 */
private class BlockedSitesAdapter(
    private val onRemove: (String) -> Unit,
) : ListAdapter<String, BlockedSitesAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_site, parent, false)
        return VH(view, onRemove)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View, private val onRemove: (String) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val domainView: TextView = itemView.findViewById(R.id.blocked_site_domain)
        private val removeButton: ImageButton = itemView.findViewById(R.id.blocked_site_remove)

        fun bind(domain: String) {
            domainView.text = domain
            removeButton.setOnClickListener { onRemove(domain) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            // Domains are unique within the list (the repository
            // dedupes on insert), so string equality serves both
            // identity and contents.
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem
        }
    }
}

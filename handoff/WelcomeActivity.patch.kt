// ──────────────────────────────────────────────────────────────────────
// Patch for WelcomeActivity.kt — step-indicator + promise binding.
//
// Drop these two routines into WelcomeActivity.kt. Call refreshSteps()
// from your existing welcome_flipper.setOnTouchListener / showNext()
// path; call bindPromises() once in onCreate after setContentView.
// ──────────────────────────────────────────────────────────────────────

import android.view.View
import androidx.core.content.ContextCompat

private fun refreshSteps(activeIndex: Int) {
    // The header layout is <include>d on every page, so there are
    // multiple instances of welcome_step_1/_2/_3 in the view tree —
    // we iterate them all and paint by index.
    val dotIds = arrayOf(R.id.welcome_step_1, R.id.welcome_step_2, R.id.welcome_step_3)
    val root = findViewById<View>(R.id.welcome_root)

    dotIds.forEachIndexed { idx, dotId ->
        // findAllViewsById equivalent — scan once.
        root.findAllViewsById(dotId).forEach { dot ->
            val isActive = idx == activeIndex
            val lp = dot.layoutParams
            lp.width = if (isActive) dpToPx(28) else dpToPx(14)
            dot.layoutParams = lp

            // Page 3 uses cream dots over the accent panel — its
            // header_inverse layout already binds the cream drawable
            // so we leave those alone (idx < activeIndex && page == 2).
            val isInverseHeader = dot.rootView
                .findViewById<View>(R.id.welcome_flipper)
                ?.let { (it as android.widget.ViewFlipper).displayedChild == 2 }
                ?: false
            if (!isInverseHeader) {
                dot.background = ContextCompat.getDrawable(
                    this,
                    if (isActive) R.drawable.bg_welcome_step_dot_active
                    else R.drawable.bg_welcome_step_dot
                )
            }
        }
    }
}

// Tiny helper — findAllViewsById walks the tree and collects matches.
// Android's findViewById only returns the first occurrence; we need
// every instance because the header is <include>d on every page.
private fun View.findAllViewsById(@androidx.annotation.IdRes id: Int): List<View> {
    val out = mutableListOf<View>()
    fun walk(v: View) {
        if (v.id == id) out += v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    walk(this)
    return out
}

private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

// ── Bind the four promise cards on page 2 ──────────────────────────────

private data class Promise(val title: Int, val body: Int)

private fun bindPromises() {
    val data = listOf(
        Promise(R.string.welcome_promise_1_title, R.string.welcome_promise_1_body),
        Promise(R.string.welcome_promise_2_title, R.string.welcome_promise_2_body),
        Promise(R.string.welcome_promise_3_title, R.string.welcome_promise_3_body),
        Promise(R.string.welcome_promise_4_title, R.string.welcome_promise_4_body),
    )
    val rowIds = arrayOf(
        R.id.welcome_promise_1,
        R.id.welcome_promise_2,
        R.id.welcome_promise_3,
        R.id.welcome_promise_4,
    )
    rowIds.forEachIndexed { i, rowId ->
        val row = findViewById<View>(rowId) ?: return@forEachIndexed
        row.findViewById<android.widget.TextView>(R.id.welcome_promise_title)
            .setText(data[i].title)
        row.findViewById<android.widget.TextView>(R.id.welcome_promise_body)
            .setText(data[i].body)
    }
}

// In onCreate, after setContentView(R.layout.activity_welcome):
//   bindPromises()
//   refreshSteps(0)
//   welcome_flipper.setOnDisplayedChildChangedListener { idx -> refreshSteps(idx) }
// (or call refreshSteps(idx) in whatever showNext()/showPrevious wrapper
//  you already have.)

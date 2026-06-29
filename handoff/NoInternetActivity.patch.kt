// ──────────────────────────────────────────────────────────────────────
// NoInternetActivity.kt — patch.
//
// Either drop these routines into a dedicated NoInternetActivity, or
// fold them into MainActivity's offline-state handler (recommended —
// the offline screen replaces the WebView, it isn't a separate Activity).
//
// What it does:
//   1. Starts the hero AnimatedVectorDrawable on screen-show, stops it
//      when the screen goes away (saves battery; AVD doesn't auto-pause).
//   2. Spins the refresh icon inside the stamp button with a
//      ValueAnimator — 3.4s cycle, ease-in-out, matching the
//      prototype's `nit-refresh-spin` keyframes (60% hold, then 360°
//      sweep).
//   3. Wires the Try Again button to retry the failed URL.
// ──────────────────────────────────────────────────────────────────────

import android.animation.ValueAnimator
import android.graphics.drawable.Animatable
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NoInternetController(private val activity: AppCompatActivity) {

    private val hero: ImageView = activity.findViewById(R.id.no_internet_hero)
    private val refreshIcon: ImageView = activity.findViewById(R.id.no_internet_refresh_icon)
    private val tabUrl: TextView = activity.findViewById(R.id.no_internet_tab_url)
    private val tryAgain: Button = activity.findViewById(R.id.no_internet_try_again)

    private var iconSpin: ValueAnimator? = null

    fun show(failedHost: String, onRetry: () -> Unit) {
        tabUrl.text = failedHost
        tryAgain.setOnClickListener { onRetry() }

        // 1. Start the hero AVD.
        (hero.drawable as? Animatable)?.start()

        // 2. Spin the refresh icon. The CSS keyframes hold for the first
        //    60% of the cycle, then sweep -360° over the remaining 40%
        //    with an ease-in-out interpolation — same effect here via a
        //    PathInterpolator that's flat-then-curved.
        iconSpin = ValueAnimator.ofFloat(0f, -360f).apply {
            duration = 3400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = PathInterpolator(
                /* curve flat through 0..0.6, then ease-in-out */
                0.6f, 0f, 0.45f, 1f
            )
            addUpdateListener { refreshIcon.rotation = it.animatedValue as Float }
            start()
        }
    }

    fun hide() {
        (hero.drawable as? Animatable)?.stop()
        iconSpin?.cancel()
        iconSpin = null
    }
}

// In MainActivity (the simplest hookup — assumes there's a FrameLayout
// for the WebView and you swap children when offline):
//
//   private lateinit var offlineCtrl: NoInternetController
//
//   override fun onCreate(savedInstanceState: Bundle?) {
//       super.onCreate(savedInstanceState)
//       setContentView(R.layout.activity_main)
//
//       // Inflate the offline view but keep it hidden until needed.
//       val offlineView = layoutInflater.inflate(
//           R.layout.activity_no_internet, web_container, false
//       ).apply { visibility = View.GONE }
//       web_container.addView(offlineView)
//
//       offlineCtrl = NoInternetController(this)
//   }
//
//   private fun showOffline(failedUrl: String) {
//       offlineView.visibility = View.VISIBLE
//       webView.visibility = View.GONE
//       offlineCtrl.show(failedUrl.toUri().host ?: failedUrl) {
//           // Retry: hide offline, reload.
//           offlineView.visibility = View.GONE
//           webView.visibility = View.VISIBLE
//           offlineCtrl.hide()
//           webView.reload()
//       }
//   }

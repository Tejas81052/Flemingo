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
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * Address-bar EditText that gets first crack at the BACK key via
 * [onKeyPreIme] — before the soft keyboard consumes it to hide itself.
 * The host uses [onBackPreIme] only to dismiss its autocomplete dropdown.
 *
 * It deliberately does NOT consume the event or clear focus: on devices
 * with predictive back the BACK also reaches the activity's back
 * dispatcher independently, and clearing focus here would race that
 * dispatcher into thinking nothing was being edited (and navigating /
 * exiting). Keyboard-hide and focus/navigation are handled by the
 * activity's IME-inset listener and back dispatcher instead.
 */
class AddressEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Dismiss the dropdown on BACK. Backup for older WebView/OS builds
     *  where the IME-inset signal isn't reliable; modern devices also go
     *  through the activity's inset listener. */
    var onBackPreIme: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBackPreIme?.invoke()
        }
        return super.onKeyPreIme(keyCode, event)
    }
}

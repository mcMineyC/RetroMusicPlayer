/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.ViewStructure
import android.webkit.WebView
import androidx.annotation.RequiresApi

/**
 * A WebView that bypasses some common crashes and bugs.
 *
 * Originally named LollipopFixedWebView to address a resource bug in Android 5.0/5.1.
 * Since the app's minSdk is now 24, the Lollipop-specific fix is removed to avoid
 * breaking InputMethodManager (IME) functionality.
 */
class LollipopFixedWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onProvideAutofillVirtualStructure(structure: ViewStructure?, flags: Int) {
        // By not calling super, we prevent the WebView from providing a virtual structure to the
        // Autofill service, which is known to cause NullPointerExceptions in cr_AutofillHintsService
        // on some Android versions. We don't change importantForAutofill or getAutofillType
        // to ensure the keyboard (IME) continues to function correctly.
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> {
        // Return an empty array instead of null to prevent "Attempt to get length of null array"
        // crashes in the system Autofill service.
        return arrayOf()
    }
}

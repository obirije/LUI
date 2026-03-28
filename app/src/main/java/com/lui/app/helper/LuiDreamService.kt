package com.lui.app.helper

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.lui.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LuiDreamService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var clockView: TextView? = null
    private var dateView: TextView? = null

    private val updateClock = object : Runnable {
        override fun run() {
            clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            dateView?.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000) // Update every 30s
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        isScreenBright = false // Dim for OLED burn-in protection

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
        }

        val monoFont: Typeface? = try {
            ResourcesCompat.getFont(this, R.font.jetbrains_mono_bold)
        } catch (e: Exception) {
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val interFont: Typeface? = try {
            ResourcesCompat.getFont(this, R.font.inter_light)
        } catch (e: Exception) {
            Typeface.DEFAULT
        }

        // LUI branding — very subtle
        val logoView = TextView(this).apply {
            text = "LUI"
            setTextColor(0xFF1E1E1E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = monoFont
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
        }

        // Time
        clockView = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = interFont
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            layoutParams = params
        }

        // Date
        dateView = TextView(this).apply {
            setTextColor(0xFF616161.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = interFont
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            layoutParams = params
        }

        layout.addView(logoView)
        layout.addView(clockView)
        layout.addView(dateView)
        setContentView(layout)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        updateClock.run()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        handler.removeCallbacks(updateClock)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(updateClock)
        super.onDetachedFromWindow()
    }
}

package com.lui.app.helper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.lui.app.R

object WallpaperHelper {

    fun setLuiWallpaper(context: Context) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Pure black background
            canvas.drawColor(0xFF0A0A0A.toInt())

            // Draw "LUI" logo in center, subtle
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1A1A1A.toInt() // Very subtle — just barely visible
                textSize = 64f * metrics.density
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.3f
                try {
                    typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)
                } catch (e: Exception) {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
            }

            val x = width / 2f
            val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText("LUI", x, y, paint)

            // Set for both home and lock screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
            } else {
                wallpaperManager.setBitmap(bitmap)
            }

            bitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

package com.lui.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lui.app.MainActivity
import com.lui.app.R
import com.lui.app.databinding.ActivitySplashBinding
import com.lui.app.llm.ModelManager
import com.lui.app.ui.onboarding.OnboardingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var animationDone = false
    private var loadingDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAnimation()
        startPreloading()
    }

    private fun startAnimation() {
        val logo = binding.splashLogo

        logo.scaleX = 0.8f
        logo.scaleY = 0.8f
        logo.alpha = 0f

        // Fade in + scale
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .withEndAction {
                // If loading is already done, fade out immediately
                // Otherwise, stay visible — pulse subtly while waiting
                if (loadingDone) {
                    fadeOutAndNavigate()
                } else {
                    animationDone = true
                    startPulse(logo)
                }
            }
            .start()
    }

    private fun startPulse(logo: TextView) {
        logo.animate()
            .alpha(0.6f)
            .setDuration(800)
            .withEndAction {
                if (loadingDone) {
                    fadeOutAndNavigate()
                } else {
                    logo.animate()
                        .alpha(1f)
                        .setDuration(800)
                        .withEndAction { startPulse(logo) }
                        .start()
                }
            }
            .start()
    }

    /**
     * Pre-load models in background while splash animates.
     * This copies model files from staging if needed — the expensive part
     * that causes the blank screen between splash and main.
     */
    private fun startPreloading() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    ModelManager.ensureModel(this@SplashActivity)
                    ModelManager.ensureVoiceModels(this@SplashActivity)
                    ModelManager.ensureTtsModel(this@SplashActivity)
                } catch (e: Exception) {
                    // Non-fatal — main activity will handle missing models
                }
            }

            loadingDone = true
            if (animationDone) {
                fadeOutAndNavigate()
            }
            // If animation isn't done yet, it will navigate when it finishes
        }
    }

    private fun fadeOutAndNavigate() {
        binding.splashLogo.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { navigate() }
            .start()
    }

    private fun navigate() {
        val prefs = getSharedPreferences("lui_prefs", MODE_PRIVATE)
        val dest = if (prefs.getBoolean("onboarding_complete", false))
            MainActivity::class.java else OnboardingActivity::class.java

        startActivity(Intent(this, dest))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

package com.lui.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lui.app.MainActivity
import com.lui.app.R
import com.lui.app.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        binding.btnMicPermission.text = if (granted) "Microphone enabled" else "Enable microphone"
        binding.btnMicPermission.isEnabled = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skip if already completed
        val prefs = getSharedPreferences("lui_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_complete", false)) {
            launchMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check already-granted permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            binding.btnMicPermission.text = "Microphone enabled"
            binding.btnMicPermission.isEnabled = false
        }
        binding.btnDefaultLauncher.setOnClickListener {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }

        binding.btnMicPermission.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnGetStarted.setOnClickListener {
            prefs.edit().putBoolean("onboarding_complete", true).apply()
            launchMain()
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

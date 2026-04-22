package com.lui.app

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.lui.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen when wake word triggers
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hardware volume buttons control media volume (TTS)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.canvasFragment) {
                    navController.popBackStack()
                }
            }
        })

        // Cold-start path for wake word / ring-gesture activation — onCreate
        // fires instead of onNewIntent when LUI wasn't already running.
        //
        // Guard on `savedInstanceState == null` so a config-change or
        // low-memory recreate doesn't replay the original wake-word intent
        // every time the user returns from background. We also consume the
        // extras after reading so getIntent() won't re-trigger on later
        // reads.
        if (savedInstanceState == null && consumeVoiceTrigger(intent)) {
            val vm = androidx.lifecycle.ViewModelProvider(this)[LuiViewModel::class.java]
            window.decorView.postDelayed({ vm.onWakeWordActivated() }, 800)
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)           // so getIntent() reflects the new intent
        if (navController.currentDestination?.id != R.id.canvasFragment) {
            navController.popBackStack(R.id.canvasFragment, false)
        }

        // Wake word / ring gesture triggered — greet and start conversation mode.
        // Consume the flags immediately so a subsequent recreate doesn't replay.
        if (consumeVoiceTrigger(intent)) {
            val vm = androidx.lifecycle.ViewModelProvider(this)[LuiViewModel::class.java]
            window.decorView.postDelayed({ vm.onWakeWordActivated() }, 500)
        }
    }

    /** Read and clear the wake_word / ring_gesture extras. Returns true if
     *  either was set. */
    private fun consumeVoiceTrigger(intent: android.content.Intent?): Boolean {
        if (intent == null) return false
        val wake = intent.getBooleanExtra("wake_word", false)
        val ring = intent.getBooleanExtra("ring_gesture", false)
        if (wake) intent.removeExtra("wake_word")
        if (ring) intent.removeExtra("ring_gesture")
        return wake || ring
    }
}

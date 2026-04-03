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
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (navController.currentDestination?.id != R.id.canvasFragment) {
            navController.popBackStack(R.id.canvasFragment, false)
        }

        // Wake word triggered — greet and start conversation mode
        if (intent?.getBooleanExtra("wake_word", false) == true) {
            val vm = androidx.lifecycle.ViewModelProvider(this)[LuiViewModel::class.java]
            window.decorView.postDelayed({
                // LUI responds to wake word with a greeting, then listens
                vm.onWakeWordActivated()
            }, 500)
        }
    }
}

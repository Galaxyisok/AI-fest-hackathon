package com.scamguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.scamguard.calls.CallGuardService
import com.scamguard.phase1.Phase1Screen
import com.scamguard.ui.DemoScreen
import com.scamguard.ui.HomeScreen
import com.scamguard.ui.LiveScreen
import com.scamguard.ui.PrivacyScreen
import com.scamguard.ui.ScamGuardTheme
import com.scamguard.ui.SetupScreen

enum class Screen { HOME, LIVE, DEMO, SETUP, PROFILE, PRIVACY }

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf(Screen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallGuardService.createChannels(this)
        savedInstanceState?.getString("screen")?.let { screen = Screen.valueOf(it) }
        route(intent)
        setContent {
            ScamGuardTheme {
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onStartLive = ::startLive,
                        onDemo = { screen = Screen.DEMO },
                        onSetup = { screen = Screen.SETUP },
                        onProfile = { screen = Screen.PROFILE },
                        onPrivacy = { screen = Screen.PRIVACY },
                        onOpenLive = { screen = Screen.LIVE },
                    )
                    Screen.LIVE -> LiveScreen(onBack = { screen = Screen.HOME })
                    Screen.DEMO -> DemoScreen(onBack = { screen = Screen.HOME }, onPlay = { call ->
                        CallGuardService.startDemo(this, call.asset, call.title)
                        screen = Screen.LIVE
                    })
                    Screen.SETUP -> SetupScreen(onBack = { screen = Screen.HOME })
                    Screen.PROFILE -> Phase1Screen(onDone = { screen = Screen.HOME })
                    Screen.PRIVACY -> PrivacyScreen(onBack = { screen = Screen.HOME })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.name)
    }

    private fun route(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_LIVE -> screen = Screen.LIVE
            OPEN_START_LIVE -> startLive()
            OPEN_DEMO -> intent.getStringExtra(EXTRA_DEMO_ASSET)?.let { asset ->
                // Lets a rehearsal or test start a demo call directly: adb shell am start ... --es open demo --es asset demo/x.wav
                CallGuardService.startDemo(this, asset, intent.getStringExtra(EXTRA_DEMO_TITLE) ?: "Demo call")
                screen = Screen.LIVE
            }
        }
        intent?.removeExtra(EXTRA_OPEN)
    }

    private fun startLive() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            screen = Screen.SETUP   // explain first, then ask
            return
        }
        CallGuardService.startLive(this)
        screen = Screen.LIVE
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_LIVE = "live"
        const val OPEN_START_LIVE = "start_live"
        const val OPEN_DEMO = "demo"
        const val EXTRA_DEMO_ASSET = "asset"
        const val EXTRA_DEMO_TITLE = "title"
    }
}

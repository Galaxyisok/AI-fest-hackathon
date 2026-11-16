package com.scamguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.scamguard.ui.AppDock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.scamguard.calls.CallGuardService
import com.scamguard.phase1.LearnScreen
import com.scamguard.phase1.Phase1Screen
import com.scamguard.profile.ProfileRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.scamguard.ui.DemoScreen
import com.scamguard.ui.HomeScreen
import com.scamguard.ui.LiveScreen
import com.scamguard.ui.PrivacyScreen
import com.scamguard.ui.ScamGuardTheme
import com.scamguard.ui.SetupScreen

enum class Screen { HOME, LIVE, DEMO, SETUP, PROFILE, LEARN, PRIVACY }

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf(Screen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallGuardService.createChannels(this)
        savedInstanceState?.getString("screen")?.let { screen = Screen.valueOf(it) }
        val opened = intent?.getStringExtra(EXTRA_OPEN)
        route(intent)
        if (savedInstanceState == null && opened == null) offerOnboardingOnce()
        setContent {
            ScamGuardTheme {
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                val dockVisible = screen in listOf(Screen.HOME, Screen.LEARN, Screen.PROFILE)
                Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = screen,
                    modifier = Modifier.fillMaxSize().padding(bottom = if (dockVisible) 88.dp else 0.dp),
                    transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 30 }) togetherWith fadeOut(tween(140)) },
                    label = "page",
                ) { currentScreen ->
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(
                        onStartLive = ::startLive,
                        onDemo = { screen = Screen.DEMO },
                        onSetup = { screen = Screen.SETUP },
                        onProfile = { screen = Screen.PROFILE },
                        onLearn = { screen = Screen.LEARN },
                        onPrivacy = { screen = Screen.PRIVACY },
                        onOpenLive = { screen = Screen.LIVE },
                    )
                    Screen.LIVE -> LiveScreen(onBack = { screen = Screen.HOME })
                    Screen.DEMO -> DemoScreen(onBack = { screen = Screen.HOME }, onPlay = { call ->
                        CallGuardService.startDemo(this@MainActivity, call.asset, call.title)
                        screen = Screen.LIVE
                    })
                    Screen.SETUP -> SetupScreen(onBack = { screen = Screen.HOME })
                    Screen.PROFILE -> Phase1Screen(onDone = { screen = Screen.HOME }, onLearn = { screen = Screen.LEARN })
                    Screen.LEARN -> LearnScreen(onBack = { screen = Screen.HOME }, onPractice = { asset, title ->
                        CallGuardService.startDemo(this@MainActivity, asset, title)
                        screen = Screen.LIVE
                    })
                    Screen.PRIVACY -> PrivacyScreen(onBack = { screen = Screen.HOME })
                }
                }
                if (dockVisible) AppDock(screen, { screen = it }, Modifier.align(Alignment.BottomCenter))
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

    /** First launch with no profile: open the profile questions once. "Not now" is remembered. */
    private fun offerOnboardingOnce() {
        lifecycleScope.launch {
            val repo = ProfileRepository(this@MainActivity)
            if (repo.load() == null && !repo.onboardingOfferedFlow.first()) {
                repo.setOnboardingOffered()
                if (screen == Screen.HOME) screen = Screen.PROFILE
            }
        }
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

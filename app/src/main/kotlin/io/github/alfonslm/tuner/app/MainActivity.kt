package io.github.alfonslm.tuner.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.alfonslm.tuner.app.audio.MicrophoneSource
import io.github.alfonslm.tuner.app.ui.TunerActions
import io.github.alfonslm.tuner.app.ui.TunerScreen
import io.github.alfonslm.tuner.app.ui.TunerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TunerTheme {
                TunerApp()
            }
        }
    }
}

private val TunerViewModelFactory = viewModelFactory {
    initializer {
        val application = checkNotNull(this[APPLICATION_KEY])
        TunerViewModel(MicrophoneSource(application), PreferencesSettingsStore(application))
    }
}

/** Connects the screen to the view model and to the three things only the platform can do. */
@Composable
private fun TunerApp(viewModel: TunerViewModel = viewModel(factory = TunerViewModelFactory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 1. Ask for the microphone the first time the user presses start.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.permissionDenied()
    }

    // 2. Give the microphone back whenever the app leaves the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop() }

    // 3. Keep the screen awake while tuning: both hands are on the instrument.
    val view = LocalView.current
    DisposableEffect(view, state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    val actions = remember(viewModel, context) {
        TunerActions(
            onToggleRunning = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                when {
                    viewModel.state.value.running -> viewModel.stop()
                    granted -> viewModel.start()
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onInstrument = viewModel::setInstrument,
            onReferencePitch = viewModel::setReferencePitch,
            onSmartFilter = viewModel::setSmartFilter,
            onTogglePin = viewModel::togglePin,
        )
    }
    TunerScreen(state, actions)
}

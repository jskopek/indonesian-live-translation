package ca.skopek.dengar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.skopek.dengar.ui.TranslatorScreen
import ca.skopek.dengar.ui.theme.DengarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: TranslatorViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) viewModel.toggleListening() else viewModel.showPermissionDenied()
            }

            DengarTheme {
                TranslatorScreen(
                    state = state,
                    level = viewModel.level,
                    onToggleListening = {
                        if (hasMicrophonePermission()) {
                            viewModel.toggleListening()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onClear = viewModel::clearTranscript,
                    onRetryModel = viewModel::retryModel,
                    onDismissNotice = viewModel::dismissNotice,
                )
            }
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

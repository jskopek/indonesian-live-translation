package ca.skopek.dengar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.skopek.dengar.DengarViewModel
import ca.skopek.dengar.Screen

@Composable
fun DengarApp(viewModel: DengarViewModel, onToggleRecording: () -> Unit) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()

    BackHandler(enabled = screen != Screen.Live) { viewModel.back() }

    when (val current = screen) {
        Screen.Live -> {
            val state by viewModel.live.collectAsStateWithLifecycle()
            LiveScreen(
                state = state,
                level = viewModel.level,
                onToggleRecording = onToggleRecording,
                onOpenHistory = { viewModel.navigate(Screen.History) },
                onOpenSettings = { viewModel.navigate(Screen.Settings) },
                onRetryModel = viewModel::retryModel,
                onDismissNotice = viewModel::dismissNotice,
            )
        }

        Screen.History -> {
            val conversations by viewModel.conversations.collectAsStateWithLifecycle()
            HistoryScreen(
                conversations = conversations,
                onOpen = { viewModel.navigate(Screen.Playback(it)) },
                onBack = viewModel::back,
            )
        }

        is Screen.Playback -> {
            val conversations by viewModel.conversations.collectAsStateWithLifecycle()
            val conversation = conversations.firstOrNull { it.id == current.id }
            PlaybackScreen(
                conversation = conversation,
                audioFile = viewModel.store.audioFile(current.id),
                onBack = viewModel::back,
                onDelete = { viewModel.deleteConversation(current.id) },
            )
        }

        Screen.Settings -> {
            SettingsScreen(
                initialApiKey = viewModel.apiKey,
                initialModel = viewModel.transcribeModel,
                onSave = { key, model ->
                    viewModel.saveSettings(key, model)
                    viewModel.back()
                },
                onBack = viewModel::back,
            )
        }
    }
}

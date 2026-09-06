package ca.skopek.dengar.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import ca.skopek.dengar.BuildConfig
import ca.skopek.dengar.Connection
import ca.skopek.dengar.LiveUiState
import ca.skopek.dengar.R
import ca.skopek.dengar.transcript.Segment
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    state: LiveUiState,
    level: StateFlow<Float>,
    onToggleRecording: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryModel: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val transcript = state.transcript

    // Keep the screen on while the phone is being held up to listen.
    val view = LocalView.current
    DisposableEffect(state.recording) {
        view.keepScreenOn = state.recording
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        onDismissNotice()
    }

    // Follow the conversation: the newest text stays in view.
    val lastText = transcript.segments.lastOrNull()?.indonesian.orEmpty()
    LaunchedEffect(transcript.segments.size, lastText.length) {
        if (transcript.segments.isNotEmpty()) listState.animateScrollToItem(transcript.segments.size - 1)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenHistory, enabled = !state.recording) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.history))
                    }
                    IconButton(onClick = onOpenSettings, enabled = !state.recording) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = { if (!state.finishing) onToggleRecording() },
                containerColor = if (state.recording) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                if (state.finishing) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        imageVector = if (state.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (state.recording) R.string.stop_recording else R.string.start_recording,
                        ),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StatusBanner(state = state, onRetryModel = onRetryModel, onOpenSettings = onOpenSettings)

            if (transcript.isEmpty) {
                EmptyState(state = state, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(transcript.segments, key = { it.id }) { segment ->
                        SegmentRow(segment = segment)
                    }
                }
            }

            LevelMeter(level = level, active = state.recording && !state.finishing)
        }
    }
}

@Composable
private fun StatusBanner(state: LiveUiState, onRetryModel: () -> Unit, onOpenSettings: () -> Unit) {
    val clock = formatClock(state.elapsedMs)
    val message: String? = when {
        !state.hasApiKey -> stringResource(R.string.need_api_key)
        state.finishing -> stringResource(R.string.finishing)
        state.recording -> when (state.connection) {
            Connection.Connecting, Connection.Idle -> stringResource(R.string.status_connecting)
            Connection.Live -> stringResource(R.string.status_live, clock)
            Connection.Reconnecting -> stringResource(R.string.status_reconnecting, clock)
        }
        state.modelError != null -> stringResource(R.string.model_failed, state.modelError)
        !state.modelReady -> stringResource(R.string.model_downloading)
        else -> null
    }
    if (message == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        when {
            !state.hasApiKey -> TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            !state.recording && state.modelError != null -> TextButton(onClick = onRetryModel) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun EmptyState(state: LiveUiState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(if (state.recording) R.string.status_live else R.string.status_idle, formatClock(state.elapsedMs)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun SegmentRow(segment: Segment, highlighted: Boolean = false) {
    val pending = !segment.isFinal
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        val english = segment.english
        Text(
            text = english ?: if (pending) segment.indonesian else stringResource(R.string.translating),
            style = MaterialTheme.typography.headlineSmall,
            fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
            color = when {
                highlighted -> MaterialTheme.colorScheme.primary
                english == null && !pending -> MaterialTheme.colorScheme.outline
                pending -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (english != null || !pending) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = segment.indonesian,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelMeter(level: StateFlow<Float>, active: Boolean) {
    val fraction by level.collectAsState()
    LinearProgressIndicator(
        progress = { if (active) fraction else 0f },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        trackColor = Color.Transparent,
    )
}

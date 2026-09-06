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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import ca.skopek.dengar.R
import ca.skopek.dengar.UiState
import ca.skopek.dengar.transcript.Segment
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    state: UiState,
    level: StateFlow<Float>,
    onToggleListening: () -> Unit,
    onClear: () -> Unit,
    onRetryModel: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val transcript = state.transcript

    // Keep the screen on while the phone is being held up to listen.
    val view = LocalView.current
    DisposableEffect(state.listening) {
        view.keepScreenOn = state.listening
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        onDismissNotice()
    }

    // Follow the conversation: newest text stays in view.
    val itemCount = transcript.segments.size + if (transcript.partialIndonesian.isNotBlank()) 1 else 0
    LaunchedEffect(itemCount, transcript.partialIndonesian.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (!transcript.isEmpty) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear_transcript))
                        }
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
        floatingActionButton = {
            if (state.speechAvailable) {
                LargeFloatingActionButton(
                    onClick = onToggleListening,
                    containerColor = if (state.listening) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ) {
                    Icon(
                        imageVector = if (state.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (state.listening) R.string.stop_listening else R.string.start_listening,
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
            StatusBanner(state = state, onRetryModel = onRetryModel)

            if (transcript.isEmpty) {
                EmptyState(listening = state.listening, modifier = Modifier.weight(1f))
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
                    if (transcript.partialIndonesian.isNotBlank()) {
                        item(key = "partial") {
                            PartialRow(
                                indonesian = transcript.partialIndonesian,
                                english = transcript.partialEnglish,
                            )
                        }
                    }
                }
            }

            LevelMeter(level = level, listening = state.listening)
        }
    }
}

@Composable
private fun StatusBanner(state: UiState, onRetryModel: () -> Unit) {
    val message: String? = when {
        !state.speechAvailable -> stringResource(R.string.speech_unavailable)
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
        if (state.modelError != null) {
            TextButton(onClick = onRetryModel) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun EmptyState(listening: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(if (listening) R.string.status_listening else R.string.status_idle),
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
private fun SegmentRow(segment: Segment) {
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Text(
            text = segment.english ?: stringResource(R.string.translating),
            style = MaterialTheme.typography.headlineSmall,
            color = if (segment.english != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = segment.indonesian,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PartialRow(indonesian: String, english: String) {
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        if (english.isNotBlank()) {
            Text(
                text = english,
                style = MaterialTheme.typography.headlineSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = indonesian,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun LevelMeter(level: StateFlow<Float>, listening: Boolean) {
    val rmsDb by level.collectAsState()
    // The recogniser reports roughly -2 dB (silence) to 10 dB (loud speech).
    val fraction = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { if (listening) fraction else 0f },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        trackColor = Color.Transparent,
    )
}

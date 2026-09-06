package ca.skopek.dengar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ca.skopek.dengar.R
import ca.skopek.dengar.playback.ConversationPlayer
import ca.skopek.dengar.store.Conversation
import ca.skopek.dengar.transcript.Segment
import java.io.File
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.5f, 0.65f, 0.8f, 1f)

private fun speedLabel(speed: Float): String = if (speed == 1f) "1×" else "${speed}×"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    conversation: Conversation?,
    audioFile: File,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(conversation?.let { formatDate(it.startedAtMillis) } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_conversation))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (conversation == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                PlaybackBody(conversation = conversation, audioFile = audioFile)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PlaybackBody(conversation: Conversation, audioFile: File) {
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(0.8f) }
    var repeatId by remember { mutableStateOf<String?>(null) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()

    val player = remember(audioFile) {
        runCatching { ConversationPlayer(audioFile) { playing = false } }.getOrNull()
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    if (player == null) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Text(
                text = stringResource(R.string.playback_missing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(16.dp))
            TranscriptList(conversation.segments, currentIndex = -1, listState = listState, onTap = {})
        }
        return
    }

    val segments = conversation.segments
    val durationMs = player.durationMs.coerceAtLeast(1L)

    fun endOf(index: Int): Long {
        val segment = segments[index]
        return segment.endMs
            ?: segments.getOrNull(index + 1)?.startMs
            ?: durationMs
    }

    fun playFrom(ms: Long) {
        player.seekTo(ms)
        positionMs = ms
        player.speed = speed
        player.play()
        playing = true
    }

    val currentIndex = segments.indexOfLast { (it.startMs ?: 0L) <= positionMs }

    // Poll the player while it runs; also implement "repeat this line".
    LaunchedEffect(playing, repeatId) {
        while (playing) {
            positionMs = player.positionMs
            val repeatIndex = repeatId?.let { id -> segments.indexOfFirst { it.id == id } } ?: -1
            if (repeatIndex >= 0 && positionMs >= endOf(repeatIndex)) {
                val start = segments[repeatIndex].startMs ?: 0L
                player.seekTo(start)
                positionMs = start
            }
            delay(100)
        }
    }

    LaunchedEffect(currentIndex) {
        if (playing && currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TranscriptList(
            segments = segments,
            currentIndex = currentIndex,
            listState = listState,
            modifier = Modifier.weight(1f),
            onTap = { index ->
                val start = segments[index].startMs ?: 0L
                if (repeatId != null) repeatId = segments[index].id
                playFrom(start)
            },
        )

        Surface(tonalElevation = 3.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = if (repeatId != null) stringResource(R.string.repeat_on) else stringResource(R.string.tap_line_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(dragValue?.toLong() ?: positionMs), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = dragValue ?: positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { dragValue = it },
                        onValueChangeFinished = {
                            dragValue?.let { value ->
                                player.seekTo(value.toLong())
                                positionMs = value.toLong()
                            }
                            dragValue = null
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(formatClock(durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledIconButton(
                        onClick = {
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                player.speed = speed
                                player.play()
                                playing = true
                            }
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                        )
                    }
                    IconButton(
                        onClick = {
                            repeatId = if (repeatId != null) null else segments.getOrNull(currentIndex)?.id
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (repeatId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(Icons.Filled.Repeat, contentDescription = stringResource(R.string.repeat_line))
                    }
                    Spacer(Modifier.width(4.dp))
                    SPEEDS.forEach { option ->
                        FilterChip(
                            selected = speed == option,
                            onClick = {
                                speed = option
                                player.speed = option
                            },
                            label = { Text(speedLabel(option)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptList(
    segments: List<Segment>,
    currentIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(segments, key = { _, segment -> segment.id }) { index, segment ->
            Box(modifier = Modifier.fillMaxWidth().clickable { onTap(index) }) {
                SegmentRow(segment = segment, highlighted = index == currentIndex)
            }
        }
    }
}

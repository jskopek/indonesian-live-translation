package ca.skopek.dengar.realtime

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One WebSocket session against the OpenAI Realtime transcription endpoint. Audio goes in as
 * base64 PCM chunks; transcript events come back through [Listener] on OkHttp's threads.
 */
class RealtimeTranscriber(
    private val apiKey: String,
    private val model: String,
    private val language: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onEvent(event: RealtimeEvent)

        /** [reason] is null when the socket was closed by [close]. */
        fun onDisconnected(reason: String?)
    }

    private var socket: WebSocket? = null

    @Volatile
    private var open = false

    @Volatile
    private var closedByUs = false

    val isOpen: Boolean get() = open

    fun connect() {
        closedByUs = false
        val request = Request.Builder()
            .url(RealtimeProtocol.URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = httpClient.newWebSocket(request, socketListener)
    }

    /** Returns false if the socket is not open; the caller decides whether that matters. */
    fun sendAudio(pcm: ByteArray, length: Int): Boolean {
        if (!open) return false
        return socket?.send(RealtimeProtocol.audioAppend(pcm, length)) ?: false
    }

    /** Ask the server to transcribe whatever is buffered, used when the user stops mid-sentence. */
    fun commit() {
        if (open) socket?.send(RealtimeProtocol.audioCommit())
    }

    fun close() {
        closedByUs = true
        open = false
        socket?.close(1000, "done")
        socket = null
    }

    private companion object {
        /** One client for the whole app; each instance would otherwise leak a thread pool. */
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(RealtimeProtocol.sessionUpdate(model, language))
            open = true
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { RealtimeProtocol.parse(text) }.getOrNull() ?: return
            listener.onEvent(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            listener.onDisconnected(if (closedByUs) null else "connection closed ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false
            val reason = when {
                closedByUs -> null
                response?.code == 401 -> "OpenAI rejected the API key (401)"
                response != null -> "OpenAI returned HTTP ${response.code}"
                else -> t.message ?: t.javaClass.simpleName
            }
            listener.onDisconnected(reason)
        }
    }
}

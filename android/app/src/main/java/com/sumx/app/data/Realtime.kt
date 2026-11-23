package com.sumx.app.data

import com.sumx.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** WebSocket connection with auth-on-first-frame, heartbeat pings and exponential-backoff reconnect. */
class Realtime(private val api: Api, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val _events = MutableSharedFlow<Pair<String, JsonObject>>(extraBufferCapacity = 128)
    val events: SharedFlow<Pair<String, JsonObject>> = _events.asSharedFlow()
    private var ws: WebSocket? = null
    private var job: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        job = scope.launch {
            var backoff = 1000L
            while (isActive && running) {
                val closed = CompletableDeferred<Unit>()
                try {
                    val token = api.accessToken()
                    if (token != null) {
                        val url = BuildConfig.API_BASE_URL.replaceFirst("http", "ws") + "/ws"
                        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(buildJsonObject { put("type", "auth"); put("token", token) }.toString())
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                runCatching {
                                    val o = api.json.parseToJsonElement(text).jsonObject
                                    val type = o["type"]!!.jsonPrimitive.content
                                    if (type == "ready") backoff = 1000L
                                    _events.tryEmit(type to (o["data"] as? JsonObject ?: JsonObject(emptyMap())))
                                }
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                        })
                        closed.await()
                        if (running) _events.tryEmit("reconnected" to JsonObject(emptyMap())) // lets the UI resync missed events
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                if (running) { delay(backoff); backoff = minOf(backoff * 2, 15_000L) }
            }
        }
    }

    fun send(o: JsonObject): Boolean = ws?.send(o.toString()) ?: false

    fun typing(conversationId: String) {
        ws?.send(buildJsonObject { put("type", "typing"); put("conversationId", conversationId) }.toString())
    }

    fun stop() { running = false; job?.cancel(); ws?.close(1000, null); ws = null }
}

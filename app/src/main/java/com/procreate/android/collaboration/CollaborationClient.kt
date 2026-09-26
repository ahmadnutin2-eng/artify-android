package com.procreate.android.collaboration

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One application-scoped WebSocket. Gallery starts matching; CanvasActivity takes over events. */
class CollaborationClient(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var listener: ((CollaborationEvent) -> Unit)? = null
    @Volatile private var closingIntentionally = false
    @Volatile private var connectionGeneration = 0
    @Volatile var currentSession: CollaborationSession? = null
        private set

    fun observe(observer: ((CollaborationEvent) -> Unit)?) {
        listener = observer
        if (observer != null) currentSession?.let { dispatch(CollaborationEvent.Matched(it)) }
    }

    fun search(displayName: String, width: Int, height: Int): Boolean {
        val url = CollaborationConfig.serverUrl(appContext)
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) return false
        if (!com.procreate.android.BuildConfig.DEBUG && !url.startsWith("wss://")) return false
        leave(sendLeave = false)
        val generation = ++connectionGeneration
        closingIntentionally = false
        currentSession = null
        dispatch(CollaborationEvent.Connecting)
        val localId = CollaborationConfig.clientId(appContext)
        val cleanName = displayName.trim().take(40).ifEmpty { "Artist ${localId.takeLast(4)}" }
        CollaborationConfig.setDisplayName(appContext, cleanName)
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration) {
                    webSocket.close(1000, "superseded")
                    return
                }
                webSocket.send(JSONObject()
                    .put("type", "search")
                    .put("clientId", localId)
                    .put("name", cleanName)
                    .put("width", width.coerceIn(256, 4096))
                    .put("height", height.coerceIn(256, 4096))
                    .toString())
                dispatch(CollaborationEvent.Searching)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                runCatching { handleMessage(JSONObject(text), localId, cleanName) }
                    .onFailure { dispatch(CollaborationEvent.Error("Invalid collaboration message")) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                socket = null
                if (!closingIntentionally) dispatch(CollaborationEvent.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration) return
                socket = null
                if (!closingIntentionally) {
                    dispatch(CollaborationEvent.Error(t.message ?: "Connection failed"))
                }
            }
        })
        return true
    }

    fun sendStroke(stroke: LiveStrokeEvent) {
        val session = currentSession ?: return
        send(JSONObject()
            .put("type", "stroke")
            .put("sessionId", session.sessionId)
            .put("strokeId", stroke.strokeId)
            .put("phase", stroke.phase.name.lowercase())
            .put("x", stroke.x.toDouble())
            .put("y", stroke.y.toDouble())
            .put("pressure", stroke.pressure.toDouble())
            .put("color", stroke.color)
            .put("size", stroke.size.toDouble())
            .put("opacity", stroke.opacity.toDouble())
            .put("eraser", stroke.eraser))
    }

    fun sendPatch(strokeId: String, left: Int, top: Int, png: ByteArray) {
        val session = currentSession ?: return
        send(JSONObject()
            .put("type", "patch")
            .put("sessionId", session.sessionId)
            .put("strokeId", strokeId)
            .put("left", left)
            .put("top", top)
            .put("png", Base64.encodeToString(png, Base64.NO_WRAP)))
    }

    fun cancelSearch() = leave(sendLeave = true)

    fun leave(sendLeave: Boolean = true) {
        closingIntentionally = true
        if (sendLeave) {
            currentSession?.let { session ->
                send(JSONObject().put("type", "leave").put("sessionId", session.sessionId))
            }
            socket?.send(JSONObject().put("type", "cancel").toString())
        }
        socket?.close(1000, "leaving")
        socket = null
        currentSession = null
        connectionGeneration += 1
    }

    private fun send(json: JSONObject): Boolean = socket?.send(json.toString()) == true

    private fun handleMessage(json: JSONObject, localId: String, localName: String) {
        when (json.getString("type")) {
            "matched" -> {
                val session = CollaborationSession(
                    sessionId = json.getString("sessionId"),
                    localParticipantId = localId,
                    localParticipantName = localName,
                    partnerParticipantId = json.getString("partnerId"),
                    partnerParticipantName = json.optString("partnerName", "Partner"),
                    canvasWidth = json.getInt("width"),
                    canvasHeight = json.getInt("height")
                )
                currentSession = session
                dispatch(CollaborationEvent.Matched(session))
            }
            "stroke" -> dispatch(CollaborationEvent.PartnerStroke(
                participantId = json.getString("participantId"),
                participantName = json.optString("participantName", "Partner"),
                stroke = LiveStrokeEvent(
                    strokeId = json.getString("strokeId"),
                    phase = StrokePhase.valueOf(json.getString("phase").uppercase()),
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat(),
                    pressure = json.getDouble("pressure").toFloat(),
                    color = json.getInt("color"),
                    size = json.getDouble("size").toFloat(),
                    opacity = json.getDouble("opacity").toFloat(),
                    eraser = json.optBoolean("eraser", false)
                )
            ))
            "patch" -> dispatch(CollaborationEvent.PartnerPatch(RemoteStrokePatch(
                participantId = json.getString("participantId"),
                participantName = json.optString("participantName", "Partner"),
                strokeId = json.getString("strokeId"),
                left = json.getInt("left"),
                top = json.getInt("top"),
                pngBytes = Base64.decode(json.getString("png"), Base64.DEFAULT)
            )))
            "partner_left" -> dispatch(CollaborationEvent.PartnerLeft(
                json.optString("participantName", "Partner")
            ))
            "error" -> dispatch(CollaborationEvent.Error(json.optString("message", "Server error")))
        }
    }

    private fun dispatch(event: CollaborationEvent) {
        mainHandler.post { listener?.invoke(event) }
    }
}

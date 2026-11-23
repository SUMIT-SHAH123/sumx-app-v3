package com.sumx.app.data

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.webrtc.*

sealed interface CallState {
    data object Idle : CallState
    data class Outgoing(val peer: Person, val kind: String) : CallState
    data class Incoming(val callId: String, val peer: Person, val kind: String) : CallState
    data class Active(val peer: Person, val kind: String, val connected: Boolean, val startedAt: Long) : CallState
}

/** 1:1 voice/video calls. Signaling goes over the app's WebSocket; media flows peer-to-peer (or via TURN) with WebRTC. */
class CallManager(private val app: Application, private val api: Api, private val rt: Realtime, private val scope: CoroutineScope) {
    var state by mutableStateOf<CallState>(CallState.Idle); private set
    var muted by mutableStateOf(false); private set
    var cameraOff by mutableStateOf(false); private set
    var speaker by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null)
    var localVideo by mutableStateOf<VideoTrack?>(null); private set
    var remoteVideo by mutableStateOf<VideoTrack?>(null); private set
    var settings: Settings = Settings()
    val egl: EglBase by lazy { EglBase.create() }

    private val mainH = Handler(Looper.getMainLooper())
    private fun main(f: () -> Unit) { mainH.post(f) }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var callId: String? = null
    private var isCaller = false
    private var kind = "voice"
    private var cancelRequested = false
    private var remoteSet = false
    private val pending = mutableListOf<IceCandidate>()

    open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    private fun send(o: JsonObject) = rt.send(o)
    private fun signal(payload: JsonObject) { callId?.let { id -> send(buildJsonObject { put("type", "call.signal"); put("callId", id); put("payload", payload) }) } }

    // ---------- public actions ----------
    fun invite(peer: Person, kind: String) {
        if (state !is CallState.Idle) return
        this.kind = kind; isCaller = true; cancelRequested = false
        state = CallState.Outgoing(peer, kind)
        startLocal(kind); audioMode(kind == "video")
        send(buildJsonObject { put("type", "call.invite"); put("calleeId", peer.id); put("kind", kind) })
    }
    fun accept() {
        val s = state as? CallState.Incoming ?: return
        isCaller = false; callId = s.callId; kind = s.kind
        state = CallState.Active(s.peer, s.kind, false, 0)
        scope.launch { startLocal(s.kind); audioMode(s.kind == "video"); createPc(); send(buildJsonObject { put("type", "call.accept"); put("callId", s.callId) }) }
    }
    fun decline() { (state as? CallState.Incoming)?.let { s -> send(buildJsonObject { put("type", "call.decline"); put("callId", s.callId) }) }; cleanup() }
    fun hangup() {
        val id = callId
        if (id != null) send(buildJsonObject { put("type", "call.end"); put("callId", id) }) else cancelRequested = true
        cleanup()
    }
    fun toggleMute() { muted = !muted; audioTrack?.setEnabled(!muted) }
    fun toggleCamera() { cameraOff = !cameraOff; videoTrack?.setEnabled(!cameraOff) }
    fun switchCamera() { capturer?.switchCamera(null) }
    fun toggleSpeaker() { speaker = !speaker; (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = speaker }

    // ---------- server events (returns true if consumed) ----------
    fun onEvent(type: String, d: JsonObject): Boolean {
        when (type) {
            "call.incoming" -> {
                val id = d["callId"]!!.jsonPrimitive.content
                if (state !is CallState.Idle) { send(buildJsonObject { put("type", "call.decline"); put("callId", id) }); return true }
                state = CallState.Incoming(id, api.json.decodeFromJsonElement(d["from"]!!), d["kind"]!!.jsonPrimitive.content)
            }
            "call.ringing" -> { callId = d["callId"]!!.jsonPrimitive.content; if (cancelRequested) hangup() }
            "call.accepted" -> {
                val s = state as? CallState.Outgoing ?: return true
                state = CallState.Active(s.peer, s.kind, false, 0)
                scope.launch { createPc(); makeOffer() }
            }
            "call.signal" -> onSignal(d["payload"] as? JsonObject ?: return true)
            "call.ended" -> {
                notice = when (d["reason"]?.jsonPrimitive?.content) { "declined" -> "Call declined."; "missed" -> "No answer."; "cancelled" -> "Call cancelled."; else -> "Call ended." }
                cleanup()
            }
            "call.error" -> { notice = d["message"]?.jsonPrimitive?.content ?: "Call failed."; cleanup() }
            else -> return false
        }
        return true
    }

    // ---------- WebRTC plumbing ----------
    private fun startLocal(kind: String) {
        val f = factory()
        val ac = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", settings.voice.echoCancellation.toString()))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", settings.voice.noiseSuppression.toString()))
        }
        val src = f.createAudioSource(ac); audioSource = src
        audioTrack = f.createAudioTrack("a0", src).also { it.setEnabled(true) }
        if (kind != "video") return
        val en = Camera2Enumerator(app)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
        val cap = en.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("sumx-cap", egl.eglBaseContext)
        val vs = f.createVideoSource(false)
        cap.initialize(helper, app, vs.capturerObserver); cap.startCapture(640, 480, 24)
        capturer = cap; surfaceHelper = helper; videoSource = vs
        cameraOff = settings.voice.startVideoOff
        val vt = f.createVideoTrack("v0", vs).also { it.setEnabled(!cameraOff) }
        videoTrack = vt; main { localVideo = vt }
    }

    private suspend fun createPc() {
        val servers = runCatching { api.iceServers().iceServers }.getOrNull().orEmpty().map { s ->
            PeerConnection.IceServer.builder(s.urls).apply { s.username?.let { setUsername(it) }; s.credential?.let { setPassword(it) } }.createIceServer()
        }.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) }
        val cfg = PeerConnection.RTCConfiguration(servers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val conn = factory().createPeerConnection(cfg, observer) ?: run { notice = "Couldn't start the call."; hangup(); return }
        audioTrack?.let { conn.addTrack(it, listOf("sumx")) }
        videoTrack?.let { conn.addTrack(it, listOf("sumx")) }
        pc = conn
    }

    private fun sdpConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", (kind == "video").toString()))
    }
    private fun makeOffer() {
        pc?.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                d ?: return; pc?.setLocalDescription(Sdp(), d)
                signal(buildJsonObject { put("kind", "offer"); put("sdp", d.description) })
            }
        }, sdpConstraints())
    }
    private fun remoteReady() { remoteSet = true; pending.forEach { pc?.addIceCandidate(it) }; pending.clear() }

    private fun onSignal(p: JsonObject) {
        when (p["kind"]?.jsonPrimitive?.content) {
            "offer" -> pc?.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    remoteReady()
                    pc?.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            d ?: return; pc?.setLocalDescription(Sdp(), d)
                            signal(buildJsonObject { put("kind", "answer"); put("sdp", d.description) })
                        }
                    }, sdpConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, p["sdp"]!!.jsonPrimitive.content))
            "answer" -> pc?.setRemoteDescription(object : Sdp() { override fun onSetSuccess() = remoteReady() },
                SessionDescription(SessionDescription.Type.ANSWER, p["sdp"]!!.jsonPrimitive.content))
            "candidate" -> {
                val c = IceCandidate(p["sdpMid"]?.jsonPrimitive?.contentOrNull, p["sdpMLineIndex"]!!.jsonPrimitive.int, p["candidate"]!!.jsonPrimitive.content)
                if (remoteSet) pc?.addIceCandidate(c) else pending.add(c)
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                    main { (state as? CallState.Active)?.let { if (!it.connected) state = it.copy(connected = true, startedAt = System.currentTimeMillis()) } }
                PeerConnection.IceConnectionState.FAILED -> main { notice = "Connection lost."; hangup() }
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {
            c ?: return
            signal(buildJsonObject { put("kind", "candidate"); put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex) })
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(t: RtpTransceiver?) { val tr = t?.receiver?.track(); if (tr is VideoTrack) main { remoteVideo = tr } }
    }

    private fun audioMode(speakerOn: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn = speakerOn; speaker = speakerOn
    }

    fun cleanup() {
        runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        videoTrack?.dispose(); videoTrack = null; videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null; audioSource?.dispose(); audioSource = null
        pc?.close(); pc = null
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply { mode = AudioManager.MODE_NORMAL; isSpeakerphoneOn = false }
        pending.clear(); remoteSet = false; callId = null; cancelRequested = false
        main { localVideo = null; remoteVideo = null; muted = false; cameraOff = false; state = CallState.Idle }
    }
}

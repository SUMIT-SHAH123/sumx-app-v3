package com.sumx.app.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sumx.app.data.CallManager
import com.sumx.app.data.CallState
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun VideoView(track: VideoTrack?, egl: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(ctx).apply {
        init(egl.eglBaseContext, null); setEnableHardwareScaler(true); setMirror(mirror)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); if (overlay) setZOrderMediaOverlay(true) } }
    DisposableEffect(track) { track?.addSink(renderer); onDispose { track?.removeSink(renderer) } }
    DisposableEffect(Unit) { onDispose { renderer.release() } }
    AndroidView({ renderer }, modifier)
}

@Composable
fun CallScreen(cm: CallManager, ask: (List<String>, () -> Unit) -> Unit) {
    val s = cm.state
    val (peer, kind) = when (s) { is CallState.Outgoing -> s.peer to s.kind; is CallState.Incoming -> s.peer to s.kind; is CallState.Active -> s.peer to s.kind; else -> return }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val video = kind == "video"

    Box(Modifier.fillMaxSize().background(SumxColors.Bg)) {
        if (video && s is CallState.Active && cm.remoteVideo != null) VideoView(cm.remoteVideo, cm.egl, false, false, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!(video && s is CallState.Active && cm.remoteVideo != null)) {
                Avatar(peer.displayName, 120.dp, url = peer.avatarUrl); Spacer(Modifier.height(16.dp))
            }
            Text(peer.displayName, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(when (s) {
                is CallState.Incoming -> "Incoming ${if (video) "video" else "voice"} call"
                is CallState.Outgoing -> "Calling…"
                is CallState.Active -> if (!s.connected) "Connecting…" else { val sec = ((now - s.startedAt) / 1000).coerceAtLeast(0); "%d:%02d".format(sec / 60, sec % 60) }
                else -> ""
            }, color = SumxColors.Cyan, fontSize = 16.sp)
        }
        if (video && s !is CallState.Incoming) VideoView(cm.localVideo, cm.egl, true, true,
            Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(16.dp).size(110.dp, 160.dp).clip(RoundedCornerShape(12.dp)))

        Row(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s is CallState.Incoming) {
                FilledIconButton({ cm.decline() }, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = DndRed)) { Icon(Icons.Filled.CallEnd, "Decline") }
                FilledIconButton({
                    ask(if (video) listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else listOf(Manifest.permission.RECORD_AUDIO)) { cm.accept() }
                }, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = OnlineGreen)) { Icon(Icons.Filled.Call, "Accept") }
            } else {
                FilledTonalIconButton({ cm.toggleMute() }, Modifier.size(56.dp)) { Icon(if (cm.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute microphone") }
                if (video) {
                    FilledTonalIconButton({ cm.toggleCamera() }, Modifier.size(56.dp)) { Icon(if (cm.cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam, "Toggle camera") }
                    FilledTonalIconButton({ cm.switchCamera() }, Modifier.size(56.dp)) { Icon(Icons.Filled.FlipCameraAndroid, "Switch camera") }
                }
                FilledTonalIconButton({ cm.toggleSpeaker() }, Modifier.size(56.dp)) { Icon(if (cm.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown, "Speaker") }
                FilledIconButton({ cm.hangup() }, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = DndRed)) { Icon(Icons.Filled.CallEnd, "End call", tint = Color.White) }
            }
        }
    }
}

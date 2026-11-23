package com.sumx.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import com.sumx.app.data.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads now-playing metadata from media apps (Spotify, YouTube Music, local players...) through Android's MediaSession API.
 * This only works for apps that publish a media session, and only after the user grants "Notification access" and turns detection on.
 */
class MediaListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var msm: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var pushedKey: String? = null
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java); msm = m
        val cn = ComponentName(this, MediaListener::class.java)
        runCatching { m.addOnActiveSessionsChangedListener(sessionsListener, cn); attach(m.getActiveSessions(cn)) }
    }
    override fun onListenerDisconnected() {
        controllers.forEach { runCatching { it.unregisterCallback(cb) } }; controllers = emptyList()
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        clear()
    }
    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(cb) } }
        controllers = list; list.forEach { it.registerCallback(cb) }; evaluate()
    }

    private fun evaluate() {
        if (!getSharedPreferences("sumx_prefs", Context.MODE_PRIVATE).getBoolean("autodetect", false)) return clear()
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return clear()
        val md = c.metadata ?: return clear()
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return clear()
        val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        if (artist == null && md.getString(MediaMetadata.METADATA_KEY_ALBUM) == null) return clear() // not music
        val key = "${c.packageName}|$title|$artist"
        if (key == pushedKey) return
        pushedKey = key
        val pkg = c.packageName
        val platform = when { "spotify" in pkg -> "spotify"; "youtube.music" in pkg || "apps.youtube.music" in pkg -> "youtube_music"; else -> "other" }
        val art = (md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI))?.takeIf { it.startsWith("https://") }
        val dur = (md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000).toInt().takeIf { it > 0 }
        scope.launch { runCatching { Graph.api(applicationContext).setListening(platform, title.take(120), artist?.take(120), art, dur, "device") } }
    }
    private fun clear() {
        if (pushedKey == null) return
        pushedKey = null
        scope.launch { runCatching { Graph.api(applicationContext).clearListening() } }
    }
}

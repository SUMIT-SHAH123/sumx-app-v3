package com.sumx.app.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import coil.compose.AsyncImage
import com.sumx.app.BuildConfig
import com.sumx.app.data.ActivityInfo
import com.sumx.app.data.Graph
import com.sumx.app.data.ListeningInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val OnlineGreen = Color(0xFF3DDC84)
val IdleAmber = Color(0xFFFFB020)
val DndRed = Color(0xFFFF5C7A)

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dayFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
fun hhmm(iso: String) = runCatching { timeFmt.format(Instant.parse(iso)) }.getOrDefault("")
fun dayTime(iso: String) = runCatching { dayFmt.format(Instant.parse(iso)) }.getOrDefault("")

fun hexColor(s: String?, fallback: Color = SumxColors.Cyan): Color = runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(fallback)
fun statusColor(s: String?) = when (s) { "online" -> OnlineGreen; "idle" -> IdleAmber; "dnd" -> DndRed; else -> Color(0xFF4B5772) }
fun statusLabel(s: String?) = when (s) { "online" -> "Online"; "idle" -> "Idle"; "dnd" -> "Do not disturb"; else -> "Offline" }

fun platformLabel(p: String?) = when (p) { "sumx_beats" -> "SUM X Beats"; "spotify" -> "Spotify"; "youtube_music" -> "YouTube Music"; "local" -> "local music"; else -> null }
fun listeningText(l: ListeningInfo): String {
    val song = listOfNotNull(l.title, l.artist).joinToString(" – ")
    return "Listening to " + song.ifBlank { "music" } + (platformLabel(l.platform)?.let { " on $it" } ?: "")
}
fun activityLine(a: ActivityInfo): String = when (a.type) {
    "playing" -> "Playing ${a.name}"; "watching" -> "Watching ${a.name}"; "building" -> "Building ${a.name}"; "using_sumx" -> "Using SUM X"; else -> a.name
}
fun activityText(a: ActivityInfo?, l: ListeningInfo?): String? = l?.let { listeningText(it) } ?: a?.let { activityLine(it) }

fun autodetectEnabled(ctx: Context) = ctx.getSharedPreferences("sumx_prefs", Context.MODE_PRIVATE).getBoolean("autodetect", false)
fun setAutodetect(ctx: Context, on: Boolean) = ctx.getSharedPreferences("sumx_prefs", Context.MODE_PRIVATE).edit().putBoolean("autodetect", on).apply()
fun hasListenerAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
fun openListenerSettings(ctx: Context) = ctx.startActivity(android.content.Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))

/** Loads server paths ("/files/..") with auth, or any other absolute/content URL as-is. */
@Composable
fun NetImage(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, alignment: Alignment = Alignment.Center) {
    val ctx = LocalContext.current
    val url = when { path == null -> null; path.startsWith("/") -> BuildConfig.API_BASE_URL + path; else -> path }
    AsyncImage(model = url, contentDescription = null, imageLoader = Graph.imageLoader(ctx), modifier = modifier, contentScale = contentScale, alignment = alignment)
}

@Composable
fun Avatar(name: String, size: Dp = 44.dp, status: String? = null, url: String? = null, frame: String = "none", decoration: String = "none", accent: Color = SumxColors.Cyan) {
    val frameMod = when (frame) {
        "ring" -> Modifier.border(2.dp, accent, CircleShape)
        "gradient" -> Modifier.border(3.dp, Brush.sweepGradient(listOf(SumxColors.Blue, SumxColors.Cyan, SumxColors.Blue)), CircleShape)
        "double" -> Modifier.border(2.dp, accent, CircleShape).padding(3.dp).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape).padding(2.dp)
        else -> Modifier
    }
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().then(frameMod).clip(CircleShape).background(Brush.linearGradient(listOf(SumxColors.Blue, SumxColors.Cyan))), Alignment.Center) {
            if (url != null) NetImage(url, Modifier.fillMaxSize())
            else Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color.White, fontSize = (size.value / 2.4f).sp)
        }
        val deco = when (decoration) { "star" -> "⭐"; "crown" -> "👑"; "flame" -> "🔥"; "bolt" -> "⚡"; else -> null }
        if (deco != null) Text(deco, fontSize = (size.value / 3.2f).sp, modifier = Modifier.align(Alignment.TopEnd))
        if (status != null) Box(Modifier.size(size / 3.2f).align(Alignment.BottomEnd).clip(CircleShape).background(SumxColors.Bg).padding(2.dp).clip(CircleShape).background(statusColor(status)))
    }
}

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(text, color = SumxColors.Muted) }

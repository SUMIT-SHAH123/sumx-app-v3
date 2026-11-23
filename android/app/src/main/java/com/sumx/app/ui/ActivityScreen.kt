package com.sumx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sumx.app.data.NotifItem
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun MusicDetectionRow(vm: AppViewModel) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(autodetectEnabled(ctx)) }
    var access by remember { mutableStateOf(hasListenerAccess(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = hasListenerAccess(ctx) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-detect music", fontWeight = FontWeight.SemiBold)
                Text("Reads now-playing info from music apps that publish it (Spotify, YouTube Music, most players). Needs notification access.", fontSize = 12.sp, color = SumxColors.Muted)
            }
            Switch(on, { v ->
                on = v; setAutodetect(ctx, v)
                if (v && !hasListenerAccess(ctx)) openListenerSettings(ctx)
                if (!v && vm.feed.mine.listening?.source == "device") vm.clearListening()
            })
        }
        if (on) Text(if (access) "Notification access: granted ✓" else "Notification access needed — tap the switch again after enabling SUM X in the list.", fontSize = 12.sp, color = if (access) OnlineGreen else IdleAmber)
    }
}

@Composable
private fun ActivityDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf("playing") }; var name by remember { mutableStateOf("") }; var details by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SumxColors.Card, title = { Text("Set activity") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceRow("Type", listOf("playing" to "Playing", "watching" to "Watching", "building" to "Building", "custom" to "Custom", "using_sumx" to "Using SUM X"), type) { type = it }
            if (type != "using_sumx") Field(name, { name = it.take(60) }, if (type == "custom") "What are you up to?" else "Name (e.g. Free Fire)")
            Field(details, { details = it.take(100) }, "Details (optional)")
        } },
        confirmButton = { TextButton({ val n = if (type == "using_sumx") "SUM X" else name; if (n.isNotBlank()) { vm.setActivity(type, n, details); onDismiss() } }) { Text("Share", color = SumxColors.Cyan) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun ListeningDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var platform by remember { mutableStateOf("sumx_beats") }; var title by remember { mutableStateOf("") }; var artist by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SumxColors.Card, title = { Text("Set what you're listening to") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This is entered by you. Turn on auto-detect to read it from your music apps instead.", fontSize = 12.sp, color = SumxColors.Muted)
            ChoiceRow("Platform", listOf("sumx_beats" to "SUM X Beats", "spotify" to "Spotify", "youtube_music" to "YouTube Music", "local" to "Local music", "other" to "Other"), platform) { platform = it }
            Field(title, { title = it.take(120) }, "Song title"); Field(artist, { artist = it.take(120) }, "Artist (optional)")
        } },
        confirmButton = { TextButton({ if (title.isNotBlank()) { vm.setListening(platform, title, artist); onDismiss() } }) { Text("Share", color = SumxColors.Cyan) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

fun notifText(n: NotifItem): String {
    val who = n.actor?.displayName ?: "Someone"; val d = n.data
    fun s(k: String) = d?.get(k)?.jsonPrimitive?.contentOrNull ?: ""
    return when (n.type) {
        "friend_request" -> "$who sent you a friend request"; "friend_accepted" -> "$who accepted your friend request"
        "mention" -> "$who mentioned you: ${s("preview")}"; "reply" -> "$who replied to you: ${s("preview")}"
        "missed_call" -> "Missed ${s("kind").ifBlank { "voice" }} call from $who"; "activity" -> "$who · ${s("text")}"; else -> "New notification"
    }
}

@Composable
fun ActivityScreen(vm: AppViewModel, ask: (List<String>, () -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dlg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) { when (tab) { 0 -> vm.loadFeed(); 1 -> vm.loadNotifs(); else -> vm.loadCalls() } }
    Column(Modifier.fillMaxSize()) {
        Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
        TabRow(tab, containerColor = Color.Transparent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Activity") })
            Tab(tab == 1, { tab = 1 }, text = { Text(if (vm.notifs.unread > 0) "Alerts (${vm.notifs.unread})" else "Alerts") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Calls") })
        }
        when (tab) {
            0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = SumxColors.Card) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Your activity", fontWeight = FontWeight.Bold)
                            Text(vm.feed.mine.activity?.let { activityLine(it) } ?: "Not sharing an activity", color = SumxColors.Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ dlg = "activity" }) { Text("Set activity", color = SumxColors.Text) }
                                if (vm.feed.mine.activity != null) TextButton({ vm.clearActivity() }) { Text("Clear", color = DndRed) }
                            }
                            HorizontalDivider(color = SumxColors.Border)
                            Text(vm.feed.mine.listening?.let { listeningText(it) } ?: "Not sharing music", color = SumxColors.Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ dlg = "listening" }) { Text("Set listening", color = SumxColors.Text) }
                                if (vm.feed.mine.listening != null) TextButton({ vm.clearListening() }) { Text("Clear", color = DndRed) }
                            }
                            MusicDetectionRow(vm)
                            Text("Who can see this is controlled in Settings → Activity privacy.", fontSize = 12.sp, color = SumxColors.Muted)
                        }
                    }
                }
                item { Text("Active now", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                if (vm.feed.active.isEmpty()) item { Text("None of your friends are sharing an activity right now.", color = SumxColors.Muted, fontSize = 14.sp) }
                items(vm.feed.active, key = { it.user.id }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openProfile(f.user.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f.user.displayName, 44.dp, f.user.status, f.user.avatarUrl)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(f.user.displayName, fontWeight = FontWeight.SemiBold)
                            f.listening?.let { Text(listeningText(it), fontSize = 13.sp, color = SumxColors.Cyan, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            f.activity?.let { Text(activityLine(it), fontSize = 13.sp, color = SumxColors.Muted, maxLines = 1) }
                        }
                        f.listening?.artworkUrl?.let { NetImage(it, Modifier.size(44.dp).background(Color.Black, RoundedCornerShape(8.dp))) }
                    }
                }
                item { Text("Recently active", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                if (vm.feed.recent.isEmpty()) item { Text("No friends were active in the last 24 hours.", color = SumxColors.Muted, fontSize = 14.sp) }
                items(vm.feed.recent, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openProfile(p.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.displayName, 40.dp, "offline", p.avatarUrl)
                        Column(Modifier.padding(start = 12.dp)) { Text(p.displayName); Text("Active in the last day", fontSize = 12.sp, color = SumxColors.Muted) }
                    }
                }
            }
            1 -> Column(Modifier.fillMaxSize()) {
                if (vm.notifs.items.isEmpty()) Empty("No notifications yet.")
                else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton({ vm.markAllRead() }, enabled = vm.notifs.unread > 0) { Text("Mark all read", color = SumxColors.Cyan) } }
                    LazyColumn { items(vm.notifs.items, key = { it.id }) { n ->
                        Row(Modifier.fillMaxWidth().background(if (n.read) Color.Transparent else SumxColors.Blue.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(n.actor?.displayName ?: "S", 40.dp, null, n.actor?.avatarUrl)
                            Column(Modifier.padding(start = 12.dp)) { Text(notifText(n), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis); Text(dayTime(n.createdAt), fontSize = 12.sp, color = SumxColors.Muted) }
                        }
                    } }
                }
            }
            else -> if (vm.callHistory.isEmpty()) Empty("No calls yet. Start one from a chat or a friend's profile.")
                else LazyColumn { items(vm.callHistory, key = { it.id }) { c ->
                    val missed = c.status == "missed" || c.status == "declined" || c.status == "cancelled"
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { missed -> Icons.Filled.PhoneMissed; c.outgoing -> Icons.Filled.CallMade; else -> Icons.Filled.CallReceived }, null, tint = if (missed) DndRed else OnlineGreen)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(c.user.displayName, fontWeight = FontWeight.SemiBold)
                            Text("${if (c.outgoing) "Outgoing" else "Incoming"} ${c.kind} · ${if (missed) c.status else "%d:%02d".format(c.durationSec / 60, c.durationSec % 60)} · ${dayTime(c.createdAt)}", fontSize = 12.sp, color = SumxColors.Muted)
                        }
                        IconButton({ startCall(vm, ask, c.user, "voice") }) { Icon(Icons.Filled.Call, "Call back", tint = SumxColors.Cyan) }
                    }
                } }
        }
    }
    if (dlg == "activity") ActivityDialog(vm) { dlg = null }
    if (dlg == "listening") ListeningDialog(vm) { dlg = null }
}

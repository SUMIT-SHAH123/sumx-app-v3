package com.sumx.app.ui

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sumx.app.BuildConfig
import com.sumx.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private val EMOJIS = listOf("😀","😁","😂","🤣","😊","😍","😘","😎","🤔","😢","😭","😡","👍","👎","👏","🙏","💪","🔥","✨","🎉","💯","❤️","💙","💜","🖤","⭐","⚡","🎵","🎮","🍕","☕","🌙","🌈","🚀","👀","🙌","🤝","😴","🥳","😇","🤯","😅","😉","🙂","🙃","😋","😜","🤗","🤩","🥺","😤","😱","🤮","🤢","🫡","🫶","💀","🙈","🎧","📷")

@Composable
fun EmojiPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = SumxColors.Card, confirmButton = { TextButton(onDismiss) { Text("Close") } },
        text = { LazyVerticalGrid(GridCells.Fixed(7), Modifier.height(300.dp)) { gridItems(EMOJIS) { e ->
            Text(e, fontSize = 26.sp, modifier = Modifier.clickable { onPick(e) }.padding(6.dp)) } } })
}

@Composable
private fun GifPicker(vm: AppViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    LaunchedEffect(q) { delay(350); vm.searchGifs(q) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SumxColors.Card, confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text("GIFs") },
        text = { Column { Field(q, { q = it }, "Search GIFs")
            if (vm.gifs.isEmpty()) Text("No GIFs to show. (The server needs a TENOR_API_KEY.)", color = SumxColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(320.dp)) { gridItems(vm.gifs) { g ->
                NetImage(g.previewUrl, Modifier.padding(3.dp).height(100.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.sendGif(g); onDismiss() }) } } } })
}

@Composable
private fun VoicePlayer(att: Attachment) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val api = remember { Graph.api(ctx) }
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({
            val p = player
            if (playing) { p?.pause(); playing = false }
            else if (p != null) { p.start(); playing = true }
            else scope.launch {
                val token = runCatching { api.accessToken() }.getOrNull(); val mp = MediaPlayer()
                try {
                    mp.setDataSource(ctx, Uri.parse(BuildConfig.API_BASE_URL + "/files/${att.id}"), mapOf("Authorization" to "Bearer $token"))
                    mp.setOnPreparedListener { it.start(); playing = true }
                    mp.setOnCompletionListener { playing = false; it.seekTo(0) }
                    mp.prepareAsync(); player = mp
                } catch (e: Exception) { mp.release() }
            }
        }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play voice message") }
        Text("Voice message · ${(att.durationMs ?: 0) / 1000}s", fontSize = 13.sp)
    }
}

private fun download(ctx: Context, att: Attachment, token: String?) {
    val req = DownloadManager.Request(Uri.parse(BuildConfig.API_BASE_URL + "/files/${att.id}"))
        .addRequestHeader("Authorization", "Bearer $token").setTitle(att.name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, att.name)
    (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(vm: AppViewModel, m: Msg, mine: Boolean, meId: String, scale: Float, onLong: () -> Unit, onReact: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val api = remember { Graph.api(ctx) }
    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Surface(color = if (mine) SumxColors.Blue else SumxColors.Card, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = {}, onLongClick = { if (!m.deleted) onLong() })) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                m.replyTo?.let { rid ->
                    val q = vm.msgs.firstOrNull { it.id == rid }
                    Text("↩ " + (q?.body ?: if (q?.attachments?.isNotEmpty() == true || q?.gifUrl != null) "Attachment" else "Original message"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (m.deleted) Text("Message deleted", fontStyle = FontStyle.Italic, color = SumxColors.Muted, fontSize = (15 * scale).sp)
                else {
                    m.gifUrl?.let { NetImage(it, Modifier.size(200.dp, 150.dp).clip(RoundedCornerShape(10.dp))) }
                    m.attachments.forEach { a -> when (a.kind) {
                        "image" -> NetImage("/files/${a.id}", Modifier.size(220.dp).clip(RoundedCornerShape(10.dp)))
                        "voice" -> VoicePlayer(a)
                        else -> Row(Modifier.clickable { scope.launch { download(ctx, a, runCatching { api.accessToken() }.getOrNull()); vm.message = "Downloading ${a.name}…" } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.InsertDriveFile, null); Spacer(Modifier.width(8.dp))
                            Column { Text(a.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${a.size / 1024} KB · tap to download", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f)) } }
                    } }
                    if (!m.body.isNullOrBlank()) Text(m.body, fontSize = (15 * scale).sp)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (m.pinned) Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.7f))
                    Text(hhmm(m.createdAt) + if (m.editedAt != null) " · edited" else "", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
        if (m.reactions.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            m.reactions.forEach { r ->
                val iMine = meId in r.users
                Surface(shape = RoundedCornerShape(12.dp), color = if (iMine) SumxColors.Blue.copy(alpha = 0.35f) else SumxColors.Card,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.react(m, r.emoji) }) {
                    Text("${r.emoji} ${r.users.size}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, meId: String, ask: (List<String>, () -> Unit) -> Unit) {
    val c = vm.openConv ?: return
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var reactFor by remember { mutableStateOf<Msg?>(null) }
    var showGif by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder(ctx) }
    val scale = if (vm.settings.accessibility.largeText) 1.25f else 1f

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording) { recSecs = 0; while (recording) { delay(1000); recSecs++ } }
    LaunchedEffect(vm.editing) { vm.editing?.let { text = it.body ?: "" } }
    BackHandler { if (searching) { searching = false; query = ""; vm.clearSearch() } else vm.closeChat() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.sendAttachment(it, "image") } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.sendAttachment(it, "file") } }

    val lastMine = vm.msgs.firstOrNull { it.senderId == meId && !it.deleted }
    val seen = runCatching { vm.otherLastRead != null && lastMine != null && Instant.parse(vm.otherLastRead) >= Instant.parse(lastMine.createdAt) }.getOrDefault(false)
    val act = vm.feed.active.firstOrNull { it.user.id == c.user.id }?.let { activityText(it.activity, it.listening) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().background(SumxColors.Surface).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.closeChat() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Avatar(c.user.displayName, 38.dp, c.user.status, c.user.avatarUrl)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(c.user.displayName, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                Text(if (vm.typing) "typing…" else (act ?: statusLabel(c.user.status)), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (vm.typing || c.user.online) SumxColors.Cyan else SumxColors.Muted)
            }
            IconButton({ searching = !searching; if (!searching) { query = ""; vm.clearSearch() } }) { Icon(Icons.Filled.Search, "Search") }
            IconButton({ vm.showPins() }) { Icon(Icons.Filled.PushPin, "Pinned messages") }
            IconButton({ startCall(vm, ask, c.user, "voice") }) { Icon(Icons.Filled.Call, "Voice call") }
            IconButton({ startCall(vm, ask, c.user, "video") }) { Icon(Icons.Filled.Videocam, "Video call") }
        }
        if (searching) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(query, { query = it; vm.search(it) }, "Search this conversation") }
        }
        val results = vm.searchResults
        if (searching && results != null) {
            if (results.isEmpty()) Box(Modifier.weight(1f)) { Empty("No messages found.") }
            else LazyColumn(Modifier.weight(1f)) { items(results, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text((if (m.senderId == meId) "You" else c.user.displayName) + " · " + dayTime(m.createdAt), fontSize = 12.sp, color = SumxColors.Muted)
                    Text(m.body ?: "", fontSize = (15 * scale).sp) } } }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.msgs.isEmpty()) item { Empty("Say hi to ${c.user.displayName}!") }
                items(vm.msgs, key = { it.id }) { m ->
                    val mine = m.senderId == meId
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Box {
                            Bubble(vm, m, mine, meId, scale, onLong = { menuFor = m.id }, onReact = { reactFor = m })
                            DropdownMenu(menuFor == m.id, { menuFor = null }) {
                                DropdownMenuItem({ Text("Reply") }, { menuFor = null; vm.editing = null; vm.replyTo = m })
                                DropdownMenuItem({ Text("React") }, { menuFor = null; reactFor = m })
                                if (!m.body.isNullOrBlank()) DropdownMenuItem({ Text("Copy text") }, { menuFor = null; clip.setText(AnnotatedString(m.body)) })
                                DropdownMenuItem({ Text(if (m.pinned) "Unpin" else "Pin") }, { menuFor = null; vm.pin(m) })
                                if (mine && !m.body.isNullOrBlank() && m.attachments.isEmpty()) DropdownMenuItem({ Text("Edit") }, { menuFor = null; vm.replyTo = null; vm.editing = m })
                                if (mine) DropdownMenuItem({ Text("Delete") }, { menuFor = null; vm.deleteMessage(m) })
                            }
                        }
                        if (mine && m.id == lastMine?.id && seen) Text("Seen", fontSize = 11.sp, color = SumxColors.Cyan, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
                    }
                }
                if (vm.hasMore) item { TextButton({ vm.loadOlder() }, Modifier.fillMaxWidth()) { Text("Load older messages", color = SumxColors.Cyan) } }
            }
        }
        (vm.editing ?: vm.replyTo)?.let { b ->
            Row(Modifier.fillMaxWidth().background(SumxColors.Surface).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (vm.editing != null) "Editing: " else "Replying to: ") + (b.body ?: "attachment"), color = SumxColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton({ if (vm.editing != null) text = ""; vm.editing = null; vm.replyTo = null }) { Icon(Icons.Filled.Close, "Cancel") }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SumxColors.Cyan)
        if (recording) Row(Modifier.fillMaxWidth().background(SumxColors.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(DndRed))
            Text("  Recording ${recSecs / 60}:%02d".format(recSecs % 60), Modifier.weight(1f))
            IconButton({ recorder.cancel(); recording = false }) { Icon(Icons.Filled.Delete, "Discard") }
            FilledIconButton({ recording = false; recorder.stop()?.let { (f, d) -> vm.sendVoice(f, d) } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SumxColors.Blue)) { Icon(Icons.AutoMirrored.Filled.Send, "Send voice message") }
        } else Row(Modifier.fillMaxWidth().background(SumxColors.Surface).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton({ attachMenu = true }) { Icon(Icons.Filled.AddCircleOutline, "Attach") }
                DropdownMenu(attachMenu, { attachMenu = false }) {
                    DropdownMenuItem({ Text("Photo") }, { attachMenu = false; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    DropdownMenuItem({ Text("File") }, { attachMenu = false; filePicker.launch("*/*") })
                    DropdownMenuItem({ Text("GIF") }, { attachMenu = false; showGif = true })
                }
            }
            IconButton({ showEmoji = true }) { Icon(Icons.Filled.EmojiEmotions, "Emoji") }
            OutlinedTextField(text, { text = it; vm.onTyping() }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SumxColors.Cyan, unfocusedBorderColor = SumxColors.Border, cursorColor = SumxColors.Cyan))
            if (text.isBlank()) IconButton({ ask(listOf(Manifest.permission.RECORD_AUDIO)) { recording = recorder.start() } }, Modifier.size(52.dp)) { Icon(Icons.Filled.Mic, "Record voice message", tint = SumxColors.Cyan) }
            else FilledIconButton({ vm.send(text); text = "" }, Modifier.padding(start = 4.dp).size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = SumxColors.Blue)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }

    if (showEmoji) EmojiPicker({ text += it; showEmoji = false }, { showEmoji = false })
    reactFor?.let { m -> EmojiPicker({ vm.react(m, it); reactFor = null }, { reactFor = null }) }
    if (showGif) GifPicker(vm) { showGif = false }
    vm.pins?.let { pins ->
        AlertDialog(onDismissRequest = { vm.hidePins() }, containerColor = SumxColors.Card, title = { Text("Pinned messages") },
            confirmButton = { TextButton({ vm.hidePins() }) { Text("Close") } },
            text = { if (pins.isEmpty()) Text("Nothing pinned yet. Long-press a message and choose Pin.", color = SumxColors.Muted)
                else LazyColumn(Modifier.heightIn(max = 360.dp)) { items(pins, key = { it.id }) { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text((if (m.senderId == meId) "You" else c.user.displayName) + " · " + dayTime(m.createdAt), fontSize = 11.sp, color = SumxColors.Muted)
                            Text(m.body ?: if (m.gifUrl != null) "GIF" else "Attachment", maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        TextButton({ vm.pin(m) }) { Text("Unpin", color = SumxColors.Cyan) } } } } })
    }
}

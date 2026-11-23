package com.sumx.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sumx.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val api = Graph.api(app)
    private val rt = Realtime(api, viewModelScope)
    val calls = CallManager(app, api, rt, viewModelScope)
    private var meId = ""
    private var started = false
    private var collectJob: Job? = null
    private var typingJob: Job? = null
    private var lastTypingSent = 0L

    var friends by mutableStateOf<List<Person>>(emptyList()); private set
    var requests by mutableStateOf(Requests()); private set
    var blocked by mutableStateOf<List<Person>>(emptyList()); private set
    var convs by mutableStateOf<List<Conv>>(emptyList()); private set
    var feed by mutableStateOf(Feed()); private set
    var profile by mutableStateOf<Profile?>(null); private set
    var settings by mutableStateOf(Settings()); private set
    var notifs by mutableStateOf(Notifs()); private set
    var callHistory by mutableStateOf<List<CallRecord>>(emptyList()); private set
    var sessions by mutableStateOf<List<SessionInfo>>(emptyList()); private set
    var viewing by mutableStateOf<Profile?>(null); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var editingProfile by mutableStateOf(false)
    var sessionEnded by mutableStateOf(false); private set

    // open chat (msgs are newest-first)
    var openConv by mutableStateOf<Conv?>(null); private set
    var msgs by mutableStateOf<List<Msg>>(emptyList()); private set
    var otherLastRead by mutableStateOf<String?>(null); private set
    var hasMore by mutableStateOf(false); private set
    var typing by mutableStateOf(false); private set
    var replyTo by mutableStateOf<Msg?>(null)
    var editing by mutableStateOf<Msg?>(null)
    var pins by mutableStateOf<List<Msg>?>(null); private set
    var searchResults by mutableStateOf<List<Msg>?>(null); private set
    var gifs by mutableStateOf<List<Gif>>(emptyList()); private set

    fun start(id: String) {
        if (started && meId == id) return
        stop(); meId = id; started = true
        rt.start()
        collectJob = viewModelScope.launch { rt.events.collect { (t, d) -> onEvent(t, d) } }
        loadAll()
    }
    fun stop() {
        collectJob?.cancel(); rt.stop(); started = false
        friends = emptyList(); requests = Requests(); blocked = emptyList(); convs = emptyList(); feed = Feed(); profile = null; settings = Settings()
        notifs = Notifs(); callHistory = emptyList(); sessions = emptyList(); viewing = null
        openConv = null; msgs = emptyList(); loaded = false; sessionEnded = false; message = null
    }
    override fun onCleared() { rt.stop() }

    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: ApiException) {
            if (e.code == "SESSION_ENDED") sessionEnded = true else if (e.code != "CANCELLED") message = e.message
        }
    }

    fun loadAll() = launchSafe {
        friends = api.friends(); requests = api.requests(); blocked = api.blocked(); convs = api.conversations()
        settings = api.settings(); calls.settings = settings
        feed = api.feed(); profile = api.myProfile(); notifs = api.notifications(); loaded = true
    }
    private fun loadSocial() = launchSafe { friends = api.friends(); requests = api.requests(); blocked = api.blocked(); convs = api.conversations(); feed = api.feed(); profile = api.myProfile() }
    private fun loadConvs() = launchSafe { convs = api.conversations() }
    fun loadFeed() = launchSafe { feed = api.feed() }
    fun loadNotifs() = launchSafe { notifs = api.notifications() }
    fun loadCalls() = launchSafe { callHistory = api.callHistory() }
    fun loadSessions() = launchSafe { sessions = api.sessions() }
    fun markAllRead() = launchSafe { api.readAllNotifications(); notifs = api.notifications() }
    fun revokeSession(s: SessionInfo) = launchSafe { api.revokeSession(s.id); sessions = api.sessions() }

    // ---------- realtime ----------
    private fun replaceMsg(m: Msg) { msgs = msgs.map { if (it.id == m.id) m else it }; pins = pins?.let { l -> if (m.pinned) (if (l.any { it.id == m.id }) l.map { x -> if (x.id == m.id) m else x } else listOf(m) + l) else l.filter { it.id != m.id } } }
    private fun addMsg(m: Msg) { if (msgs.none { it.id == m.id }) msgs = listOf(m) + msgs }

    private fun preview(m: Msg) = m.body?.takeIf { it.isNotBlank() } ?: when {
        m.gifUrl != null -> "GIF"; m.attachments.firstOrNull()?.kind == "image" -> "📷 Photo"
        m.attachments.firstOrNull()?.kind == "voice" -> "🎤 Voice message"; m.attachments.isNotEmpty() -> "📎 File"; else -> "New message" }

    private fun onEvent(type: String, d: JsonObject) {
        if (calls.onEvent(type, d)) return
        when (type) {
            "reconnected" -> { loadAll(); openConv?.let { c -> launchSafe { val p = api.messages(c.id); msgs = p.messages; otherLastRead = p.otherLastReadAt } } }
            "friend.update", "friend.request" -> loadSocial()
            "activity.update" -> loadFeed()
            "presence" -> {
                val id = d["userId"]?.jsonPrimitive?.content ?: return
                val st = d["status"]?.jsonPrimitive?.content ?: return
                friends = friends.map { if (it.id == id) it.copy(status = st, online = st != "offline") else it }
                convs = convs.map { if (it.user.id == id) it.copy(user = it.user.copy(status = st, online = st != "offline")) else it }
                openConv?.let { if (it.user.id == id) openConv = it.copy(user = it.user.copy(status = st, online = st != "offline")) }
            }
            "message.new" -> {
                val m = api.json.decodeFromJsonElement<Msg>(d)
                if (openConv?.id == m.conversationId) { addMsg(m); if (m.senderId != meId) { typing = false; markRead() } }
                else if (m.senderId != meId) {
                    val c = convs.firstOrNull { it.id == m.conversationId }
                    if (c?.muted != true && settings.notify.messages && settings.status != "dnd")
                        Notifier.show(app, m.conversationId.hashCode(), c?.user?.displayName ?: "New message", preview(m))
                }
                loadConvs()
            }
            "message.edit", "message.delete", "message.reaction", "message.pin" -> { replaceMsg(api.json.decodeFromJsonElement(d)); if (type != "message.reaction") loadConvs() }
            "message.read" -> if (d["conversationId"]?.jsonPrimitive?.content == openConv?.id && d["userId"]?.jsonPrimitive?.content != meId)
                otherLastRead = d["at"]?.jsonPrimitive?.content
            "typing" -> if (d["conversationId"]?.jsonPrimitive?.content == openConv?.id) {
                typing = true; typingJob?.cancel(); typingJob = viewModelScope.launch { delay(3000); typing = false }
            }
            "notification" -> {
                loadNotifs()
                if (d["silent"]?.jsonPrimitive?.booleanOrNull != true) {
                    val actor = friends.firstOrNull { it.id == d["actorId"]?.jsonPrimitive?.contentOrNull }?.displayName ?: "Someone"
                    val data = d["data"] as? JsonObject
                    Notifier.show(app, d["id"].hashCode(), "SUM X", when (d["type"]?.jsonPrimitive?.content) {
                        "friend_request" -> "$actor sent you a friend request"; "friend_accepted" -> "$actor accepted your friend request"
                        "mention" -> "$actor mentioned you: ${data?.get("preview")?.jsonPrimitive?.contentOrNull ?: ""}"; "reply" -> "$actor replied: ${data?.get("preview")?.jsonPrimitive?.contentOrNull ?: ""}"
                        "missed_call" -> "Missed call from $actor"; "activity" -> "$actor: ${data?.get("text")?.jsonPrimitive?.contentOrNull ?: ""}"; else -> "New notification" })
                }
            }
        }
    }

    // ---------- chat ----------
    fun openChat(c: Conv) {
        openConv = c; msgs = emptyList(); otherLastRead = null; replyTo = null; editing = null; hasMore = false; typing = false; pins = null; searchResults = null
        launchSafe { val p = api.messages(c.id); msgs = p.messages; otherLastRead = p.otherLastReadAt; hasMore = p.messages.size >= 50; markReadNow() }
    }
    fun openWith(p: Person) = launchSafe {
        val id = api.openConversation(p.id); val list = api.conversations(); convs = list
        openChat(list.firstOrNull { it.id == id } ?: Conv(id, p))
    }
    fun closeChat() { openConv = null; msgs = emptyList(); replyTo = null; editing = null; pins = null; searchResults = null; loadConvs() }
    private suspend fun markReadNow() { val c = openConv ?: return; api.markRead(c.id); convs = convs.map { if (it.id == c.id) it.copy(unread = 0) else it } }
    private fun markRead() = launchSafe { markReadNow() }
    fun loadOlder() { val c = openConv ?: return; val last = msgs.lastOrNull() ?: return
        launchSafe { val p = api.messages(c.id, last.createdAt); msgs = msgs + p.messages.filter { n -> msgs.none { it.id == n.id } }; hasMore = p.messages.size >= 50 } }
    fun send(text: String) {
        val c = openConv ?: return; val t = text.trim(); if (t.isEmpty()) return
        val ed = editing; val rep = replyTo
        launchSafe {
            if (ed != null) { val m = api.editMessage(ed.id, t); replaceMsg(m); editing = null }
            else { addMsg(api.sendMessage(c.id, t, rep?.id)); replyTo = null }
        }
    }
    fun onTyping() { val c = openConv ?: return; val now = System.currentTimeMillis(); if (now - lastTypingSent > 2000) { lastTypingSent = now; rt.typing(c.id) } }
    fun deleteMessage(m: Msg) = launchSafe { replaceMsg(api.deleteMessage(m.id)) }
    fun react(m: Msg, emoji: String) = launchSafe { replaceMsg(api.react(m.id, emoji)) }
    fun pin(m: Msg) = launchSafe { replaceMsg(api.pin(m.id)); message = if (m.pinned) "Unpinned." else "Pinned." }
    fun showPins() { val c = openConv ?: return; launchSafe { pins = api.pins(c.id) } }
    fun hidePins() { pins = null }
    fun search(q: String) { val c = openConv ?: return; if (q.isBlank()) { searchResults = null; return }; launchSafe { searchResults = api.search(c.id, q.trim()) } }
    fun clearSearch() { searchResults = null }
    fun mute(c: Conv) = launchSafe { api.mute(c.id, !c.muted); convs = api.conversations() }
    fun hide(c: Conv) = launchSafe { api.hide(c.id); convs = api.conversations() }
    fun searchGifs(q: String) = launchSafe { gifs = api.gifs(q) }
    fun sendGif(g: Gif) { val c = openConv ?: return; val rep = replyTo; launchSafe { addMsg(api.sendGif(c.id, g.url, rep?.id)); replyTo = null } }

    private data class Picked(val bytes: ByteArray, val name: String, val mime: String)
    private suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        var name = "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw ApiException("READ", "Couldn't read that file.")
        if (bytes.size > 15 * 1024 * 1024) throw ApiException("TOO_BIG", "That file is too large (15 MB max).")
        Picked(bytes, name, cr.getType(uri) ?: "application/octet-stream")
    }
    fun sendAttachment(uri: Uri, kind: String) { val c = openConv ?: return; val rep = replyTo
        launchSafe { busy = true; try { val f = read(uri); addMsg(api.sendAttachment(c.id, kind, f.bytes, f.name, f.mime, null, rep?.id, null)); replyTo = null } finally { busy = false } } }
    fun sendVoice(file: File, durationMs: Int) { val c = openConv ?: return; val rep = replyTo
        launchSafe { busy = true; try { val b = withContext(Dispatchers.IO) { file.readBytes().also { file.delete() } }
            addMsg(api.sendAttachment(c.id, "voice", b, "voice.m4a", "audio/mp4", null, rep?.id, durationMs)); replyTo = null } finally { busy = false } } }

    // ---------- friends ----------
    fun sendRequest(id: String) = launchSafe { val r = api.sendRequest(id.trim()); message = if ("friends" in r) "You're now friends!" else "Friend request sent."; loadSocial() }
    fun requestAction(r: Req, action: String) = launchSafe { api.requestAction(r.id, action); loadSocial() }
    fun removeFriend(p: Person) = launchSafe { api.removeFriend(p.id); message = "Removed ${p.displayName}."; viewing = null; loadSocial() }
    fun block(p: Person) = launchSafe { api.block(p.id); message = "Blocked ${p.displayName}."; viewing = null; loadSocial() }
    fun unblock(p: Person) = launchSafe { api.unblock(p.id); message = "Unblocked ${p.displayName}."; loadSocial() }
    fun openProfile(id: String) = launchSafe { viewing = api.profile(id) }
    fun closeProfile() { viewing = null }

    // ---------- profile / activity / settings ----------
    fun saveProfile(fields: JsonObject, uploads: Map<String, Uri>, removals: Set<String>, onDone: () -> Unit) = launchSafe {
        busy = true
        try {
            if (fields.isNotEmpty()) profile = api.patchProfile(fields)
            for ((slot, uri) in uploads) { val f = read(uri); profile = api.uploadProfileImage(slot, f.bytes, f.mime) }
            for (slot in removals) if (slot !in uploads) profile = api.removeProfileImage(slot)
            message = "Profile saved."; onDone()
        } finally { busy = false }
    }
    fun setActivity(type: String, name: String, details: String?) = launchSafe { api.setActivity(type, name.trim(), details?.trim()?.ifBlank { null }); feed = api.feed(); message = "Activity updated." }
    fun clearActivity() = launchSafe { api.clearActivity(); feed = api.feed() }
    fun setListening(platform: String, title: String, artist: String?) = launchSafe { api.setListening(platform, title.trim(), artist?.trim()?.ifBlank { null }, null, null, "manual"); feed = api.feed(); message = "Listening activity updated." }
    fun clearListening() = launchSafe { api.clearListening(); feed = api.feed() }
    fun saveSettings(s: Settings) { settings = s; calls.settings = s; launchSafe { settings = api.saveSettings(s); calls.settings = settings; feed = api.feed() } }
    fun changeUsername(u: String, onDone: () -> Unit) = launchSafe { api.changeUsername(u.trim().lowercase()); message = "Username updated."; loadSocial(); onDone() }
    fun deleteAccount(password: String?, idToken: String?, onDone: () -> Unit) = launchSafe { api.deleteAccount(password, idToken); onDone() }
}

package com.sumx.app.data

import com.sumx.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable data class User(val id: String, val email: String, val emailVerified: Boolean, val username: String, val sumxId: String, val displayName: String, val createdAt: String? = null)
@Serializable data class Session(val accessToken: String, val refreshToken: String, val user: User)
@Serializable data class Tokens(val accessToken: String, val refreshToken: String)
@Serializable data class MeResponse(val user: User)
@Serializable data class Methods(val password: Boolean, val google: String? = null)

class ApiException(val code: String, override val message: String) : Exception(message)

class Api(private val tokens: TokenStore) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private suspend fun raw(method: String, path: String, body: JsonObject?, bearer: String?): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BuildConfig.API_BASE_URL + path)
            .method(method, if (method == "GET") null else (body?.toString() ?: "{}").toRequestBody(jsonType))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val e = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
                    val code = e?.get("code")?.jsonPrimitive?.content ?: "HTTP_${r.code}"
                    val msg = if (bearer == null && code == "UNAUTHENTICATED")
                        "Can't reach the SUM X API at this address. Check API_BASE_URL in local.properties (no trailing slash) and rebuild."
                        else e?.get("message")?.jsonPrimitive?.content ?: "Request failed (${r.code})."
                    throw ApiException(code, msg)
                }
                text
            }
        } catch (e: java.io.IOException) { throw ApiException("NETWORK", "Can't reach SUM X. Check your connection.") }
    }

    private fun obj(vararg p: Pair<String, Any?>) = buildJsonObject {
        p.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); is Number -> put(k, v); null -> {}; else -> put(k, v.toString()) } }
    }

    // ---- public auth calls ----
    private fun save(s: Session): User { tokens.access = s.accessToken; tokens.refresh = s.refreshToken; return s.user }
    private suspend fun session(path: String, body: JsonObject) = save(json.decodeFromString(raw("POST", path, body, null)))

    /** Returns the signed-in user when the server verified the account immediately, or null when an email code is required. */
    suspend fun register(displayName: String, username: String, email: String, password: String, dob: String): User? {
        val text = raw("POST", "/auth/register", obj("displayName" to displayName, "username" to username, "email" to email,
            "password" to password, "dob" to dob, "acceptTerms" to true), null)
        return if ("accessToken" in json.parseToJsonElement(text).jsonObject) save(json.decodeFromString<Session>(text)) else null
    }
    suspend fun verifyEmail(email: String, code: String) = session("/auth/verify-email", obj("email" to email, "code" to code, "rememberMe" to true))
    suspend fun resendVerification(email: String) = raw("POST", "/auth/resend-verification", obj("email" to email), null)
    suspend fun login(identifier: String, password: String, remember: Boolean) =
        session("/auth/login", obj("identifier" to identifier, "password" to password, "rememberMe" to remember))
    suspend fun google(idToken: String) = session("/auth/google", obj("idToken" to idToken, "rememberMe" to true))
    suspend fun forgot(email: String) = raw("POST", "/auth/forgot-password", obj("email" to email), null)
    suspend fun reset(email: String, code: String, newPassword: String) =
        raw("POST", "/auth/reset-password", obj("email" to email, "code" to code, "newPassword" to newPassword), null)

    // ---- authenticated calls (auto-refresh once on 401; refreshes are serialized so rotation can't race) ----
    private val refreshLock = Mutex()
    private suspend fun authed(method: String, path: String, body: JsonObject? = null): String {
        val used = tokens.access
        try { return raw(method, path, body, used) }
        catch (e: ApiException) {
            if (e.code == "SESSION_ENDED") { tokens.clear(); throw e }
            if (e.code != "UNAUTHENTICATED" || !refreshTokens(used)) throw e
            return raw(method, path, body, tokens.access)
        }
    }
    private suspend fun refreshTokens(stale: String?): Boolean = refreshLock.withLock {
        val r = tokens.refresh ?: return@withLock false
        if (stale != null && tokens.access != stale) return@withLock true // someone else already refreshed
        try {
            val t: Tokens = json.decodeFromString(raw("POST", "/auth/refresh", obj("refreshToken" to r), null))
            tokens.access = t.accessToken; tokens.refresh = t.refreshToken; true
        } catch (e: ApiException) { if (e.code == "SESSION_ENDED") tokens.clear(); false }
    }
    suspend fun accessToken(): String? { refreshTokens(null); return tokens.access }

    fun currentAccess() = tokens.access
    fun hasSession() = tokens.refresh != null
    suspend fun me(): User = json.decodeFromString<MeResponse>(authed("GET", "/me")).user
    suspend fun methods(): Methods = json.decodeFromString(authed("GET", "/auth/methods"))
    suspend fun linkGoogle(idToken: String) = authed("POST", "/auth/link/google", obj("idToken" to idToken))
    suspend fun unlinkGoogle() = authed("DELETE", "/auth/link/google")
    suspend fun logout() { runCatching { authed("POST", "/auth/logout") }; tokens.clear() }
    suspend fun logoutAll() { runCatching { authed("POST", "/auth/logout-all") }; tokens.clear() }

    // ---- uploads ----
    private fun errorOf(text: String, code: Int): ApiException {
        val e = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
        return ApiException(e?.get("code")?.jsonPrimitive?.content ?: "HTTP_$code", e?.get("message")?.jsonPrimitive?.content ?: "Request failed ($code).")
    }
    private suspend fun upload(path: String, fields: Map<String, String>, fileName: String, mime: String, bytes: ByteArray): String {
        suspend fun once(token: String?): String = withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (k, v) -> addFormDataPart(k, v) }
                addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            }.build()
            val req = Request.Builder().url(BuildConfig.API_BASE_URL + path).post(body).apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            try { http.newCall(req).execute().use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw errorOf(t, r.code); t } }
            catch (e: java.io.IOException) { throw ApiException("NETWORK", "Can't reach SUM X. Check your connection.") }
        }
        val used = tokens.access
        return try { once(used) } catch (e: ApiException) {
            if (e.code == "SESSION_ENDED") { tokens.clear(); throw e }
            if (e.code != "UNAUTHENTICATED" || !refreshTokens(used)) throw e
            once(tokens.access)
        }
    }

    // ---- profile / activity / settings ----
    suspend fun myProfile(): Profile = json.decodeFromString(authed("GET", "/profile/me"))
    suspend fun profile(id: String): Profile = json.decodeFromString(authed("GET", "/users/$id/profile"))
    suspend fun patchProfile(fields: JsonObject): Profile = json.decodeFromString(authed("PATCH", "/profile", fields))
    suspend fun uploadProfileImage(slot: String, bytes: ByteArray, mime: String): Profile = json.decodeFromString(upload("/profile/$slot", emptyMap(), "$slot.img", mime, bytes))
    suspend fun removeProfileImage(slot: String): Profile = json.decodeFromString(authed("DELETE", "/profile/$slot"))
    suspend fun setActivity(type: String, name: String, details: String?) = authed("PUT", "/activity", obj("type" to type, "name" to name, "details" to details))
    suspend fun clearActivity() = authed("DELETE", "/activity")
    suspend fun setListening(platform: String, title: String, artist: String?, artworkUrl: String?, durationSec: Int?, source: String) =
        authed("PUT", "/activity/listening", obj("platform" to platform, "title" to title, "artist" to artist, "artworkUrl" to artworkUrl, "durationSec" to durationSec, "source" to source))
    suspend fun clearListening() = authed("DELETE", "/activity/listening")
    suspend fun feed(): Feed = json.decodeFromString(authed("GET", "/activity/feed"))
    suspend fun settings(): Settings = json.decodeFromString(authed("GET", "/settings"))
    suspend fun saveSettings(s: Settings): Settings = json.decodeFromString(authed("PUT", "/settings", json.encodeToJsonElement(s).jsonObject))
    suspend fun notifications(): Notifs = json.decodeFromString(authed("GET", "/notifications"))
    suspend fun readAllNotifications() = authed("POST", "/notifications/read-all")
    suspend fun sessions(): List<SessionInfo> = json.decodeFromString(authed("GET", "/sessions"))
    suspend fun revokeSession(id: String) = authed("DELETE", "/sessions/$id")
    suspend fun changeUsername(u: String) = authed("POST", "/account/username", obj("username" to u))
    suspend fun deleteAccount(password: String?, idToken: String?) { authed("DELETE", "/account", obj("password" to password, "idToken" to idToken)); tokens.clear() }
    suspend fun iceServers(): IceConfig = json.decodeFromString(authed("GET", "/calls/ice-servers"))
    suspend fun callHistory(): List<CallRecord> = json.decodeFromString(authed("GET", "/calls"))

    // ---- rich messaging ----
    suspend fun sendAttachment(convId: String, kind: String, bytes: ByteArray, name: String, mime: String, body: String?, replyTo: String?, durationMs: Int?): Msg {
        val f = mutableMapOf("kind" to kind)
        if (!body.isNullOrBlank()) f["body"] = body
        if (replyTo != null) f["replyTo"] = replyTo
        if (durationMs != null) f["durationMs"] = durationMs.toString()
        return json.decodeFromString(upload("/conversations/$convId/attachments", f, name, mime, bytes))
    }
    suspend fun sendGif(convId: String, url: String, replyTo: String?): Msg = json.decodeFromString(authed("POST", "/conversations/$convId/messages", obj("gifUrl" to url, "replyTo" to replyTo)))
    suspend fun react(msgId: String, emoji: String): Msg = json.decodeFromString(authed("POST", "/messages/$msgId/reactions", obj("emoji" to emoji)))
    suspend fun pin(msgId: String): Msg = json.decodeFromString(authed("POST", "/messages/$msgId/pin"))
    suspend fun pins(convId: String): List<Msg> = json.decodeFromString(authed("GET", "/conversations/$convId/pins"))
    suspend fun search(convId: String, q: String): List<Msg> = json.decodeFromString(authed("GET", "/conversations/$convId/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")))
    suspend fun gifs(q: String): List<Gif> = json.decodeFromString(authed("GET", "/gifs/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")))

    // ---- friends ----
    suspend fun friends(): List<Person> = json.decodeFromString(authed("GET", "/friends"))
    suspend fun requests(): Requests = json.decodeFromString(authed("GET", "/friends/requests"))
    suspend fun blocked(): List<Person> = json.decodeFromString(authed("GET", "/friends/blocked"))
    suspend fun sendRequest(sumxId: String) = authed("POST", "/friends/request", obj("sumxId" to sumxId))
    suspend fun requestAction(id: String, action: String) = authed("POST", "/friends/requests/$id/$action")
    suspend fun removeFriend(userId: String) = authed("DELETE", "/friends/$userId")
    suspend fun block(userId: String) = authed("POST", "/blocks/$userId")
    suspend fun unblock(userId: String) = authed("DELETE", "/blocks/$userId")

    // ---- messaging ----
    suspend fun conversations(): List<Conv> = json.decodeFromString(authed("GET", "/conversations"))
    suspend fun openConversation(userId: String): String = json.decodeFromString<IdOnly>(authed("POST", "/conversations", obj("userId" to userId))).id
    suspend fun messages(convId: String, before: String? = null): MsgPage =
        json.decodeFromString(authed("GET", "/conversations/$convId/messages" + (before?.let { "?before=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")))
    suspend fun sendMessage(convId: String, body: String, replyTo: String?): Msg =
        json.decodeFromString(authed("POST", "/conversations/$convId/messages", obj("body" to body, "replyTo" to replyTo)))
    suspend fun editMessage(id: String, body: String): Msg = json.decodeFromString(authed("PATCH", "/messages/$id", obj("body" to body)))
    suspend fun deleteMessage(id: String): Msg = json.decodeFromString(authed("DELETE", "/messages/$id"))
    suspend fun markRead(convId: String) = authed("POST", "/conversations/$convId/read")
    suspend fun mute(convId: String, muted: Boolean) = authed("POST", "/conversations/$convId/mute", obj("muted" to muted))
    suspend fun hide(convId: String) = authed("POST", "/conversations/$convId/hide")
}

@Serializable data class Req(val id: String, val user: Person)
@Serializable data class Requests(val incoming: List<Req> = emptyList(), val outgoing: List<Req> = emptyList())
@Serializable data class Conv(val id: String, val user: Person, val last: LastMsg? = null, val unread: Int = 0, val muted: Boolean = false)
@Serializable data class MsgPage(val messages: List<Msg> = emptyList(), val otherLastReadAt: String? = null)
@Serializable data class IdOnly(val id: String)

@Serializable data class Person(val id: String, val displayName: String, val sumxId: String, val username: String, val avatarUrl: String? = null, val accentColor: String? = null, val status: String = "offline", val online: Boolean = false, val lastSeen: String? = null)
@Serializable data class LastMsg(val id: String, val senderId: String, val body: String? = null, val createdAt: String, val deleted: Boolean = false, val kind: String? = null)
@Serializable data class Attachment(val id: String, val kind: String, val mime: String, val name: String, val size: Long = 0, val durationMs: Int? = null)
@Serializable data class Reaction(val emoji: String, val users: List<String> = emptyList())
@Serializable data class Msg(val id: String, val conversationId: String, val senderId: String, val body: String? = null, val replyTo: String? = null, val createdAt: String, val editedAt: String? = null,
    val deleted: Boolean = false, val pinned: Boolean = false, val gifUrl: String? = null, val attachments: List<Attachment> = emptyList(), val reactions: List<Reaction> = emptyList())
@Serializable data class Badge(val id: String, val label: String)
@Serializable data class ActivityInfo(val type: String, val name: String, val details: String? = null, val startedAt: String? = null)
@Serializable data class ListeningInfo(val platform: String? = null, val title: String? = null, val artist: String? = null, val artworkUrl: String? = null, val startedAt: String? = null, val durationSec: Int? = null, val source: String? = null)
@Serializable data class Profile(
    val id: String, val displayName: String, val sumxId: String, val username: String, val createdAt: String? = null,
    val avatarUrl: String? = null, val bannerUrl: String? = null, val backgroundUrl: String? = null,
    val bannerColor: String = "#006BFF", val accentColor: String = "#00D9FF", val bannerPosition: Int = 50,
    val about: String = "", val pronouns: String? = null, val customStatus: String? = null,
    val frame: String = "none", val decoration: String = "none", val effect: String = "none", val nameStyle: String = "plain", val theme: String = "midnight",
    val badges: List<Badge> = emptyList(), val isFriend: Boolean = false, val status: String? = null,
    val activity: ActivityInfo? = null, val listening: ListeningInfo? = null, val mutualFriends: Int = 0)
@Serializable data class FeedItem(val user: Person, val activity: ActivityInfo? = null, val listening: ListeningInfo? = null)
@Serializable data class Mine(val activity: ActivityInfo? = null, val listening: ListeningInfo? = null)
@Serializable data class Feed(val mine: Mine = Mine(), val active: List<FeedItem> = emptyList(), val recent: List<Person> = emptyList())
@Serializable data class NotifItem(val id: String, val type: String, val read: Boolean, val createdAt: String, val actor: Person? = null, val data: JsonObject? = null)
@Serializable data class Notifs(val unread: Int = 0, val items: List<NotifItem> = emptyList())
@Serializable data class SessionInfo(val id: String, val device: String? = null, val createdAt: String, val lastUsedAt: String, val current: Boolean = false)
@Serializable data class CallRecord(val id: String, val kind: String, val status: String, val outgoing: Boolean, val createdAt: String, val user: Person, val durationSec: Int = 0)
@Serializable data class Gif(val id: String, val previewUrl: String, val url: String)
@Serializable data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)
@Serializable data class IceConfig(val iceServers: List<IceServer> = emptyList())
@Serializable data class ListeningShow(val title: Boolean = true, val artist: Boolean = true, val artwork: Boolean = true, val platform: Boolean = true, val duration: Boolean = true)
@Serializable data class NotifyPrefs(val messages: Boolean = true, val friendRequests: Boolean = true, val calls: Boolean = true, val mentions: Boolean = true, val activity: Boolean = false)
@Serializable data class Access(val reduceMotion: Boolean = false, val largeText: Boolean = false)
@Serializable data class VoicePrefs(val noiseSuppression: Boolean = true, val echoCancellation: Boolean = true, val startVideoOff: Boolean = false)
@Serializable data class Settings(val status: String = "online", val activityVisibility: String = "friends", val listeningVisibility: String = "friends",
    val listeningShow: ListeningShow = ListeningShow(), val notify: NotifyPrefs = NotifyPrefs(), val accessibility: Access = Access(), val voice: VoicePrefs = VoicePrefs())

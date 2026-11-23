package com.sumx.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sumx.app.BuildConfig
import com.sumx.app.data.*
import kotlinx.coroutines.launch

enum class Screen { Splash, Login, Register, Verify, Forgot, Reset, Home }

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val api = Graph.api(app)
    var screen by mutableStateOf(Screen.Splash); private set
    var user by mutableStateOf<User?>(null); private set
    var methods by mutableStateOf<Methods?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<String?>(null); private set
    var pendingEmail by mutableStateOf(""); private set

    init {
        viewModelScope.launch {
            if (!api.hasSession()) { screen = Screen.Login; return@launch }
            try { user = api.me(); screen = Screen.Home; methods = runCatching { api.methods() }.getOrNull() }
            catch (e: ApiException) { screen = Screen.Login; if (e.code == "NETWORK") error = e.message }
        }
    }

    fun forceLogout() { user = null; methods = null; screen = Screen.Login; error = "Your session ended. Please log in again." }

    fun refreshUser() { viewModelScope.launch { runCatching { user = api.me() } } }
    fun accountDeleted() { user = null; methods = null; screen = Screen.Login; info = "Your account was deleted." }
    fun reloadMethods() { viewModelScope.launch { methods = runCatching { api.methods() }.getOrNull() } }

    fun go(s: Screen) { error = null; info = null; screen = s }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; info = null
        viewModelScope.launch {
            try { block() } catch (e: ApiException) { if (e.code != "CANCELLED") error = e.message }
            finally { busy = false }
        }
    }
    private suspend fun enter(u: User) { user = u; methods = runCatching { api.methods() }.getOrNull(); screen = Screen.Home }

    fun login(id: String, pw: String, remember: Boolean) = run {
        if (id.isBlank() || pw.isEmpty()) throw ApiException("V", "Enter your login and password.")
        try { enter(api.login(id, pw, remember)) }
        catch (e: ApiException) {
            if (e.code == "EMAIL_NOT_VERIFIED") { pendingEmail = if ("@" in id && !id.startsWith("@")) id.trim().lowercase() else ""; screen = Screen.Verify; info = e.message }
            else throw e
        }
    }
    fun register(name: String, uname: String, email: String, pw: String, pw2: String, dob: String, terms: Boolean) = run {
        if (pw != pw2) throw ApiException("V", "Passwords don't match.")
        if (!terms) throw ApiException("V", "You must accept the Terms and Privacy Policy.")
        val u = api.register(name.trim(), uname.trim().lowercase(), email.trim().lowercase(), pw, dob.trim())
        if (u != null) enter(u)
        else { pendingEmail = email.trim().lowercase(); screen = Screen.Verify; info = "We emailed you a 6-digit code." }
    }
    fun verify(code: String) = run { enter(api.verifyEmail(pendingEmail, code.trim())) }
    fun resend() = run { api.resendVerification(pendingEmail); info = "A new code was sent." }
    fun forgot(email: String) = run { api.forgot(email.trim().lowercase()); pendingEmail = email.trim().lowercase(); screen = Screen.Reset; info = "If an account exists, we sent a reset code." }
    fun reset(code: String, pw: String) = run { api.reset(pendingEmail, code.trim(), pw); screen = Screen.Login; info = "Password updated. Log in with your new password." }

    fun google(ctx: Context) = run { enter(api.google(googleIdToken(ctx, BuildConfig.GOOGLE_WEB_CLIENT_ID))) }
    fun linkGoogle(ctx: Context) = run { api.linkGoogle(googleIdToken(ctx, BuildConfig.GOOGLE_WEB_CLIENT_ID)); methods = api.methods(); info = "Google account connected." }
    fun unlinkGoogle() = run { api.unlinkGoogle(); methods = api.methods(); info = "Google account disconnected." }

    fun logout(all: Boolean) = run { if (all) api.logoutAll() else api.logout(); user = null; methods = null; screen = Screen.Login }
}

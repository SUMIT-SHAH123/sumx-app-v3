package com.sumx.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sumx.app.BuildConfig
import com.sumx.app.data.ApiException
import com.sumx.app.data.Settings
import com.sumx.app.data.googleIdToken
import kotlinx.coroutines.launch

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 13.sp, color = SumxColors.Muted) }
        Icon(Icons.Filled.ChevronRight, null, tint = SumxColors.Muted)
    }
}
@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = SumxColors.Muted) }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, auth: AuthViewModel, onEditProfile: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(page != "home") { page = "home" }
    LaunchedEffect(page) { if (page == "sessions") vm.loadSessions(); if (page == "connections" || page == "delete") auth.reloadMethods() }
    val s = vm.settings
    val titles = mapOf("account" to "My account", "privacy" to "Privacy & safety", "apps" to "Authorized applications", "connections" to "Connections", "notifications" to "Notifications",
        "appearance" to "Appearance & accessibility", "voice" to "Voice & video", "activity" to "Activity privacy", "blocked" to "Blocked users", "sessions" to "Devices & sessions", "delete" to "Delete account")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (page == "home") {
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            NavRow("My account", "Email, username, SUM X ID") { page = "account" }
            NavRow("Profile", "Avatar, banner, theme, effects") { onEditProfile() }
            NavRow("Privacy & safety", "Status, who can reach you") { page = "privacy" }
            NavRow("Activity privacy", "Who sees your activity and music") { page = "activity" }
            NavRow("Notifications", "Choose what you receive") { page = "notifications" }
            NavRow("Voice & video", "Call audio and camera defaults") { page = "voice" }
            NavRow("Appearance & accessibility", "Text size, motion") { page = "appearance" }
            NavRow("Connections", "Google account, music detection") { page = "connections" }
            NavRow("Authorized applications", "Third-party access") { page = "apps" }
            NavRow("Blocked users", "${vm.blocked.size} blocked") { page = "blocked" }
            NavRow("Devices & sessions", "Where you're logged in") { page = "sessions" }
            NavRow("Delete account", "Permanently remove your account") { page = "delete" }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Log out", auth.busy) { auth.logout(false) }
            Text("SUM X is free for everyone. No subscriptions, no paid features.", fontSize = 12.sp, color = SumxColors.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ page = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(titles[page] ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            auth.info?.let { Text(it, color = SumxColors.Cyan) }
            when (page) {
                "account" -> {
                    val u = auth.user
                    var newName by remember { mutableStateOf("") }
                    Text("Email: ${u?.email}"); Text("SUM X ID: ${u?.sumxId}"); Text("Username: ${u?.username}")
                    Field(newName, { newName = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) }, "New username", hint = "3–20 letters, numbers or underscores")
                    PrimaryButton("Change username", vm.busy) { vm.changeUsername(newName) { auth.refreshUser(); newName = "" } }
                    Text("You can change your username once every 14 days. The 4 digits of your SUM X ID stay the same.", fontSize = 12.sp, color = SumxColors.Muted)
                }
                "privacy" -> {
                    ChoiceRow("Status", listOf("online" to "Online", "idle" to "Idle", "dnd" to "Do not disturb", "invisible" to "Invisible"), s.status) { vm.saveSettings(s.copy(status = it)) }
                    Text("Do not disturb silences notifications. Invisible shows you as offline to friends.", fontSize = 12.sp, color = SumxColors.Muted)
                    Text("• Only your friends can message or call you.\n• People need your exact SUM X ID to send a friend request.\n• Blocked users can't find, message or call you.", color = SumxColors.Muted, fontSize = 14.sp)
                    OutlinedButton({ page = "blocked" }) { Text("Manage blocked users", color = SumxColors.Text) }
                }
                "apps" -> Text("No applications are authorized. SUM X doesn't grant third-party apps access to your account.", color = SumxColors.Muted)
                "connections" -> {
                    val ctx = LocalContext.current; val g = auth.methods?.google
                    Text("Password: ${if (auth.methods?.password == true) "set" else "not set"}"); Text("Google: ${g ?: "not connected"}")
                    if (g == null) OutlinedButton({ auth.linkGoogle(ctx) }, enabled = !auth.busy) { Text("Connect Google account", color = SumxColors.Text) }
                    else OutlinedButton({ auth.unlinkGoogle() }, enabled = !auth.busy) { Text("Disconnect Google", color = SumxColors.Text) }
                    HorizontalDivider(color = SumxColors.Border); MusicDetectionRow(vm)
                }
                "notifications" -> {
                    val n = s.notify
                    ToggleRow("Messages", "New direct messages", n.messages) { vm.saveSettings(s.copy(notify = n.copy(messages = it))) }
                    ToggleRow("Friend requests", "Requests and accepted requests", n.friendRequests) { vm.saveSettings(s.copy(notify = n.copy(friendRequests = it))) }
                    ToggleRow("Calls", "Missed calls", n.calls) { vm.saveSettings(s.copy(notify = n.copy(calls = it))) }
                    ToggleRow("Mentions & replies", "", n.mentions) { vm.saveSettings(s.copy(notify = n.copy(mentions = it))) }
                    ToggleRow("Friend activity", "When friends start an activity", n.activity) { vm.saveSettings(s.copy(notify = n.copy(activity = it))) }
                    Text("System notifications appear while SUM X is running in the background. Notifications for a fully closed app need push (FCM) set up by the server owner.", fontSize = 12.sp, color = SumxColors.Muted)
                }
                "appearance" -> {
                    ToggleRow("Large message text", "Makes chat text 25% bigger", s.accessibility.largeText) { vm.saveSettings(s.copy(accessibility = s.accessibility.copy(largeText = it))) }
                    ToggleRow("Reduce motion", "Turns off animated profile effects", s.accessibility.reduceMotion) { vm.saveSettings(s.copy(accessibility = s.accessibility.copy(reduceMotion = it))) }
                    Text("Theme: SUM X Midnight (dark). Each profile can use its own theme from Edit profile.", fontSize = 13.sp, color = SumxColors.Muted)
                }
                "voice" -> {
                    val v = s.voice
                    ToggleRow("Noise suppression", "", v.noiseSuppression) { vm.saveSettings(s.copy(voice = v.copy(noiseSuppression = it))) }
                    ToggleRow("Echo cancellation", "", v.echoCancellation) { vm.saveSettings(s.copy(voice = v.copy(echoCancellation = it))) }
                    ToggleRow("Start video calls with camera off", "", v.startVideoOff) { vm.saveSettings(s.copy(voice = v.copy(startVideoOff = it))) }
                }
                "activity" -> {
                    val l = s.listeningShow
                    ChoiceRow("Who can see my activity (games, projects…)", listOf("friends" to "Friends", "nobody" to "Nobody"), s.activityVisibility) { vm.saveSettings(s.copy(activityVisibility = it)) }
                    ChoiceRow("Who can see what I'm listening to", listOf("friends" to "Friends", "nobody" to "Nobody"), s.listeningVisibility) { vm.saveSettings(s.copy(listeningVisibility = it)) }
                    Text("Music details friends can see", fontWeight = FontWeight.SemiBold)
                    ToggleRow("Song title", "", l.title) { vm.saveSettings(s.copy(listeningShow = l.copy(title = it))) }
                    ToggleRow("Artist", "", l.artist) { vm.saveSettings(s.copy(listeningShow = l.copy(artist = it))) }
                    ToggleRow("Album artwork", "", l.artwork) { vm.saveSettings(s.copy(listeningShow = l.copy(artwork = it))) }
                    ToggleRow("Music platform", "", l.platform) { vm.saveSettings(s.copy(listeningShow = l.copy(platform = it))) }
                    ToggleRow("Listening duration", "", l.duration) { vm.saveSettings(s.copy(listeningShow = l.copy(duration = it))) }
                    Text("To stop sharing completely, clear your activity on the Activity tab.", fontSize = 12.sp, color = SumxColors.Muted)
                }
                "blocked" -> if (vm.blocked.isEmpty()) Text("You haven't blocked anyone.", color = SumxColors.Muted)
                    else vm.blocked.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.displayName, 40.dp, null, p.avatarUrl); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(p.displayName); Text(p.sumxId, fontSize = 12.sp, color = SumxColors.Muted) }
                        TextButton({ vm.unblock(p) }) { Text("Unblock", color = SumxColors.Cyan) } } }
                "sessions" -> {
                    if (vm.sessions.isEmpty()) Text("Loading…", color = SumxColors.Muted)
                    vm.sessions.forEach { ss -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (ss.current) "This device" else if (ss.device?.contains("okhttp") == true) "SUM X Android app" else ss.device ?: "Unknown device", fontWeight = FontWeight.SemiBold)
                            Text("Signed in ${dayTime(ss.createdAt)} · last active ${dayTime(ss.lastUsedAt)}", fontSize = 12.sp, color = SumxColors.Muted) }
                        if (!ss.current) TextButton({ vm.revokeSession(ss) }) { Text("Log out", color = DndRed) } } }
                    OutlinedButton({ auth.logout(true) }, Modifier.fillMaxWidth(), enabled = !auth.busy) { Text("Log out of all devices", color = SumxColors.Text) }
                }
                "delete" -> {
                    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
                    var pw by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
                    val hasPw = auth.methods?.password == true
                    Text("This permanently removes your profile, friends and sign-in methods. Messages you already sent stay with the people you chatted with, shown as \"Deleted User\". This can't be undone.", color = SumxColors.Muted, fontSize = 14.sp)
                    if (hasPw) Field(pw, { pw = it }, "Your password", password = true) else Text("You signed up with Google, so you'll confirm with Google.", fontSize = 13.sp)
                    Field(confirm, { confirm = it }, "Type DELETE to confirm")
                    Button({
                        if (hasPw) vm.deleteAccount(pw, null) { auth.accountDeleted() }
                        else scope.launch { try { vm.deleteAccount(null, googleIdToken(ctx, BuildConfig.GOOGLE_WEB_CLIENT_ID)) { auth.accountDeleted() } } catch (e: ApiException) { vm.message = e.message } }
                    }, Modifier.fillMaxWidth().height(50.dp), enabled = confirm == "DELETE" && (!hasPw || pw.isNotEmpty()), colors = ButtonDefaults.buttonColors(containerColor = DndRed)) { Text("Delete my account") }
                }
            }
        }
    }
}

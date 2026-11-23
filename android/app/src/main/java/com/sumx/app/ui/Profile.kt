package com.sumx.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sumx.app.data.Person
import com.sumx.app.data.Profile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import androidx.compose.ui.draw.clip


fun themeColors(theme: String): List<Color> = when (theme) {
    "ocean" -> listOf(0xFF04162E, 0xFF0B3A66); "neon" -> listOf(0xFF0A0420, 0xFF24104F)
    "sunset" -> listOf(0xFF2B0F1A, 0xFF5A2535); "graphite" -> listOf(0xFF0E1116, 0xFF222833); else -> listOf(0xFF05070D, 0xFF0C1630)
}.map { Color(it) }

@Composable
fun NameText(name: String, style: String, accent: Color, size: TextUnit = 24.sp) {
    val base = TextStyle(fontSize = size, fontWeight = FontWeight.Bold, color = Color.White)
    val st = when (style) {
        "gradient" -> base.copy(brush = Brush.linearGradient(listOf(SumxColors.Blue, SumxColors.Cyan)))
        "glow" -> base.copy(shadow = Shadow(accent, Offset.Zero, 18f))
        "neon" -> base.copy(color = accent, shadow = Shadow(accent, Offset.Zero, 24f))
        else -> base
    }
    Text(name, style = st)
}

@Composable
private fun EffectOverlay(effect: String, accent: Color, reduce: Boolean, modifier: Modifier) {
    if (effect == "none") return
    val t = rememberInfiniteTransition(label = "fx")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    val p = if (reduce) 0.7f else phase
    Canvas(modifier) {
        if (effect == "sparkle") listOf(.1f to .2f, .3f to .7f, .55f to .3f, .75f to .6f, .9f to .25f, .45f to .85f).forEachIndexed { i, (x, y) ->
            val a = (p + i * 0.17f) % 1f
            drawCircle(accent.copy(alpha = 0.25f + 0.65f * a), 2.5f + 3f * a, Offset(size.width * x, size.height * y))
        } else drawRect(accent.copy(alpha = 0.05f + 0.14f * p))
    }
}

@Composable
fun ProfileCard(p: Profile, reduceMotion: Boolean) {
    val accent = hexColor(p.accentColor)
    Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent, border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brush.verticalGradient(themeColors(p.theme)))) {
            p.backgroundUrl?.let { NetImage(it, Modifier.matchParentSize().alpha(0.18f)) }
            Column {
                Box(Modifier.fillMaxWidth().height(110.dp)) {
                    if (p.bannerUrl != null) NetImage(p.bannerUrl, Modifier.fillMaxSize(), alignment = BiasAlignment(0f, p.bannerPosition / 50f - 1f))
                    else Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(hexColor(p.bannerColor, SumxColors.Blue), hexColor(p.bannerColor, SumxColors.Blue).copy(alpha = 0.55f)))))
                    EffectOverlay(p.effect, accent, reduceMotion, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.BottomStart).offset(x = 16.dp, y = 40.dp)) { Avatar(p.displayName, 84.dp, p.status, p.avatarUrl, p.frame, p.decoration, accent) }
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 50.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NameText(p.displayName, p.nameStyle, accent)
                    Text(p.sumxId + (p.pronouns?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = SumxColors.Muted, fontSize = 14.sp)
                    p.customStatus?.takeIf { it.isNotBlank() }?.let { Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.08f)) { Text(it, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 14.sp) } }
                    if (p.badges.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.badges.forEach { Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.18f)) { Text(it.label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp, color = accent) } } }
                    p.listening?.let { l ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                            if (l.artworkUrl != null) { NetImage(l.artworkUrl, Modifier.size(44.dp).background(Color.Black, RoundedCornerShape(8.dp))); Spacer(Modifier.width(10.dp)) }
                            Column {
                                Text(listeningText(l), fontSize = 14.sp)
                                val mins = l.startedAt?.let { runCatching { (System.currentTimeMillis() - Instant.parse(it).toEpochMilli()) / 60000 }.getOrNull() }
                                if (mins != null) Text("for $mins min", fontSize = 12.sp, color = SumxColors.Muted)
                            }
                        }
                    }
                    p.activity?.let { Text(activityLine(it), fontSize = 14.sp, color = accent) }
                    if (p.about.isNotBlank()) { Text("ABOUT ME", fontSize = 11.sp, color = SumxColors.Muted, fontWeight = FontWeight.Bold); Text(p.about, fontSize = 14.sp) }
                    Text(listOfNotNull(p.createdAt?.take(10)?.let { "Member since $it" }, if (p.mutualFriends > 0) "${p.mutualFriends} mutual friends" else null).joinToString(" · "), fontSize = 12.sp, color = SumxColors.Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(vm: AppViewModel, p: Profile, ask: (List<String>, () -> Unit) -> Unit, onMessage: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val person = Person(p.id, p.displayName, p.sumxId, p.username, p.avatarUrl, p.accentColor, p.status ?: "offline", p.status != null && p.status != "offline")
    ModalBottomSheet(onDismissRequest = { vm.closeProfile() }, sheetState = state, containerColor = SumxColors.Bg) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileCard(p, vm.settings.accessibility.reduceMotion)
            if (p.isFriend) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.openWith(person); vm.closeProfile(); onMessage() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SumxColors.Blue)) { Text("Message") }
                    OutlinedButton({ vm.closeProfile(); startCall(vm, ask, person, "voice") }, Modifier.weight(1f)) { Text("Call", color = SumxColors.Text) }
                    OutlinedButton({ vm.closeProfile(); startCall(vm, ask, person, "video") }, Modifier.weight(1f)) { Text("Video", color = SumxColors.Text) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ vm.removeFriend(person) }) { Text("Remove friend", color = DndRed) }
                    TextButton({ vm.block(person) }) { Text("Block", color = DndRed) }
                }
            } else if (p.id != vm.profile?.id) {
                Button({ vm.sendRequest(p.sumxId); vm.closeProfile() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SumxColors.Blue)) { Text("Add friend") }
                TextButton({ vm.block(person) }) { Text("Block", color = DndRed) }
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: AppViewModel, auth: AuthViewModel) {
    if (vm.editingProfile) { ProfileEditor(vm); return }
    val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    val p = vm.profile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (p == null) Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { CircularProgressIndicator(color = SumxColors.Cyan) }
        else {
            ProfileCard(p, vm.settings.accessibility.reduceMotion)
            PrimaryButton("Edit profile", false) { vm.editingProfile = true }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ clip.setText(AnnotatedString(p.sumxId)); vm.message = "ID copied." }, Modifier.weight(1f)) { Text("Copy ID", color = SumxColors.Text) }
                OutlinedButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Add me on SUM X: ${p.sumxId}") }, "Share SUM X ID"))
                }, Modifier.weight(1f)) { Text("Share", color = SumxColors.Text) }
            }
        }
    }
}

private val SWATCHES = listOf("#006BFF", "#00D9FF", "#7C3AED", "#EC4899", "#EF4444", "#F59E0B", "#10B981", "#64748B", "#0F172A", "#FFFFFF")

@Composable
private fun ColorPicker(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = SumxColors.Muted, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SWATCHES.forEach { hx -> Box(Modifier.size(32.dp).clip(CircleShape).background(hexColor(hx)).border(if (hx.equals(value, true)) 3.dp else 1.dp, if (hx.equals(value, true)) Color.White else SumxColors.Border, CircleShape).clickable { onChange(hx) }) }
        }
        Field(value, { onChange(it.take(7)) }, "Hex color", hint = if (Regex("^#[0-9a-fA-F]{6}$").matches(value)) null else "Use a hex color like #006BFF")
    }
}

@Composable
fun ChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = SumxColors.Muted, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> FilterChip(selected == v, { onSelect(v) }, { Text(l) }) }
        }
    }
}

@Composable
fun ProfileEditor(vm: AppViewModel) {
    val base = vm.profile ?: return
    BackHandler { vm.editingProfile = false }
    var name by remember { mutableStateOf(base.displayName) }; var about by remember { mutableStateOf(base.about) }
    var pronouns by remember { mutableStateOf(base.pronouns ?: "") }; var status by remember { mutableStateOf(base.customStatus ?: "") }
    var bannerColor by remember { mutableStateOf(base.bannerColor) }; var accent by remember { mutableStateOf(base.accentColor) }
    var pos by remember { mutableFloatStateOf(base.bannerPosition.toFloat()) }
    var frame by remember { mutableStateOf(base.frame) }; var deco by remember { mutableStateOf(base.decoration) }; var effect by remember { mutableStateOf(base.effect) }
    var nameStyle by remember { mutableStateOf(base.nameStyle) }; var theme by remember { mutableStateOf(base.theme) }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }; var bannerUri by remember { mutableStateOf<Uri?>(null) }; var bgUri by remember { mutableStateOf<Uri?>(null) }
    var removed by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> avatarUri = u; removed = removed - "avatar" } }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> bannerUri = u; removed = removed - "banner" } }
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> bgUri = u; removed = removed - "background" } }
    val img = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    val hex = Regex("^#[0-9a-fA-F]{6}$")

    val draft = base.copy(displayName = name.ifBlank { base.displayName }, about = about, pronouns = pronouns.ifBlank { null }, customStatus = status.ifBlank { null },
        bannerColor = bannerColor, accentColor = accent, bannerPosition = pos.toInt(), frame = frame, decoration = deco, effect = effect, nameStyle = nameStyle, theme = theme,
        avatarUrl = avatarUri?.toString() ?: if ("avatar" in removed) null else base.avatarUrl,
        bannerUrl = bannerUri?.toString() ?: if ("banner" in removed) null else base.bannerUrl,
        backgroundUrl = bgUri?.toString() ?: if ("background" in removed) null else base.backgroundUrl)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Edit profile", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Live preview — nothing is saved until you tap Save.", color = SumxColors.Muted, fontSize = 13.sp)
        ProfileCard(draft, vm.settings.accessibility.reduceMotion)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Field(name, { name = it.take(32) }, "Display name")
        Field(pronouns, { pronouns = it.take(24) }, "Pronouns (optional)")
        Field(status, { status = it.take(60) }, "Custom status")
        OutlinedTextField(about, { about = it.take(190) }, Modifier.fillMaxWidth(), label = { Text("About me") }, minLines = 3, maxLines = 5, supportingText = { Text("${about.length}/190") },
            shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SumxColors.Cyan, unfocusedBorderColor = SumxColors.Border, focusedLabelColor = SumxColors.Cyan, cursorColor = SumxColors.Cyan))

        Text("Images", fontWeight = FontWeight.SemiBold)
        listOf(Triple("Avatar", pickAvatar, "avatar"), Triple("Banner", pickBanner, "banner"), Triple("Profile background", pickBg, "background")).forEach { (label, launcher, slot) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f)); OutlinedButton({ launcher.launch(img) }) { Text("Choose", color = SumxColors.Text) }
                TextButton({ when (slot) { "avatar" -> avatarUri = null; "banner" -> bannerUri = null; else -> bgUri = null }; removed = removed + slot }) { Text("Remove", color = DndRed) }
            }
        }
        Text("Banner position", color = SumxColors.Muted, fontSize = 13.sp)
        Slider(pos, { pos = it }, valueRange = 0f..100f)
        ColorPicker("Banner color", bannerColor) { bannerColor = it }
        ColorPicker("Accent color", accent) { accent = it }
        ChoiceRow("Theme", listOf("midnight" to "Midnight", "ocean" to "Ocean", "neon" to "Neon", "sunset" to "Sunset", "graphite" to "Graphite"), theme) { theme = it }
        ChoiceRow("Avatar frame", listOf("none" to "None", "ring" to "Ring", "gradient" to "Gradient", "double" to "Double"), frame) { frame = it }
        ChoiceRow("Avatar decoration", listOf("none" to "None", "star" to "⭐ Star", "crown" to "👑 Crown", "flame" to "🔥 Flame", "bolt" to "⚡ Bolt"), deco) { deco = it }
        ChoiceRow("Profile effect", listOf("none" to "None", "sparkle" to "Sparkle", "pulse" to "Pulse"), effect) { effect = it }
        ChoiceRow("Name style", listOf("plain" to "Plain", "gradient" to "Gradient", "glow" to "Glow", "neon" to "Neon"), nameStyle) { nameStyle = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton({ vm.editingProfile = false }, Modifier.weight(1f).height(50.dp)) { Text("Discard", color = SumxColors.Text) }
            Box(Modifier.weight(1f)) { PrimaryButton("Save", vm.busy) {
                error = when {
                    name.isBlank() -> "Display name can't be empty."
                    !hex.matches(bannerColor) || !hex.matches(accent) -> "Colors must look like #006BFF."
                    else -> null
                }
                if (error == null) {
                    val fields = buildJsonObject {
                        if (name.trim() != base.displayName) put("displayName", name.trim())
                        if (about != base.about) put("about", about)
                        if (pronouns.trim().ifBlank { null } != base.pronouns) put("pronouns", pronouns.trim().ifBlank { null })
                        if (status.trim().ifBlank { null } != base.customStatus) put("customStatus", status.trim().ifBlank { null })
                        if (bannerColor != base.bannerColor) put("bannerColor", bannerColor)
                        if (accent != base.accentColor) put("accentColor", accent)
                        if (pos.toInt() != base.bannerPosition) put("bannerPosition", pos.toInt())
                        if (frame != base.frame) put("frame", frame); if (deco != base.decoration) put("decoration", deco); if (effect != base.effect) put("effect", effect)
                        if (nameStyle != base.nameStyle) put("nameStyle", nameStyle); if (theme != base.theme) put("theme", theme)
                    }
                    val uploads = buildMap<String, Uri> { avatarUri?.let { put("avatar", it) }; bannerUri?.let { put("banner", it) }; bgUri?.let { put("background", it) } }
                    vm.saveProfile(fields, uploads, removed) { vm.editingProfile = false }
                }
            } }
        }
    }
}

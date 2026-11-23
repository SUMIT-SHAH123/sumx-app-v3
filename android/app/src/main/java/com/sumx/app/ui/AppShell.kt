package com.sumx.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sumx.app.data.CallState
import com.sumx.app.data.Person

/** Asks for the needed permissions, then runs [action]. */
@Composable
fun rememberPermissionRunner(): (List<String>, () -> Unit) -> Unit {
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val a = pendingAction; pendingAction = null
        if (a != null && res.values.all { it }) a()
    }
    return { perms, action -> pendingAction = action; launcher.launch(perms.toTypedArray()) }
}

@Composable
fun AppShell(auth: AuthViewModel) {
    val vm: AppViewModel = viewModel()
    val me = auth.user ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val ask = rememberPermissionRunner()

    LaunchedEffect(me.id) { vm.start(me.id) }
    DisposableEffect(me.id) { onDispose { vm.stop() } }
    LaunchedEffect(vm.sessionEnded) { if (vm.sessionEnded) auth.forceLogout() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    LaunchedEffect(vm.calls.notice) { vm.calls.notice?.let { snack.showSnackbar(it); vm.calls.notice = null; vm.loadCalls() } }

    val callState = vm.calls.state
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = SumxColors.Bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (vm.openConv == null) NavigationBar(containerColor = SumxColors.Surface) {
                    val colors = NavigationBarItemDefaults.colors(selectedIconColor = SumxColors.Cyan, selectedTextColor = SumxColors.Cyan, indicatorColor = SumxColors.Card,
                        unselectedIconColor = SumxColors.Muted, unselectedTextColor = SumxColors.Muted)
                    listOf(Triple("Friends", vm.requests.incoming.size, Icons.Filled.People),
                        Triple("Messages", vm.convs.sumOf { if (it.muted) 0 else it.unread }, Icons.Filled.Chat),
                        Triple("Activity", vm.notifs.unread, Icons.Filled.GraphicEq),
                        Triple("Profile", 0, Icons.Filled.Person), Triple("Settings", 0, Icons.Filled.Settings)
                    ).forEachIndexed { i, (label, badge, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(label) }, colors = colors,
                            icon = { if (badge > 0) BadgedBox({ Badge { Text("$badge") } }) { Icon(icon, label) } else Icon(icon, label) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (vm.openConv != null) ChatScreen(vm, me.id, ask)
                else when (tab) {
                    0 -> FriendsScreen(vm) { tab = 1 }
                    1 -> MessagesScreen(vm)
                    2 -> ActivityScreen(vm, ask)
                    3 -> ProfileScreen(vm, auth)
                    else -> SettingsScreen(vm, auth) { vm.editingProfile = true; tab = 3 }
                }
            }
        }
        vm.viewing?.let { ProfileSheet(vm, it, ask) { tab = 1 } }
        if (callState !is CallState.Idle) CallScreen(vm.calls, ask)
    }
}

fun startCall(vm: AppViewModel, ask: (List<String>, () -> Unit) -> Unit, p: Person, kind: String) {
    val perms = if (kind == "video") listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else listOf(Manifest.permission.RECORD_AUDIO)
    ask(perms) { vm.calls.invite(p, kind) }
}

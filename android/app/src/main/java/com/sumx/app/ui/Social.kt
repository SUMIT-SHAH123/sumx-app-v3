package com.sumx.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sumx.app.data.Person

@Composable
private fun PersonRow(p: Person, subtitle: String, onClick: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.displayName, status = p.status, url = p.avatarUrl)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = SumxColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun FriendsScreen(vm: AppViewModel, onOpenChat: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    val titles = listOf("All", "Online", "Pending", "Sent", "Blocked")
    val match = { p: Person -> query.isBlank() || p.displayName.contains(query, true) || p.sumxId.contains(query, true) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Friends", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalIconButton({ showAdd = true }) { Icon(Icons.Filled.PersonAdd, "Add friend") }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Field(query, { query = it }, "Search friends") }
        ScrollableTabRow(tab, containerColor = Color.Transparent, edgePadding = 8.dp) {
            titles.forEachIndexed { i, t ->
                val n = if (i == 2) vm.requests.incoming.size else 0
                Tab(tab == i, { tab = i }, text = { Text(if (n > 0) "$t ($n)" else t) })
            }
        }
        if (!vm.loaded) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = SumxColors.Cyan) }
        else when (tab) {
            0, 1 -> {
                val list = vm.friends.filter { (tab == 0 || it.online) && match(it) }
                if (list.isEmpty()) Empty(if (vm.friends.isEmpty()) "No friends yet. Tap + and enter a SUM X ID to send a request." else if (tab == 1) "No friends are online right now." else "No matches.")
                else LazyColumn { items(list, key = { it.id }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    val act = vm.feed.active.firstOrNull { it.user.id == p.id }
                    val sub = statusLabel(p.status) + (act?.let { activityText(it.activity, it.listening) }?.let { " · $it" } ?: "")
                    PersonRow(p, sub, onClick = { vm.openProfile(p.id) }) {
                        IconButton({ vm.openWith(p); onOpenChat() }) { Icon(Icons.Filled.Chat, "Message", tint = SumxColors.Cyan) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("View profile") }, { menu = false; vm.openProfile(p.id) })
                                DropdownMenuItem({ Text("Remove friend") }, { menu = false; vm.removeFriend(p) })
                                DropdownMenuItem({ Text("Block") }, { menu = false; vm.block(p) })
                            }
                        }
                    }
                } }
            }
            2 -> {
                val list = vm.requests.incoming.filter { match(it.user) }
                if (list.isEmpty()) Empty("No pending friend requests.")
                else LazyColumn { items(list, key = { it.id }) { r -> PersonRow(r.user, "wants to be friends") {
                    IconButton({ vm.requestAction(r, "accept") }) { Icon(Icons.Filled.Check, "Accept", tint = OnlineGreen) }
                    IconButton({ vm.requestAction(r, "reject") }) { Icon(Icons.Filled.Close, "Reject", tint = MaterialTheme.colorScheme.error) }
                } } }
            }
            3 -> {
                val list = vm.requests.outgoing.filter { match(it.user) }
                if (list.isEmpty()) Empty("You haven't sent any requests.")
                else LazyColumn { items(list, key = { it.id }) { r -> PersonRow(r.user, "request sent") {
                    TextButton({ vm.requestAction(r, "cancel") }) { Text("Cancel", color = SumxColors.Cyan) }
                } } }
            }
            else -> {
                val list = vm.blocked.filter(match)
                if (list.isEmpty()) Empty("You haven't blocked anyone.")
                else LazyColumn { items(list, key = { it.id }) { p -> PersonRow(p, "blocked") {
                    TextButton({ vm.unblock(p) }) { Text("Unblock", color = SumxColors.Cyan) }
                } } }
            }
        }
    }

    if (showAdd) {
        var id by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false }, containerColor = SumxColors.Card, title = { Text("Add friend") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter their exact SUM X ID, for example @sumit4821.", color = SumxColors.Muted, fontSize = 14.sp); Field(id, { id = it }, "SUM X ID") } },
            confirmButton = { TextButton({ if (id.isNotBlank()) { vm.sendRequest(id); showAdd = false } }) { Text("Send request", color = SumxColors.Cyan) } },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessagesScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("Messages", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
        if (!vm.loaded) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = SumxColors.Cyan) }
        else if (vm.convs.isEmpty()) Empty("No conversations yet. Open a friend from the Friends tab and tap the chat icon.")
        else LazyColumn { items(vm.convs, key = { it.id }) { c ->
            var menu by remember { mutableStateOf(false) }
            val mine = c.last?.senderId != c.user.id
            Row(Modifier.fillMaxWidth().clickable { vm.openChat(c) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.user.displayName, status = c.user.status, url = c.user.avatarUrl)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(c.user.displayName, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                    val l = c.last
                    val body = when { l == null -> "No messages yet"; l.deleted -> "Message deleted"
                        !l.body.isNullOrBlank() -> l.body; l.kind == "gif" -> "GIF"; l.kind == "image" -> "📷 Photo"; l.kind == "voice" -> "🎤 Voice message"; l.kind == "file" -> "📎 File"; else -> "" }
                    Text((if (l != null && mine) "You: " else "") + body, color = if (c.unread > 0) SumxColors.Text else SumxColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    c.last?.let { Text(hhmm(it.createdAt), color = SumxColors.Muted, fontSize = 12.sp) }
                    if (c.muted) Icon(Icons.Filled.NotificationsOff, "Muted", Modifier.size(16.dp), tint = SumxColors.Muted)
                    else if (c.unread > 0) Badge(containerColor = SumxColors.Blue) { Text("${c.unread}") }
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(if (c.muted) "Unmute" else "Mute") }, { menu = false; vm.mute(c) })
                        DropdownMenuItem({ Text("Delete conversation") }, { menu = false; vm.hide(c) })
                    }
                }
            }
        } }
    }
}

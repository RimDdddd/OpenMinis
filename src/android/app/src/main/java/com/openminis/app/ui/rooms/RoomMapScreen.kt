package com.openminis.app.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.rooms.RoomLocationStore
import com.openminis.app.ui.settings.SettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-rooms] One grid of rooms; each cell holds the faces of whoever is standing
 * in it.
 *
 * Two kinds of movement write the same json, which is why this screen does not
 * own any state of its own: the user tapping a cell here, and the character
 * calling `move_room` from the chat. The screen re-reads
 * [RoomLocationStore.snapshot] on every tap (and the store re-reads the file
 * when its mtime changed), so a move made in the chat shows up here without any
 * listener plumbing.
 *
 * Rooms come from the json, never from the mount itself, but the folders are
 * still the source of truth: every open rescans the mounted roots so a folder
 * the user just created appears without editing json by hand.
 */
@Composable
fun RoomMapScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(RoomLocationStore.snapshot(context)) }
    // 这版屋里只有两个人：川和她。名字从 SOUL.md 读，读不到就写"她"。
    var characterName by remember { mutableStateOf("") }
    val known = remember {
        listOf(RoomLocationStore.USER_KEY, RoomLocationStore.CHARACTER_KEY)
    }
    LaunchedEffect(Unit) {
        characterName = RoomLocationStore.characterName(context)
        // First open only: puts both of them in their own room so the grid is
        // not an empty page while the prompt already says where they are.
        snapshot = withContext(Dispatchers.IO) { RoomLocationStore.seedIfMissing(context, known) }
        // [T-rooms] The folders ARE the rooms: rescan the mounted roots every
        // time the map opens. Only a mount that ALREADY contains one of our
        // rooms is a room container ("房间加加加") — any other mount (a photo
        // album, a document vault) must not have its subfolders turned into
        // rooms just because it is mounted.
        val discovered = withContext(Dispatchers.IO) {
            val app = context.applicationContext as? com.openminis.app.MinisApp
            val current = snapshot.rooms.toSet()
            app?.mountedFoldersStore?.entries?.value
                ?.mapNotNull { it.resolvedHostPath }
                ?.flatMap { root ->
                    val children = File(root).listFiles()?.filter { it.isDirectory } ?: emptyList()
                    if (children.any { it.name in current }) children.map { it.name } else emptyList()
                }
                ?.distinct()
                ?: emptyList()
        }
        if (discovered.isNotEmpty()) {
            snapshot = withContext(Dispatchers.IO) { RoomLocationStore.syncRooms(context, discovered) }
        }
    }
    val myRoom = snapshot.roomOf(RoomLocationStore.USER_KEY)

    SettingsScaffold(
        title = stringResource(R.string.rooms_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.rooms_you_are_in, myRoom),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            snapshot.rooms.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { room ->
                        val people = known.filter { snapshot.roomOf(it) == room }
                        Box(Modifier.weight(1f)) {
                            RoomCell(
                                room = room,
                                people = people,
                                here = room == myRoom,
                                characterName = characterName,
                                onClick = {
                                    snapshot = RoomLocationStore.moveTo(
                                        context,
                                        RoomLocationStore.USER_KEY,
                                        room,
                                    )
                                },
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = stringResource(R.string.rooms_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RoomCell(
    room: String,
    people: List<String>,
    here: Boolean,
    characterName: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(
                if (here) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            )
            .border(
                width = if (here) 2.dp else 1.dp,
                color = if (here) accent else outline.copy(alpha = 0.7f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            people.take(4).forEach { key -> FaceChip(key, characterName) }
        }
        Text(
            text = room,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (here) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun FaceChip(key: String, characterName: String) {
    val name = if (key == RoomLocationStore.USER_KEY) "川"
    else characterName.ifBlank { "她" }
    val color = if (key == RoomLocationStore.USER_KEY) Color(0xFF6F8F7C) else Color(0xFF3D86D6)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

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
import com.openminis.app.agent.SoulStore
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
 * own any state of its own: the user tapping a cell here, and a character
 * calling `move_room` from the chat. The screen re-reads
 * [RoomLocationStore.snapshot] on every tap (and the store re-reads the file
 * when its mtime changed), so a move made in the chat shows up here without any
 * listener plumbing.
 *
 * Rooms come from the json, never from the mount itself — the app has no
 * business walking a user-mounted folder to decide what a room is. A room the
 * sandbox added but the user has never entered still gets a cell, because
 * `move_room` appends unknown names to the list.
 */
@Composable
fun RoomMapScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(RoomLocationStore.snapshot(context)) }
    // Everyone the app knows about, not just whoever the file happens to
    // mention: a character with no row yet is still standing somewhere, and
    // leaving her off the map would read as "she does not exist".
    var known by remember {
        mutableStateOf(listOf(RoomLocationStore.USER_KEY, com.openminis.app.agent.SoulStore.DEFAULT_PERSONA_DIR_NAME))
    }
    LaunchedEffect(Unit) {
        val ids = withContext(Dispatchers.IO) {
            File(context.filesDir, "minis-global/personas")
                .listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }
        known = (listOf(RoomLocationStore.USER_KEY) + ids +
            com.openminis.app.agent.SoulStore.DEFAULT_PERSONA_DIR_NAME).distinct()
        // First open only: puts everyone in their own room so the grid is not
        // an empty page while the prompt already says where they are.
        snapshot = withContext(Dispatchers.IO) { RoomLocationStore.seedIfMissing(context, known) }
        // [T-rooms] The folders ARE the rooms: rescan the mounted roots every
        // time the map opens, so creating or renaming a folder shows up here
        // without touching any json. Only direct children of a mounted root
        // count — deeper levels are contents, not rooms.
        val discovered = withContext(Dispatchers.IO) {
            val app = context.applicationContext as? com.openminis.app.MinisApp
            app?.mountedFoldersStore?.entries?.value
                ?.mapNotNull { it.resolvedHostPath }
                ?.flatMap { root ->
                    File(root).listFiles()
                        ?.filter { it.isDirectory }
                        ?.map { it.name }
                        ?: emptyList()
                }
                ?.distinct()
                ?: emptyList()
        }
        if (discovered.isNotEmpty()) {
            snapshot = withContext(Dispatchers.IO) { RoomLocationStore.syncRooms(context, discovered) }
        }
    }
    val me = RoomLocationStore.USER_KEY
    val myRoom = snapshot.roomOf(me)

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
                        val people = known
                            .filter { snapshot.roomOf(it) == room }
                            .sorted()
                        Box(Modifier.weight(1f)) {
                            RoomCell(
                                room = room,
                                people = people,
                                here = room == myRoom,
                                onClick = { snapshot = RoomLocationStore.moveTo(context, me, room) },
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
            people.take(4).forEach { key -> FaceChip(key) }
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
private fun FaceChip(key: String) {
    val name = remember(key) {
        if (key == RoomLocationStore.USER_KEY) "川"
        else SoulStore.cachedMetadataFor(key).value.name
    }
    val color = when {
        key == RoomLocationStore.USER_KEY -> Color(0xFF6F8F7C)
        key == SoulStore.DEFAULT_PERSONA_DIR_NAME -> Color(0xFF3D86D6)
        else -> Color(0xFFE0925A)
    }
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

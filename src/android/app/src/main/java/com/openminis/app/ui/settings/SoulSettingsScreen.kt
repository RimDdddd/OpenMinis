package com.openminis.app.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.db.PersonaEntity
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.agent.SoulBodyLimitCheck
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [T-soul-md] Settings page for editing SOUL.md. Mirrors iOS
 * `SoulSettingsView` (commit 6370d5a):
 *   - Header preview card showing `✨ [name]` + `[style]` (emoji is
 *     locked to ✨; the editable emoji field was removed to keep
 *     identity surface consistent across the app)
 *   - Identity fields: name, style, lang (Auto / Chinese / English)
 *   - Personality prompt (multiline) with soft-warning + hard-truncate
 *     length indicators (>2000 chars = yellow, >4000 = red)
 *   - "Restore Default" with confirmation dialog
 *   - "Save" writes to SOUL.md via [SoulStore.save] and refreshes the
 *     in-memory metadata cache so chat bubble headers update without a
 *     restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulSettingsScreen(
    onBack: () -> Unit,
    /** [T-private-longterm] Opens the selected character's own diary. */
    onOpenMemory: (personaId: String?) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // [T-persona-phase1] Which character this page is editing.
    // `null` = the built-in default character (米米), whose files stay at
    // `minis-global/memory/` — picking it is exactly the pre-persona page.
    val personaDao = remember { (context.applicationContext as MinisApp).database.personaDao() }
    var selectedPersonaId by remember { mutableStateOf<String?>(null) }
    var personas by remember { mutableStateOf<List<PersonaEntity>>(emptyList()) }
    var personaReload by remember { mutableStateOf(0) }
    var showNewPersonaDialog by remember { mutableStateOf(false) }
    var newPersonaName by remember { mutableStateOf("") }
    var showDeletePersonaDialog by remember { mutableStateOf(false) }
    // Shown when the user taps another character while this one has unsaved
    // edits. Cheaper and less destructive than silently discarding the typing.
    var switchBlocked by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf(SoulMetadata.DEFAULT.name) }
    var style by remember { mutableStateOf(SoulMetadata.DEFAULT.style) }
    var lang by remember { mutableStateOf(SoulMetadata.DEFAULT.lang) }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Preserve the raw `emoji` field exactly as it appears in SOUL.md so
    // Save round-trips without clobbering a value the user may have set
    // on another device (or an older build). Not surfaced in the UI —
    // the identity emoji shown to the user is locked to ✨ everywhere
    // (see [SoulMetadata.displayEmoji]).
    var preservedEmoji by remember { mutableStateOf(SoulMetadata.DEFAULT.emoji) }
    // [T-android-soul-custom-icon] The user-settable identity icon: an emoji
    // literal, a data:image/png;base64 URI, or empty for the default sparkle.
    var icon by remember { mutableStateOf(SoulMetadata.DEFAULT.icon) }
    var showIconMenu by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }

    // [T-android-soul-save-in-appbar] What was loaded from disk, kept so
    // "has the user changed anything" is a comparison rather than a flag.
    //
    // A boolean set by every onValueChange would report dirty after a round
    // trip that lands back on the original text (type a character, delete it),
    // and would need a reset at each of the several places state is
    // reassigned — the load, the restore-default, and the save itself. A
    // snapshot compared by value cannot drift out of sync with the fields.
    var baseline by remember { mutableStateOf<SoulFile?>(null) }

    // [T-android-soul-custom-icon] Image import: decode → reject opaque →
    // centre-crop → 96px → PNG → base64 data URI. Runs off the main thread
    // because a full-resolution camera photo is expensive to decode and scan.
    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val iconTooLargeMsg = stringResource(R.string.soul_icon_error_too_large)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp == null) {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                } else {
                    SoulIcon.encode(bmp)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> iconError = when (result.reason) {
                    SoulIcon.Rejection.TOO_LARGE -> iconTooLargeMsg
                    SoulIcon.Rejection.UNREADABLE -> iconUnreadableMsg
                }
            }
        }
    }

    // Initial load + (defensive) ensureExists. The Application-level call
    // already seeded on first run, but loading from a freshly cleared app
    // shouldn't crash.
    LaunchedEffect(selectedPersonaId) {
        loaded = false
        val parsed = withContext(Dispatchers.IO) {
            SoulStore.ensureExists(context, selectedPersonaId)
            SoulStore.load(context, selectedPersonaId)
                ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        }
        name = parsed.metadata.name
        preservedEmoji = parsed.metadata.emoji
        icon = parsed.metadata.icon
        style = parsed.metadata.style
        lang = parsed.metadata.lang
        body = parsed.body
        // [T-android-soul-save-in-appbar] Snapshot what disk holds, so the
        // dirty check starts from the loaded state rather than from the
        // pre-load defaults (which would read as "changed" immediately).
        //
        // Rebuilt through the same normalisation Save applies (ifBlank
        // fallbacks) rather than stored raw: a file whose `lang` is empty on
        // disk would otherwise compare unequal to the field state the instant
        // it loaded, and the screen would open already dirty.
        baseline = SoulFile(
            metadata = SoulMetadata(
                name = parsed.metadata.name.ifBlank { SoulMetadata.DEFAULT.name },
                emoji = parsed.metadata.emoji.ifBlank { SoulMetadata.DEFAULT.emoji },
                icon = parsed.metadata.icon,
                style = parsed.metadata.style,
                lang = parsed.metadata.lang.ifBlank { SoulMetadata.DEFAULT.lang },
            ),
            body = parsed.body,
        )
        loaded = true
    }

    LaunchedEffect(personaReload) {
        personas = withContext(Dispatchers.IO) { personaDao.getAll() }
        // [T-persona-phase1] The session list renders these names from the
        // cache; a create/rename/delete here must not leave a stale label
        // behind on the other screen.
        com.openminis.app.data.PersonaCache.set(personas)
    }

    // Language-aware length check used by both the editor counter and the
    // Save button's enabled state. See [SoulStore.isOverLimit] for the rule.
    val bodyLimitCheck by remember(body) { derivedStateOf { SoulStore.isOverLimit(body) } }

    val currentFile = SoulFile(
        metadata = SoulMetadata(
            name = name.ifBlank { SoulMetadata.DEFAULT.name },
            emoji = preservedEmoji.ifBlank { SoulMetadata.DEFAULT.emoji },
            icon = icon,
            style = style,
            lang = lang.ifBlank { SoulMetadata.DEFAULT.lang },
        ),
        body = body,
    )
    val isDirty = loaded && baseline != null && currentFile != baseline
    var showDiscardDialog by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SoulStore.save(context, currentFile, selectedPersonaId)
                    // [T-persona-phase1] The name typed in the editor is the
                    // display name everywhere else (session header, persona
                    // chips). Keep the personas row in step on save rather
                    // than adding a second "rename" surface.
                    val id = selectedPersonaId
                    if (id != null) {
                        val row = personaDao.getById(id)
                        // [T-persona-phase1] Icon rides along with the name:
                        // both are what the session list and the assistant
                        // header show, and SOUL.md alone is not read by either.
                        val newIcon = icon.ifEmpty { null }
                        if (row != null && (row.name != name.trim() || row.icon != newIcon)) {
                            personaDao.upsert(row.copy(name = name.trim(), icon = newIcon))
                        }
                    }
                }
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: "save failed"
            }
        }
        Unit
    }

    // [T-android-soul-save-in-appbar] Leaving with unsaved edits asks first.
    // Routed through one lambda so the app bar's back arrow and the system
    // back gesture cannot disagree about whether the guard applies.
    val attemptBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDirty) { showDiscardDialog = true }

    SettingsScaffold(
        title = stringResource(R.string.soul_settings_title),
        onBack = attemptBack,
        actions = {
            // Save lives in the app bar, where a top-level commit action
            // belongs and where it stays reachable without scrolling the
            // (long) prompt editor to the bottom.
            MinisTextButton(
                onClick = save,
                enabled = loaded && isDirty && !bodyLimitCheck.isOverLimit,
            ) { Text(stringResource(R.string.soul_save)) }
        },
    ) {
        // [T-persona-phase1] Character switcher. The default character has no
        // row in `personas`, so it is rendered as a fixed first chip.
        PersonaBar(
            personas = personas,
            selectedPersonaId = selectedPersonaId,
            blocked = switchBlocked,
            onSelect = { id ->
                if (isDirty) {
                    switchBlocked = true
                } else {
                    switchBlocked = false
                    selectedPersonaId = id
                }
            },
            onCreate = {
                if (isDirty) switchBlocked = true else { switchBlocked = false; showNewPersonaDialog = true }
            },
            onDelete = { showDeletePersonaDialog = true },
            onOpenMemory = { onOpenMemory(selectedPersonaId) },
        )

        SettingsSection(
            header = stringResource(R.string.soul_section_preview),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [T-android-soul-custom-icon] A bare glyph does not read as
                // tappable — reported on iOS review — so the icon sits on a
                // filled circle with a hairline border and a pencil badge.
                // The circle doubles as the backdrop for transparent PNGs,
                // which by definition have no background of their own.
                Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { showIconMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        SoulIconGlyph(icon = icon, sizeDp = 30.dp, emojiSp = 24.sp)
                        // Badge kept INSIDE the circle's bounds: on iOS an
                        // overhanging badge was clipped to a sliver by the
                        // enclosing button label.
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(2.5.dp),
                        )
                    }
                    SoulIconMenu(
                        expanded = showIconMenu,
                        hasIcon = icon.isNotEmpty(),
                        onDismiss = { showIconMenu = false },
                        onChooseEmoji = { showIconMenu = false; showEmojiSheet = true },
                        onChooseImage = {
                            showIconMenu = false
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onUseDefault = { showIconMenu = false; icon = "" },
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = name.ifBlank { "Minis" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (style.isNotBlank()) {
                        Text(
                            text = style,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.soul_section_identity),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // Emoji field intentionally removed — identity emoji is
                // locked to ✨; only name / style / lang are editable.
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text(stringResource(R.string.soul_field_style)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LangPicker(lang = lang, onLangChange = { lang = it })
            }
        }

        SettingsSection(
            header = stringResource(R.string.soul_section_personality),
            footer = stringResource(R.string.soul_personality_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = { Text(stringResource(R.string.soul_body_placeholder)) },
                )
                Spacer(Modifier.height(6.dp))
                val isOverLimit = bodyLimitCheck.isOverLimit
                val warnColor: Color =
                    if (isOverLimit) Color(0xFFFF3B30)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                val indicatorText: String = when (val c = bodyLimitCheck) {
                    is SoulBodyLimitCheck.Ok -> {
                        // Show the unit that matches whichever rule the
                        // current body is being measured against — mirrors
                        // iOS soulBodyCountText.
                        soulBodyCountTextAndroid(body)
                    }
                    is SoulBodyLimitCheck.OverLimitChinese -> stringResource(
                        R.string.soul_over_limit_chinese, c.chars, c.cap, SoulStore.ENGLISH_WORD_LIMIT,
                    )
                    is SoulBodyLimitCheck.OverLimitEnglish -> stringResource(
                        R.string.soul_over_limit_english, c.words, c.cap, SoulStore.CHINESE_CHAR_LIMIT,
                    )
                }
                Text(
                    text = indicatorText,
                    fontSize = 12.sp,
                    color = warnColor,
                )
            }
        }

        SettingsSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MinisOutlinedButton(
                    onClick = { showRestoreDialog = true },
                    // [T-android-soul-save-in-appbar] Full width now that Save
                    // has moved to the app bar and this is the only button left
                    // in the row.
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.soul_restore_default)) }
            }
        }
    }

    // [T-android-soul-save-in-appbar] Unsaved-changes guard.
    //
    // Each button leads with the verdict and then names the consequence
    // ("OK, discard" / "Cancel, keep editing"), so the row scans correctly
    // whether the user reads the leading word or the trailing one — a bare
    // "Discard"/"Keep editing" pair reads fine but gives no cue about which
    // side is the safe one.
    //
    // Dismissing the dialog by tapping outside keeps the edits — the
    // conservative reading of an ambiguous gesture.
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.soul_discard_confirm_title)) },
            text = { Text(stringResource(R.string.soul_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.soul_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.soul_discard_cancel))
                }
            },
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.soul_restore_confirm_title)) },
            text = { Text(stringResource(R.string.soul_restore_confirm_body)) },
            confirmButton = {
                MinisButton(onClick = {
                    val parsed = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
                    name = parsed.metadata.name
                    preservedEmoji = parsed.metadata.emoji
                    icon = parsed.metadata.icon
                    style = parsed.metadata.style
                    lang = parsed.metadata.lang
                    body = parsed.body
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.soul_restore_default)) }
            },
            dismissButton = {
                MinisOutlinedButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    // [T-android-soul-custom-icon] Image rejection is explained after the
    // fact here. There is no longer a transparency requirement to state up
    // front — any image is accepted (see soul_icon_image_hint) — so the only
    // remaining rejections are "couldn't be read" and "too large", neither of
    // which can be predicted before the user picks a file.
    iconError?.let { err ->
        AlertDialog(
            onDismissRequest = { iconError = null },
            title = { Text(stringResource(R.string.soul_icon_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { iconError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(icon)) "" else icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen -> icon = chosen; showEmojiSheet = false },
        )
    }

    if (showNewPersonaDialog) {
        AlertDialog(
            onDismissRequest = { showNewPersonaDialog = false },
            title = { Text("新角色") },
            text = {
                OutlinedTextField(
                    value = newPersonaName,
                    onValueChange = { newPersonaName = it },
                    label = { Text("名字") },
                    singleLine = true,
                )
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        val nm = newPersonaName.trim()
                        if (nm.isEmpty()) return@MinisTextButton
                        showNewPersonaDialog = false
                        newPersonaName = ""
                        scope.launch {
                            val id = UUID.randomUUID().toString()
                            withContext(Dispatchers.IO) {
                                personaDao.upsert(
                                    PersonaEntity(
                                        id = id,
                                        name = nm,
                                        icon = null,
                                        createdAt = System.currentTimeMillis(),
                                        sortOrder = (personaDao.getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1,
                                    ),
                                )
                                // Start from an EMPTY body: copying the default
                                // character's SOUL would make the new character
                                // a clone of 米米, which is precisely what the
                                // first hand-written persona must not be.
                                SoulStore.save(
                                    context,
                                    SoulFile(
                                        SoulMetadata(
                                            name = nm,
                                            emoji = "",
                                            icon = "",
                                            style = "",
                                            lang = "auto",
                                        ),
                                        "",
                                    ),
                                    id,
                                )
                                SoulStore.refreshCache(context, id)
                            }
                            personaReload++
                            selectedPersonaId = id
                        }
                    },
                    enabled = newPersonaName.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = {
                MinisTextButton(onClick = { showNewPersonaDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDeletePersonaDialog) {
        val targetId = selectedPersonaId
        val targetName = personas.firstOrNull { it.id == targetId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { showDeletePersonaDialog = false },
            title = { Text("删除「$targetName」") },
            text = { Text("她的 SOUL、日记和头像都会一起删掉，绑在她身上的会话会退回默认角色。这一步不能撤销。") },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        showDeletePersonaDialog = false
                        if (targetId == null) return@MinisTextButton
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val app = context.applicationContext as MinisApp
                                // Sessions must not keep pointing at a row that
                                // no longer exists — they fall back to the
                                // default character instead.
                                app.database.chatDao().detachPersonaFromSessions(targetId)
                                personaDao.deleteById(targetId)
                                SoulStore.personaMemoryDir(context, targetId).deleteRecursively()
                                SoulStore.refreshCache(context, targetId)
                            }
                            personaReload++
                            selectedPersonaId = null
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeletePersonaDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * [T-persona-phase1] Horizontal character switcher shown above the SOUL editor.
 *
 * The default character is a fixed first chip rather than a `personas` row, so
 * "no row" and "the built-in one" cannot drift apart.
 */
@Composable
private fun PersonaBar(
    personas: List<PersonaEntity>,
    selectedPersonaId: String?,
    blocked: Boolean,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onDelete: () -> Unit,
    /** [T-private-longterm] Jump to this character's diary. */
    onOpenMemory: () -> Unit = {},
) {
    SettingsSection(header = "角色") {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonaChip(
                    label = "米米（默认）",
                    selected = selectedPersonaId == null,
                    onClick = { onSelect(null) },
                )
                personas.forEach { p ->
                    PersonaChip(
                        label = p.name.ifBlank { "未命名" },
                        selected = selectedPersonaId == p.id,
                        onClick = { onSelect(p.id) },
                    )
                }
                PersonaChip(label = "＋ 新角色", selected = false, onClick = onCreate)
            }
            if (blocked) {
                Text(
                    text = "有没保存的改动，先保存再切角色。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // [T-private-longterm] Each character's diary is a separate
            // directory; this is the obvious place to reach it, because the
            // user is already looking at "who" rather than "where".
            MinisTextButton(onClick = onOpenMemory) {
                Text("她的日记", fontSize = 12.sp)
            }
            if (selectedPersonaId != null) {
                MinisTextButton(onClick = onDelete) {
                    Text("删除这个角色", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PersonaChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text = label, color = fg, fontSize = 14.sp)
    }
}

/**
 * [T-android-soul-custom-icon] The icon itself, rendered the same way
 * everywhere it appears: a decoded bitmap, an emoji, or the default sparkle.
 *
 * The bitmap decode is `remember`ed on the icon string. Without that, the
 * chat header would re-decode base64 on every recomposition — and headers
 * recompose on every streaming tick.
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
) {
    val bitmap = remember(icon) { SoulIcon.decode(icon) }
    when {
        // Rounded rectangle, NOT a circle: at 18dp in the chat header a circle
        // eats the corners of a small avatar. Clipping here is also what makes
        // an opaque image acceptable at all — the refusal that used to guard
        // against "opaque rectangle reads as a broken tile" was a presentation
        // concern, and this is the presentation fix.
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)),
        )
        icon.isNotEmpty() -> Text(text = icon, fontSize = emojiSp)
        // Unset: byte-for-byte the previous sparkle, so a user who never
        // touches this sees no visual change at all.
        sparkleTint != null -> Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleTint, blendMode = BlendMode.SrcIn)
                },
        )
        else -> Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}

@Composable
private fun SoulIconMenu(
    expanded: Boolean,
    hasIcon: Boolean,
    onDismiss: () -> Unit,
    onChooseEmoji: () -> Unit,
    onChooseImage: () -> Unit,
    onUseDefault: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.soul_icon_choose_emoji)) },
            onClick = onChooseEmoji,
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.soul_icon_choose_image))
                    // Stated up front so the square crop isn't a surprise after
                    // picking. (There is no alpha requirement: opaque images are
                    // accepted and the rounded clip is what makes them look right.)
                    Text(
                        text = stringResource(R.string.soul_icon_image_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = onChooseImage,
        )
        // Only offered when there is something to clear.
        if (hasIcon) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.soul_icon_use_default)) },
                onClick = onUseDefault,
            )
        }
    }
}

/**
 * Two rows of suggestions, tap-to-fill, a live preview at render size, and a
 * free-form field that normalizes per keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulEmojiPickerSheet(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.soul_icon_emoji_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Preview at the size the icon is actually drawn on the card.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = draft.ifEmpty { SoulMetadata.DISPLAY_EMOJI },
                    fontSize = 30.sp,
                )
            }
            Spacer(Modifier.height(20.dp))

            SoulIcon.SUGGESTED_EMOJI.chunked(8).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (e == draft) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .clickable { draft = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(text = e, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                // Normalized PER KEYSTROKE rather than validated on submit:
                // a second emoji replaces the first, and non-emoji input is
                // dropped silently instead of being accepted then rejected.
                onValueChange = { draft = SoulIcon.normalizeEmojiInput(it) },
                label = { Text(stringResource(R.string.soul_icon_emoji_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinisOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.soul_cancel))
                }
                MinisButton(
                    onClick = { onPick(draft) },
                    enabled = draft.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_icon_set)) }
            }
        }
    }
}

/// Render the within-budget counter — picks the CJK character unit vs "words" depending on
/// the same CJK ratio rule that decides which cap applies. Standalone
/// helper so it stays out of the Composable hot-path's expression budget.
@Composable
private fun soulBodyCountTextAndroid(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return stringResource(R.string.soul_count_zero)
    var cjk = 0
    var total = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        total += 1
        val isCJK =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x3040..0x309F ||
                cp in 0x30A0..0x30FF ||
                cp in 0xAC00..0xD7AF
        if (isCJK) cjk += 1
        i += Character.charCount(cp)
    }
    val ratio = if (total > 0) cjk.toDouble() / total else 0.0
    return if (ratio > SoulStore.CJK_RATIO_THRESHOLD) {
        val chars = trimmed.codePointCount(0, trimmed.length)
        stringResource(R.string.soul_count_chars, chars, SoulStore.CHINESE_CHAR_LIMIT)
    } else {
        val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        stringResource(R.string.soul_count_words, words, SoulStore.ENGLISH_WORD_LIMIT)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LangPicker(lang: String, onLangChange: (String) -> Unit) {
    val options = listOf(
        "auto" to stringResource(R.string.soul_lang_auto),
        "zh" to stringResource(R.string.soul_lang_zh),
        "en" to stringResource(R.string.soul_lang_en),
    )
    val current = options.firstOrNull { it.first == lang } ?: options.first()
    var expanded by remember { mutableStateOf(false) }
    // Render the picker as a simple labeled row of buttons. Three options
    // (auto / Chinese / English) fit comfortably without a dropdown — avoids
    // depending on material3 ExposedDropdownMenu, which has a fragile
    // alignment story across compose versions.
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.soul_field_lang),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                if (key == current.first) {
                    MinisButton(
                        onClick = { onLangChange(key) },
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                } else {
                    MinisOutlinedButton(
                        onClick = { onLangChange(key) },
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
        }
    }
    expanded // suppress unused-var warning
}

#!/usr/bin/env python3
"""
apply_chat_background.py — 给 OpenMinis (Android) 加"聊天背景图"功能。

用法（在 OpenMinis 仓库根目录执行）：
    python3 apply_chat_background.py .

做什么：
  1) AppearanceScreen.kt —— 加两个 prefs 键 + 两个 helper + 一个「聊天背景」设置
     分区（选图 / 透明度 / 清除）。
  2) ChatScreen.kt      —— 读 prefs，并在消息列表底下垫一层可选背景图（Coil）。

幂等：已经打过补丁的文件会被跳过。锚点找不到时会报错并原样不动。
不负责编译，只负责改源码。
"""
import sys, os

MARK = "chat-background-patch"

def patch(path, edits):
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if MARK in src:
        print(f"  [skip] {path} 已打过补丁")
        return
    out = src
    for old, new in edits:
        if old not in out:
            raise SystemExit(f"锚点未找到，未做任何修改：\n{path}\n---\n{old[:200]}")
        if out.count(old) != 1:
            raise SystemExit(f"锚点不唯一（{out.count(old)} 处），未做任何修改：\n{path}\n---\n{old[:200]}")
        out = out.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(out)
    print(f"  [ok]   {path}")

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    app = os.path.join(root, "src/android/app/src/main/java/com/openminis/app")
    appearance = os.path.join(app, "ui/settings/AppearanceScreen.kt")
    chat = os.path.join(app, "ui/chat/ChatScreen.kt")
    for p in (appearance, chat):
        if not os.path.isfile(p):
            raise SystemExit(f"找不到文件（请在仓库根目录运行）：{p}")

    # ---------- 1) AppearanceScreen.kt ----------
    imports_anchor = "import androidx.compose.runtime.setValue\n"
    imports_add = imports_anchor + (
        "import androidx.activity.compose.rememberLauncherForActivityResult\n"
        "import androidx.activity.result.contract.ActivityResultContracts\n"
        "import androidx.compose.material.icons.outlined.Image\n"
    )

    const_anchor = 'const val KEY_LANGUAGE = "app_language"\n'
    const_add = const_anchor + (
        "// [chat-background-patch]\n"
        'const val KEY_CHAT_BACKGROUND_URI = "chat_background_uri"      // String, "" = 无\n'
        'const val KEY_CHAT_BACKGROUND_ALPHA = "chat_background_alpha"  // Int 0..255, default 255\n'
    )

    helper_anchor = (
        "fun getThemeMode(context: Context): Int =\n"
        "    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)\n"
    )
    helper_add = helper_anchor + (
        "\n// [chat-background-patch]\n"
        "fun getChatBackgroundUri(context: Context): String =\n"
        '    getAppearancePrefs(context).getString(KEY_CHAT_BACKGROUND_URI, "") ?: ""\n'
        "\n"
        "fun getChatBackgroundAlpha(context: Context): Int =\n"
        "    getAppearancePrefs(context).getInt(KEY_CHAT_BACKGROUND_ALPHA, 255)\n"
    )

    state_anchor = (
        "    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.current(context)) }\n"
    )
    state_add = state_anchor + (
        "    // [chat-background-patch] 聊天背景：URI + 不透明度\n"
        '    var chatBackgroundUri by remember { mutableStateOf(prefs.getString(KEY_CHAT_BACKGROUND_URI, "") ?: "") }\n'
        "    var chatBackgroundAlpha by remember { mutableIntStateOf(prefs.getInt(KEY_CHAT_BACKGROUND_ALPHA, 255)) }\n"
        "    val bgPicker = rememberLauncherForActivityResult(\n"
        "        ActivityResultContracts.OpenDocument()\n"
        "    ) { uri ->\n"
        "        if (uri != null) {\n"
        "            try {\n"
        "                context.contentResolver.takePersistableUriPermission(\n"
        "                    uri,\n"
        "                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,\n"
        "                )\n"
        "            } catch (_: Exception) {\n"
        "            }\n"
        "            chatBackgroundUri = uri.toString()\n"
        "            prefs.edit().putString(KEY_CHAT_BACKGROUND_URI, uri.toString()).apply()\n"
        "        }\n"
        "    }\n"
    )

    section_anchor = "        // -- App Icon (T-android-dynamic-app-icon) --\n"
    section_add = (
        "        // -- Chat Background (chat-background-patch) --\n"
        "        SettingsSection(\n"
        '            header = "聊天背景",\n'
        '            footer = "选一张图垫在消息底下，只存在本机。",\n'
        "        ) {\n"
        "            SettingsRow(\n"
        "                icon = Icons.Outlined.Image,\n"
        "                iconColor = tileBlue,\n"
        '                title = if (chatBackgroundUri.isEmpty()) "选择背景图片" else "更换背景图片",\n'
        '                subtitle = if (chatBackgroundUri.isEmpty()) "未设置" else "已设置",\n'
        "                onClick = { bgPicker.launch(arrayOf(\"image/*\")) },\n"
        "                showChevron = true,\n"
        "                showDivider = chatBackgroundUri.isNotEmpty(),\n"
        "            )\n"
        "            if (chatBackgroundUri.isNotEmpty()) {\n"
        "                Slider(\n"
        "                    value = chatBackgroundAlpha.toFloat(),\n"
        "                    onValueChange = {\n"
        "                        val v = it.roundToInt().coerceIn(0, 255)\n"
        "                        chatBackgroundAlpha = v\n"
        "                        prefs.edit().putInt(KEY_CHAT_BACKGROUND_ALPHA, v).apply()\n"
        "                    },\n"
        "                    valueRange = 0f..255f,\n"
        "                    modifier = Modifier.padding(horizontal = 16.dp),\n"
        "                )\n"
        "                Row(\n"
        "                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),\n"
        "                    horizontalArrangement = Arrangement.Center,\n"
        "                ) {\n"
        "                    MinisTextButton(onClick = {\n"
        '                        chatBackgroundUri = ""\n'
        "                        prefs.edit().remove(KEY_CHAT_BACKGROUND_URI).apply()\n"
        "                    }) {\n"
        '                        Text("清除背景", color = MaterialTheme.colorScheme.error)\n'
        "                    }\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "\n" + section_anchor
    )

    patch(appearance, [
        (imports_anchor, imports_add),
        (const_anchor, const_add),
        (helper_anchor, helper_add),
        (state_anchor, state_add),
        (section_anchor, section_add),
    ])

    # ---------- 2) ChatScreen.kt ----------
    b1_anchor = (
        "    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }\n"
    )
    b1_add = b1_anchor + (
        "    // [chat-background-patch] 聊天背景\n"
        "    var chatBackgroundUri by remember { mutableStateOf(com.openminis.app.ui.settings.getChatBackgroundUri(context)) }\n"
        "    var chatBackgroundAlpha by remember { mutableStateOf(com.openminis.app.ui.settings.getChatBackgroundAlpha(context)) }\n"
    )

    b2_anchor = (
        "                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)\n"
    )
    b2_add = b2_anchor + (
        '                com.openminis.app.ui.settings.KEY_CHAT_BACKGROUND_URI -> chatBackgroundUri = sp.getString(key, "") ?: ""\n'
        "                com.openminis.app.ui.settings.KEY_CHAT_BACKGROUND_ALPHA -> chatBackgroundAlpha = sp.getInt(key, 255)\n"
    )

    b3_anchor = (
        "                .onGloballyPositioned { chatPaneWidthPx = it.size.width },\n"
        "        ) {\n"
        "        Column(\n"
    )
    b3_add = (
        "                .onGloballyPositioned { chatPaneWidthPx = it.size.width },\n"
        "        ) {\n"
        "        // [chat-background-patch] 背景图垫在消息列表下；半透明时与底色自然混合\n"
        "        if (chatBackgroundUri.isNotEmpty()) {\n"
        "            coil.compose.AsyncImage(\n"
        "                model = chatBackgroundUri,\n"
        "                contentDescription = null,\n"
        "                contentScale = androidx.compose.ui.layout.ContentScale.Crop,\n"
        "                alpha = (chatBackgroundAlpha / 255f).coerceIn(0f, 1f),\n"
        "                modifier = Modifier.fillMaxSize(),\n"
        "            )\n"
        "        }\n"
        "        Column(\n"
    )

    patch(chat, [(b1_anchor, b1_add), (b2_anchor, b2_add), (b3_anchor, b3_add)])

    print("\n完成。接着：把 .github/workflows/build-apk.yml 放进仓库，提交并 push。")

if __name__ == "__main__":
    main()

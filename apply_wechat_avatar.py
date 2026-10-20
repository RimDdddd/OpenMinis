#!/usr/bin/env python3
"""
apply_wechat_avatar.py — 给 OpenMinis (Android) 加「微信式聊天头像」。

用法（在 OpenMinis 仓库根目录执行）：
    python3 apply_wechat_avatar.py .

做什么：
  1) AppearanceScreen.kt —— 加一个 prefs 键 + helper + 「聊天头像」设置分区
     （只给"我"这一侧选图；助手那一侧直接用 Soul 里设的图标）。
  2) ChatAssistantMessageUI.kt —— AssistantHeader 的头像从 18dp 放大到 34dp
     （Soul 图标，圆角），名字仍在旁边。
  3) ChatUserMessageUI.kt —— 用户气泡右侧加 40dp 圆角头像（微信式），气泡左让 46dp。

幂等：已经打过补丁的文件会被跳过。锚点找不到时报错并原样不动。
只改源码，不负责编译。
"""
import sys, os

MARK = "wechat-avatar-patch"


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
            raise SystemExit(
                f"锚点不唯一（{out.count(old)} 处），未做任何修改：\n{path}\n---\n{old[:200]}"
            )
        out = out.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(out)
    print(f"  [ok]   {path}")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    app = os.path.join(root, "src/android/app/src/main/java/com/openminis/app")
    appearance = os.path.join(app, "ui/settings/AppearanceScreen.kt")
    assistant = os.path.join(app, "ui/chat/ChatAssistantMessageUI.kt")
    user = os.path.join(app, "ui/chat/ChatUserMessageUI.kt")
    for p in (appearance, assistant, user):
        if not os.path.isfile(p):
            raise SystemExit(f"找不到文件（请在仓库根目录运行）：{p}")

    # ---------- 1) AppearanceScreen.kt ----------
    imports_anchor = "import androidx.compose.runtime.setValue\n"
    # 一个 import 都不加：文件里可能已经被别的补丁 import 过同样的名字
    # （重复 import 会让 Kotlin 报 "Conflicting import ... is ambiguous"），
    # 所以下面统一用全限定名调 rememberLauncherForActivityResult / ActivityResultContracts。

    const_anchor = 'const val KEY_LANGUAGE = "app_language"\n'
    const_add = const_anchor + (
        "// [wechat-avatar-patch]\n"
        'const val KEY_CHAT_AVATAR_USER_URI = "chat_avatar_user_uri"   // String, "" = 无\n'
    )

    helper_anchor = (
        "fun getThemeMode(context: Context): Int =\n"
        "    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)\n"
    )
    helper_add = helper_anchor + (
        "\n// [wechat-avatar-patch]\n"
        "fun getChatAvatarUserUri(context: Context): String =\n"
        '    getAppearancePrefs(context).getString(KEY_CHAT_AVATAR_USER_URI, "") ?: ""\n'
    )

    state_anchor = (
        "    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.current(context)) }\n"
    )
    state_add = state_anchor + (
        "    // [wechat-avatar-patch] 聊天里「我」那一侧的头像\n"
        '    var chatAvatarUserUri by remember { mutableStateOf(prefs.getString(KEY_CHAT_AVATAR_USER_URI, "") ?: "") }\n'
        "    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(\n"
        "        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()\n"
        "    ) { uri ->\n"
        "        if (uri != null) {\n"
        "            try {\n"
        "                context.contentResolver.takePersistableUriPermission(\n"
        "                    uri,\n"
        "                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,\n"
        "                )\n"
        "            } catch (_: Exception) {\n"
        "            }\n"
        "            chatAvatarUserUri = uri.toString()\n"
        "            prefs.edit().putString(KEY_CHAT_AVATAR_USER_URI, uri.toString()).apply()\n"
        "        }\n"
        "    }\n"
    )

    section_anchor = "        // -- App Icon (T-android-dynamic-app-icon) --\n"
    section_add = (
        "        // -- Chat Avatar (wechat-avatar-patch) --\n"
        "        SettingsSection(\n"
        '            header = "聊天头像",\n'
        '            footer = "气泡旁边的小头像。助手那一侧用 Soul 里设的图标，这里只设「我」这一侧。",\n'
        "        ) {\n"
        "            SettingsRow(\n"
        "                icon = Icons.Outlined.ChatBubbleOutline,\n"
        "                iconColor = tileBlue,\n"
        '                title = if (chatAvatarUserUri.isEmpty()) "选择我的头像" else "更换我的头像",\n'
        '                subtitle = if (chatAvatarUserUri.isEmpty()) "未设置" else "已设置",\n'
        '                onClick = { avatarPicker.launch(arrayOf("image/*")) },\n'
        "                showChevron = true,\n"
        "                showDivider = chatAvatarUserUri.isNotEmpty(),\n"
        "            )\n"
        "            if (chatAvatarUserUri.isNotEmpty()) {\n"
        "                Row(\n"
        "                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),\n"
        "                    horizontalArrangement = Arrangement.Center,\n"
        "                ) {\n"
        "                    MinisTextButton(onClick = {\n"
        '                        chatAvatarUserUri = ""\n'
        "                        prefs.edit().remove(KEY_CHAT_AVATAR_USER_URI).apply()\n"
        "                    }) {\n"
        '                        Text("清除头像", color = MaterialTheme.colorScheme.error)\n'
        "                    }\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "\n" + section_anchor
    )

    patch(appearance, [
        (const_anchor, const_add),
        (helper_anchor, helper_add),
        (state_anchor, state_add),
        (section_anchor, section_add),
    ])

    # ---------- 2) ChatAssistantMessageUI.kt ----------
    header_old = (
        "        com.openminis.app.ui.settings.SoulIconGlyph(\n"
        "            icon = soulMeta.icon,\n"
        "            sizeDp = 18.dp,\n"
        "            emojiSp = 15.sp,\n"
        "            sparkleTint = sparkleGradient,\n"
        "        )\n"
        "        Spacer(modifier = Modifier.width(6.dp))\n"
    )
    header_new = (
        "        // [wechat-avatar-patch] 微信式：头像放大、与名字并排\n"
        "        com.openminis.app.ui.settings.SoulIconGlyph(\n"
        "            icon = soulMeta.icon,\n"
        "            sizeDp = 40.dp,\n"
        "            emojiSp = 30.sp,\n"
        "            sparkleTint = sparkleGradient,\n"
        "        )\n"
        "        Spacer(modifier = Modifier.width(8.dp))\n"
    )
    patch(assistant, [(header_old, header_new)])

    # ---------- 3) ChatUserMessageUI.kt ----------
    bubble_old = (
        "        val bubbleMaxWidth = this.maxWidth * 0.8f\n"
        "    Row(\n"
        "        modifier = Modifier.fillMaxWidth(),\n"
        "    ) {\n"
    )
    bubble_new = (
        "        val bubbleMaxWidth = this.maxWidth * 0.8f\n"
        "    // [wechat-avatar-patch] 「我」这一侧的头像，贴在气泡右边\n"
        "    val avatarCtx = LocalContext.current\n"
        "    val chatAvatarUserUri = com.openminis.app.ui.settings.getChatAvatarUserUri(avatarCtx)\n"
        "    if (chatAvatarUserUri.isNotEmpty()) {\n"
        "        coil.compose.AsyncImage(\n"
        "            model = chatAvatarUserUri,\n"
        "            contentDescription = null,\n"
        "            contentScale = androidx.compose.ui.layout.ContentScale.Crop,\n"
        "            modifier = Modifier\n"
        "                .align(Alignment.TopEnd)\n"
        "                .size(40.dp)\n"
        "                .clip(RoundedCornerShape(6.dp)),\n"
        "        )\n"
        "    }\n"
        "    Row(\n"
        "        modifier = Modifier\n"
        "            .fillMaxWidth()\n"
        "            .then(\n"
        "                if (chatAvatarUserUri.isNotEmpty()) Modifier.padding(end = 46.dp)\n"
        "                else Modifier\n"
        "            ),\n"
        "    ) {\n"
    )
    patch(user, [(bubble_old, bubble_new)])

    print("\n完成。检查：设置 → 外观 多出「聊天头像」；聊天里助手头像 40dp，用户头像在右边。")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
apply_stable_signing.py — 给 OpenMinis (Android) 用固定签名的 debug 构建。

用法（在 OpenMinis 仓库根目录执行）：
    python3 apply_stable_signing.py .

为什么：
  AGP 默认签名用 `~/.android/debug.keystore`。CI runner 每次都是新机器，
  没这个文件就用随机新 key 生成一个 —— 于是每次云端编出来的 APK 签名都不同，
  想装新版必须先卸载旧版（数据全没）。

  这里改成读仓库里提交的 `src/android/.signing/debug.keystore`：
  只要这个文件在仓库里，每次构建签名一致，新版可以**覆盖安装**，聊天记录留着。

  没有这个文件也不会坏：条件判断不成立时退回 AGP 默认行为，就是以前那样。

幂等：已经打过补丁会跳过。锚点找不到时报错并原样不动。
"""
import sys, os

MARK = "stable-signing-patch"


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
    gradle = os.path.join(root, "src/android/app/build.gradle.kts")
    if not os.path.isfile(gradle):
        raise SystemExit(f"找不到文件（请在仓库根目录运行）：{gradle}")

    old = (
        "    buildTypes {\n"
        '        getByName("debug") {\n'
        '            buildConfigField("boolean", "DEV_TOOLS", "true")\n'
        "        }\n"
    )
    # keystore 两个位置都认：仓库根的 debug.keystore（最好传，网页上传一步到位）
    # 或 src/android/.signing/debug.keystore（想藏起来的话）。
    ks_expr = (
        'val ks = rootProject.file("../debug.keystore").takeIf { it.exists() }'
        ' ?: rootProject.file(".signing/debug.keystore").takeIf { it.exists() }\n'
    )
    new = (
        "    // [stable-signing-patch] 固定签名：仓库里带 debug.keystore 时用同一把 key，\n"
        "    // CI 重编出的 APK 可以覆盖安装（不用卸载清数据）。\n"
        "    signingConfigs {\n"
        '        create("stableDebug") {\n'
        "            " + ks_expr +
        "            if (ks != null) {\n"
        "                storeFile = ks\n"
        '                storePassword = "android"\n'
        '                keyAlias = "androiddebugkey"\n'
        '                keyPassword = "android"\n'
        "            }\n"
        "        }\n"
        "    }\n"
        "\n"
        "    buildTypes {\n"
        '        getByName("debug") {\n'
        '            buildConfigField("boolean", "DEV_TOOLS", "true")\n'
        "            " + ks_expr +
        '            if (ks != null) signingConfig = signingConfigs.getByName("stableDebug")\n'
        "        }\n"
    )
    patch(gradle, [(old, new)])
    print(
        "\n完成。把 debug.keystore 传到仓库根目录就行（源码里两个位置都认）。"
        "没有也能编，只是签名每次不同。"
    )


if __name__ == "__main__":
    main()

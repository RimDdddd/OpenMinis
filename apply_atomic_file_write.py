#!/usr/bin/env python3
"""
apply_atomic_file_write.py — 修掉 OpenMinis (Android) file_edit / file_write 的丢写。

用法（在 OpenMinis 仓库根目录执行）：
    python3 apply_atomic_file_write.py .

问题：两个工具都是「readText → 改 → writeText」。writeText 先截断再写、不等落盘，
两笔编辑重叠时各读同一份快照，后写的静默胜出——前一笔丢了，而两个调用都报成功；
在 FUSE 挂载上同一个截断窗口还可能在文件尾留下半截 UTF-8。

改法：
  1) 新增 SafeFileWriter.kt —— 按路径加锁 + 写临时文件 + fsync + 原子 rename + 回读校验。
  2) FileEditTool.kt —— 整个读改写进锁里，改用原子写，校验不过就报错。
  3) FileWriteTool.kt —— 覆写走原子写，append 也 fsync。

幂等：打过的会跳过；锚点找不到时原样不动并报错。
不负责编译，只改源码。
"""
import sys, os

MARK = "[T-file-edit-atomic-write]"

SAFE_FILE_WRITER = '''package com.openminis.app.tools

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * [T-file-edit-atomic-write]
 *
 * Shared atomic-write helper for the `file_write` / `file_edit` tools.
 *
 * Why this exists: both tools used to do `File.readText()` -> mutate ->
 * `File.writeText()`. `writeText` truncates in place and returns long before the
 * bytes are durable, so two edits that overlap - two tool calls in flight, or a
 * retry racing the first attempt - each read the same snapshot and the last
 * writer silently wins: the earlier edit is lost while **both** calls report
 * success. On mounted (FUSE) folders the same truncate-then-write window can
 * leave a torn tail behind (a stray partial UTF-8 sequence at EOF).
 *
 * This helper makes the read-modify-write serialized per path and the write
 * atomic, durable and verified:
 *
 *   1. a per-canonical-path lock serializes concurrent edits of the same file;
 *   2. the new bytes go to a sibling temp file, are fsync'd, then rename()d over
 *      the target (atomic on the same filesystem);
 *   3. the result is read back and byte-compared before we report success.
 *
 * A failed verification returns `false`, and the caller reports an error rather
 * than a false "success" - a wrong artifact is strictly worse than none.
 */
object SafeFileWriter {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Run [block] while holding this path's lock, so that overlapping edits to
     * the same file serialize instead of clobbering each other.
     *
     * The lock key is the canonical path, so a symlinked mount and its target
     * resolve to the same lock. The holder cleans up its map entry on release
     * when nobody is queued, so the map cannot grow without bound.
     */
    fun <T> withPathLock(file: File, block: () -> T): T {
        val key = try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
            if (!lock.hasQueuedThreads()) locks.remove(key, lock)
        }
    }

    /**
     * Write [text] to [file] atomically and durably, then verify the bytes on
     * disk. Returns false if the bytes could not be confirmed afterwards.
     *
     * The caller is responsible for holding [withPathLock] when the write is the
     * second half of a read-modify-write cycle.
     */
    fun writeText(file: File, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val dir = file.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File(dir ?: File("/"), "." + file.name + ".minis-tmp-" + System.nanoTime())
        try {
            FileOutputStream(tmp, false).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // Some FUSE / legacy-storage backends refuse rename() over an
                // existing file. Fall back to an in-place overwrite, still with
                // an explicit fsync, and drop the temp file.
                FileOutputStream(file, false).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            AppLogger.warning("SafeFileWriter", "atomic write failed for ${file.absolutePath}: ${e.message}")
            return false
        }

        // Verify: the bytes on disk must equal what we intended to write.
        return try {
            file.readBytes().contentEquals(bytes)
        } catch (e: Exception) {
            AppLogger.warning("SafeFileWriter", "verify failed for ${file.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Append [text] durably. Append is not a read-modify-write cycle, so it does
     * not need the atomic dance - but it still fsyncs so the caller is not told
     * "success" before the bytes survive a crash.
     */
    fun appendText(file: File, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return try {
            FileOutputStream(file, true).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            true
        } catch (e: Exception) {
            AppLogger.warning("SafeFileWriter", "append failed for ${file.absolutePath}: ${e.message}")
            false
        }
    }
}
'''

EDIT_TOOL_OLD = '''            val content = file.readText()

            // Count occurrences
            var count = 0
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(oldString, searchFrom)
                if (idx < 0) break
                count++
                searchFrom = idx + oldString.length
            }

            if (count == 0) {
                return ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
            }

            if (count > 1 && !replaceAll) {
                return ToolExecutionResult(
                    "Error: old_string found $count times in $path. Use replace_all=true to replace all occurrences, " +
                        "or provide a more specific old_string that matches exactly once.",
                    false, toolTitle = toolTitle
                )
            }

            val newContent = if (replaceAll) {
                content.replace(oldString, newString)
            } else {
                content.replaceFirst(oldString, newString)
            }

            file.writeText(newContent)
            val replacements = if (replaceAll) count else 1
            ToolExecutionResult(
                "Edited $path ($replacements replacement(s), ${newContent.length} bytes)",
                true, toolTitle = toolTitle
            )'''

EDIT_TOOL_NEW = '''            // [T-file-edit-atomic-write] The whole read-modify-write runs under a
            // per-path lock, and the write is atomic + durable + verified. Before
            // this, two overlapping edits each read the same snapshot and the
            // last one silently won - the other was lost while both calls
            // reported success, and a torn tail could survive on FUSE mounts.
            return SafeFileWriter.withPathLock(file) {
                val content = file.readText()

                // Count occurrences
                var count = 0
                var searchFrom = 0
                while (true) {
                    val idx = content.indexOf(oldString, searchFrom)
                    if (idx < 0) break
                    count++
                    searchFrom = idx + oldString.length
                }

                if (count == 0) {
                    return@withPathLock ToolExecutionResult(
                        "Error: old_string not found in $path", false, toolTitle = toolTitle
                    )
                }

                if (count > 1 && !replaceAll) {
                    return@withPathLock ToolExecutionResult(
                        "Error: old_string found $count times in $path. Use replace_all=true to replace all occurrences, " +
                            "or provide a more specific old_string that matches exactly once.",
                        false, toolTitle = toolTitle
                    )
                }

                val newContent = if (replaceAll) {
                    content.replace(oldString, newString)
                } else {
                    content.replaceFirst(oldString, newString)
                }

                if (!SafeFileWriter.writeText(file, newContent)) {
                    return@withPathLock ToolExecutionResult(
                        "Error: edited $path but the result could not be verified on disk " +
                            "(write was not durable). The file was left unchanged.",
                        false, toolTitle = toolTitle
                    )
                }
                val replacements = if (replaceAll) count else 1
                ToolExecutionResult(
                    "Edited $path ($replacements replacement(s), ${newContent.length} bytes)",
                    true, toolTitle = toolTitle
                )
            }'''

WRITE_TOOL_OLD = '''            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }'''

WRITE_TOOL_NEW = '''            // [T-file-edit-atomic-write] Overwrite goes through the atomic +
            // fsync'd + verified writer; append stays a plain append (it is not a
            // read-modify-write cycle) but is fsync'd. Both are taken under the
            // path lock so a write cannot interleave with an overlapping edit.
            val writeOk = SafeFileWriter.withPathLock(file) {
                if (append) SafeFileWriter.appendText(file, content)
                else SafeFileWriter.writeText(file, content)
            }
            if (!writeOk) {
                return ToolExecutionResult(
                    "Error: wrote $path but the result could not be verified on disk (write was not durable).",
                    false, toolTitle = toolTitle
                )
            }'''


def patch_file(path, edits):
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if MARK in src:
        print(f"  [skip] {os.path.basename(path)} 已打过补丁")
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
    print(f"  [ok]   {os.path.basename(path)}")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    tools = os.path.join(root, "src/android/app/src/main/java/com/openminis/app/tools")
    edit_tool = os.path.join(tools, "FileEditTool.kt")
    write_tool = os.path.join(tools, "FileWriteTool.kt")
    safe_writer = os.path.join(tools, "SafeFileWriter.kt")

    for p in (edit_tool, write_tool):
        if not os.path.isfile(p):
            raise SystemExit(f"找不到文件（请在仓库根目录运行）：{p}")

    # 1) 新增 SafeFileWriter.kt
    if os.path.isfile(safe_writer):
        with open(safe_writer, "r", encoding="utf-8") as f:
            if MARK in f.read():
                print("  [skip] SafeFileWriter.kt 已存在")
            else:
                raise SystemExit("SafeFileWriter.kt 已存在但不是本项目生成的，拒绝覆盖。")
    else:
        with open(safe_writer, "w", encoding="utf-8") as f:
            f.write(SAFE_FILE_WRITER)
        print("  [ok]   SafeFileWriter.kt（新增）")

    # 2) FileEditTool.kt / 3) FileWriteTool.kt
    patch_file(edit_tool, [(EDIT_TOOL_OLD, EDIT_TOOL_NEW)])
    patch_file(write_tool, [(WRITE_TOOL_OLD, WRITE_TOOL_NEW)])

    print("\n完成。提交并 push，然后跑 .github/workflows/ 里的 build-apk workflow。")


if __name__ == "__main__":
    main()

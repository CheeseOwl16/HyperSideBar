package com.lsp.hypersidebar.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "ShortcutRootCatalog"

/**
 * 快捷方式清单的 root 枚举目录（2026-10-20，OS4 桌面去 Java 化后的选择器数据源）。
 *
 * ## 背景（真机实证，marble OS4 8.01.02.7719）
 * 选择器「应用快捷方式」页的清单原来只由桌面进程的桥提供
 * （[com.lsp.hypersidebar.ui.settings.ManifestShortcutsBridge] 请求 → launcher 进程
 * `LauncherApps.getShortcuts` → 应答）。OS4 桌面被 Rust 重写、无 Java 层 ⇒ 桥结构性
 * 缺席 ⇒ 选择器恒空（logcat 实锤 `loadManifestShortcuts failed: SecurityException`，
 * 直查被系统拒：只有默认桌面能查）。
 *
 * ## 本方案的依据
 * [ShortcutLauncher.launchShortcutIdViaRoot] 的 root 兜底链（2026-10-05 marble 真机
 * 验证）：`su -c 'cmd shortcut get-shortcuts <pkg>'` 以 root 身份查 ShortcutService，
 * **不做"只有默认桌面"的权限检查**。同一原语反过来用 = 清单查询。命令头
 * （`cmd shortcut get-shortcuts [--user USER_ID] [--flags FLAGS] PACKAGE`）从本机
 * services.jar 反编译实锤；输出块格式（`ShortcutInfo {id=... act=... cmp=... pkg=...`）
 * 与启动兜底的解析词表同源。
 *
 * ## 分类口径（与桥对齐）
 * 桥的 parse()：`s`（shortcutId）存在 ⇒ SHORTCUT_ID 启动（OS4 走 root 兜底链），
 * 否则 COMPONENT 直启。`cmd shortcut` 的每个块都有 id ⇒ **一律发 s**；`cmp=` 只是
 * 顺带信息（am start 链自己会再解析 intent）。所以 t 恒为 "d"、c 尽力而为。
 */
object ShortcutRootCatalog {

    /** 单条目录项（与 [com.lsp.hypersidebar.ui.settings.ManifestShortcutsBridge.parse] 的 JSON 字段对齐）。 */
    data class Entry(
        val packageName: String,
        val shortcutId: String,
        val className: String,
        val label: String
    )

    /** 批查flags：manifest | dynamic | pinned（与桌面桥的查询范围一致）。 */
    private const val MATCH_FLAGS = 7

    // ── 纯核：文本 → 条目（JUnit 覆盖；正则词表与启动兜底的已验证解析同源）────────

    /**
     * 解析单个包的 `cmd shortcut get-shortcuts` 输出。
     *
     * 标签来源降级链：`shortLabel=` → `text=` → `title=` → 空（调用方回落到应用名）。
     * 【待真机样本校验】label 字段名未从真实输出确认（设备离线期间按 AOSP
     * ShortcutInfo.toString 的常见字段先写全，有样本后收敛）。
     */
    fun parseEntries(packageName: String, dump: String): List<Entry> {
        if (dump.isBlank()) return emptyList()
        val blockStart = "ShortcutInfo {id="
        val entries = ArrayList<Entry>()
        var idx = dump.indexOf(blockStart)
        while (idx >= 0) {
            val next = dump.indexOf(blockStart, idx + blockStart.length)
            val block = dump.substring(idx, if (next < 0) dump.length else next)
            parseBlock(packageName, block)?.let { entries.add(it) }
            idx = next
        }
        return entries
    }

    /** 单块解析；无 id 的块丢弃。 */
    internal fun parseBlock(packageName: String, block: String): Entry? {
        val sid = Regex("id=([^,}\\s]+)").find(block)?.groupValues?.get(1) ?: return null
        if (sid.isEmpty()) return null
        val cmp = Regex("cmp=([^}\\s]+)").find(block)?.groupValues?.get(1).orEmpty()
        val label = LABEL_SOURCES.firstNotNullOfOrNull { src ->
            Regex("$src=([^,}\\n]+)").find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        return Entry(packageName, sid, cmp, label)
    }

    private val LABEL_SOURCES = listOf("shortLabel", "text", "title")

    /**
     * 批量 shell 正文：`==PKG <pkg>` 分隔 + 逐包查询。单次 su exec（N 次 exec 的
     * 进程开销远大于 binder 查询本身）。
     *
     * ⚠ 转义陷阱（2026-10-20 真机首跑实锤）：Kotlin 侧写成 `\$p` 才是**单个美元符**
     * `$p`（交给内层 sh 展开）；写成 `\\\$p` 会输出字面 `\$p`，sh 里 `"\$p"` 又还原成
     * 字面 `$p` ⇒ 250 个包里打印 250 次 `==PKG $p`、查询的是“名为 $p 的包”（返回
     * "Success"）、解析 0 条。
     */
    internal fun buildShell(packages: List<String>): String =
        packages.joinToString(" ") { pkg -> "'$pkg'" }.let { pkgs ->
            "for p in $pkgs; do echo \"==PKG \$p\"; cmd shortcut get-shortcuts --flags $MATCH_FLAGS \"\$p\" 2>&1; done"
        }

    // ── 胶水：枚举 → 批查 → 应答回填（复用桥的 onReply：状态 + 本地缓存一体）──────

    /**
     * 全量刷新：枚举 launcher 可见应用 → 单次 su 批查 → 回填
     * [com.lsp.hypersidebar.ui.settings.ManifestShortcutsBridge]（JSON 与桌面桥应答
     * 完全同形状，UI 零改动）。返回是否拿到了条目。
     *
     * 调用方：选择器页（IO 线程）；仅在本进程可用 su 时才有意义（OS4 即 root 前提，
     * 用户已确认接受）。
     */
    fun refresh(context: Context): Boolean {
        if (!ShortcutLauncher.isRootAvailable()) {
            HLog.w(TAG, "root unavailable ⇒ shortcut catalog skipped")
            return false
        }
        val pkgs = enumerateLauncherPackages(context)
        if (pkgs.isEmpty()) {
            HLog.w(TAG, "no launcher-visible packages ⇒ catalog empty")
            return false
        }
        val out = rootExec(buildShell(pkgs))
        if (out.isNullOrBlank()) {
            HLog.w(TAG, "su exec returned nothing (${pkgs.size} packages)")
            return false
        }
        // 诊断三计数 + 输出样本（真机首轮实锤用；输出异常时唯一的现场证据）
        val markers = out.lineSequence().count { it.startsWith("==PKG ") }
        val blocks = Regex("ShortcutInfo \\{id=").findAll(out).count()
        HLog.i(TAG, "catalog: pkgs=${pkgs.size} bytes=${out.length} markers=$markers blocks=$blocks")
        if (blocks == 0) {
            HLog.w(TAG, "catalog sample: ${out.take(240).replace('\n', '|')}")
            return false
        }
        // 按 ==PKG 标记切回逐包输出；未带标记的尾部（理论上没有）忽略
        val json = buildJson(context, out, pkgs.toSet())
        if (json == null) {
            HLog.w(TAG, "catalog parsed 0 entries from ${out.length}B output")
            return false
        }
        com.lsp.hypersidebar.ui.settings.ManifestShortcutsBridge.onReply(context, json)
        HLog.i(TAG, "shortcut catalog refreshed: ${pkgs.size} packages")
        return true
    }

    /** launcher 可见应用包名（去重；QUERY_ALL_PACKAGES 保证可见性）。 */
    private fun enumerateLauncherPackages(context: Context): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .asSequence()
                .mapNotNull { it?.activityInfo?.applicationInfo?.packageName }
                .filter { it.isNotBlank() }
                .toCollection(LinkedHashSet())
                .toList()
        }.getOrElse {
            HLog.w(TAG, "queryIntentActivities failed: ${it.message}")
            emptyList()
        }
    }

    /** 输出 → 桥 JSON；用真实查到的包名做键过滤（防止解析到标记行之外的杂音）。 */
    private fun buildJson(context: Context, output: String, knownPkgs: Set<String>): String? {
        val pm = context.packageManager
        val arr = org.json.JSONArray()
        var count = 0
        var pkg: String? = null
        val buf = StringBuilder()
        fun flush() {
            val p = pkg ?: return
            if (buf.isNotBlank() && p in knownPkgs) {
                for (e in parseEntries(p, buf.toString())) {
                    if (e.packageName !in knownPkgs) continue
                    val appLabel = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(e.packageName, 0)).toString()
                    }.getOrDefault(e.packageName)
                    arr.put(
                        org.json.JSONObject()
                            .put("p", e.packageName)
                            .put("c", e.className)
                            .put("l", e.label.ifEmpty { appLabel })
                            .put("s", e.shortcutId)
                            .put("t", "d")
                            .put("a", appLabel)
                    )
                    count++
                }
            }
            buf.setLength(0)
        }
        output.lineSequence().forEach { line ->
            if (line.startsWith("==PKG ")) {
                flush()
                pkg = line.removePrefix("==PKG ").trim()
            } else if (pkg != null) {
                buf.appendLine(line)
            }
        }
        flush()
        if (count == 0) return null
        return arr.toString()
    }

    /** 单次 su 批查（与启动兜底同款 ProcessBuilder 模式；stdout 全量返回）。 */
    private fun rootExec(shellBody: String): String? = try {
        val process = ProcessBuilder(listOf("su", "-c", shellBody)).start()
        try {
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0) {
                HLog.w(TAG, "su exec exit=$exit")
                null
            } else stdout
        } finally {
            process.destroy()
        }
    } catch (e: Exception) {
        HLog.w(TAG, "su exec failed: ${e.message}")
        null
    }
}

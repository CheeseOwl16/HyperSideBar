package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShortcutRootCatalog] 纯核测试：文本解析与 shell 构造。
 *
 * 词表（id=/act=/cmp=/pkg=/PersistableBundle）与
 * [ShortcutLauncher.launchShortcutIdViaRoot] 的启动兜底解析**同源**（2026-10-05
 * marble 真机验证过）。label 字段名【待真机样本校验】——测试只覆盖“有无均可”
 * 两条路径，不钉死具体字段名。
 */
class ShortcutRootCatalogTest {

    /** 形如 `cmd shortcut get-shortcuts` 输出的样例（块结构按启动兜底的实锤词表构造）。 */
    private fun sampleDump(vararg ids: String): String = ids.joinToString("\n") { id ->
        """
        |    ShortcutInfo {id=$id, flags=0x104, packageName=com.tencent.mm, userId=0,
        |        intents=#1 [
        |            (intent) Intent { act=android.intent.action.VIEW flg=0x14008000
        |                cmp=com.tencent.mm/com.tencent.mm.ui.LauncherUI
        |                pkg=com.tencent.mm }
        |        ]
        |    }
        """.trimMargin()
    } + "\n"

    @Test
    fun `parseEntries extracts id and component per block`() {
        val entries = ShortcutRootCatalog.parseEntries(
            "com.tencent.mm",
            sampleDump("nav_a", "nav_b")
        )
        assertEquals(2, entries.size)
        assertEquals("nav_a", entries[0].shortcutId)
        assertEquals("com.tencent.mm", entries[0].packageName)
        assertEquals(
            "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
            entries[0].className
        )
    }

    @Test
    fun `blocks without id are skipped`() {
        val dump = """
            |    garbage line without block start
            |    ShortcutInfo {id=, flags=0x0
            |    ShortcutInfo {id=real_one, flags=0x4
        """.trimMargin()
        val entries = ShortcutRootCatalog.parseEntries("com.tencent.mm", dump)
        assertEquals(1, entries.size)
        assertEquals("real_one", entries[0].shortcutId)
    }

    @Test
    fun `blank dump yields empty list`() {
        assertTrue(ShortcutRootCatalog.parseEntries("p", "").isEmpty())
        assertTrue(ShortcutRootCatalog.parseEntries("p", "   \n \n").isEmpty())
    }

    @Test
    fun `label falls back to empty when no known field matches`() {
        val entries = ShortcutRootCatalog.parseEntries("p", sampleDump("s1"))
        assertEquals(1, entries.size)
        assertEquals("", entries[0].label)
    }

    @Test
    fun `label picks up known label field`() {
        val dump = """
            |    ShortcutInfo {id=s9, flags=0x104,
            |        shortLabel=收付款,
            |        intents=#1 [ (intent) Intent { act=act.A cmp=p/.C pkg=p } ]
        """.trimMargin()
        val entries = ShortcutRootCatalog.parseEntries("p", dump)
        assertEquals("收付款", entries[0].label)
    }

    @Test
    fun `buildShell quotes packages and defers loop variable to shell`() {
        val shell = ShortcutRootCatalog.buildShell(listOf("com.a", "com.b"))
        assertTrue(shell.startsWith("for p in 'com.a' 'com.b'"))
        // 字面 `$p`（没有反斜杠）= 内层 sh 展开循环变量；2026-10-20 真机首跑实锤：
        // 带反斜杠会输出字面 "$p"、解析 0 条。
        assertTrue(shell.contains("echo \"==PKG \$p\""))
        assertTrue(shell.contains("\"\$p\""))
        assertFalse(shell.contains("\\\$p"))
        assertTrue(shell.contains("--flags 7"))
        assertFalse(shell.contains("null"))
    }

    @Test
    fun `packages marker splits per package when consuming batch output`() {
        // 模拟批查输出：两个 ==PKG 段，条目互不串包
        val batch = "==PKG com.a\n" + sampleDump("a1") + "==PKG com.b\n" + sampleDump("b1")
        val a = ShortcutRootCatalog.parseEntries("com.a", batch.substringAfter("==PKG com.a").substringBefore("==PKG com.b"))
        val b = ShortcutRootCatalog.parseEntries("com.b", batch.substringAfter("==PKG com.b"))
        assertEquals("a1", a.single().shortcutId)
        assertEquals("b1", b.single().shortcutId)
        assertEquals("com.a", a.single().packageName)
        assertEquals("com.b", b.single().packageName)
    }
}

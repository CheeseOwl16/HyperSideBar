package com.lsp.hypersidebar.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NativeRootProbe.ORIGINAL_PROLOGUE` 必须与 `app/src/main/cpp/launcher_profiles.h`
 * 逐字节一致。
 *
 * ## 为什么必须有这个测试
 * 那张表是**人工镜像**（C 头文件由 tools/gen_launcher_profiles.py 生成，但 Java 侧
 * 没有 codegen 管线）。漂移的后果是**假阳性**：报告会拿错误的参照值去比对目标地址，
 * 把"hook 还在"报成"已被打回文件映射"——正好是最需要准确的那个结论。
 *
 * 2026-10-11 实际发生过一次：表里只有第 1 步那 3 个锚点，而当时门控挂的是
 * `is_in_bottom_gesture_region` ⇒ 报告打"该锚点无 prologue 参照"，等于门控锚点的
 * 存活无从判断。所以同步关系必须由测试守着，不能靠注释提醒。
 *
 * 测试从模块工作目录（= app/）向上找仓库根，再解析头文件；找不到就 fail（不静默跳过，
 * 否则这条保护会在换环境后悄悄失效）。
 */
class NativePrologueMirrorTest {

    private fun headerFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/cpp/launcher_profiles.h")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 app/src/main/cpp/launcher_profiles.h（工作目录=${File(".").absolutePath}）" +
                "——本测试依赖仓库布局，不能在仓外跑"
        )
    }

    /** 从 C 头文件抽出 11 条锚点的前 16 字节 prologue（小写 hex） */
    private fun headerPrologues(): Map<String, String> {
        val text = headerFile().readText()
        // 形如：{"name", 0x1234, 188, {0xff, 0x83, ...}, "symbol"}
        val re = Regex("""\{"(\w+)",\s*0x[0-9a-fA-F]+,\s*\d+,\s*\{([^}]*)}""")
        val out = LinkedHashMap<String, String>()
        for (m in re.findAll(text)) {
            val name = m.groupValues[1]
            val bytes = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            // 头文件存满 32B，Java 侧只比对前 16B（PROBE_BYTES）
            assertTrue("$name prologue 应满 32B，实际 ${bytes.size}", bytes.size >= 16)
            out[name] = bytes.take(16).joinToString("") { b ->
                val v = b.removePrefix("0x").toInt(16)
                "%02x".format(v)
            }
        }
        return out
    }

    @Test
    fun mirrorMatchesHeaderExactly() {
        val header = headerPrologues()
        val mirror = NativeRootProbe.ORIGINAL_PROLOGUE

        assertTrue("头文件应解析出锚点（解析失败会让本测试变成空转）", header.isNotEmpty())

        // 键集合必须一致：缺一个就少守一个锚点，多一个就是引用了不存在的锚点
        assertEquals(
            "锚点名集合不一致：头文件独有=${header.keys - mirror.keys}，镜像独有=${mirror.keys - header.keys}",
            header.keys.sorted(),
            mirror.keys.sorted()
        )

        // 值必须逐字节一致
        for ((name, hex) in header) {
            assertEquals("锚点 $name 的 prologue 与 launcher_profiles.h 不一致", hex, mirror[name])
        }
    }

    @Test
    fun everyAnchorHasSixteenByteReference() {
        // PROBE_BYTES=16 ⇒ 每条参照都必须是 32 个 hex 字符，否则比对长度对不上
        for ((name, hex) in NativeRootProbe.ORIGINAL_PROLOGUE) {
            assertEquals("锚点 $name 的参照长度应为 32 个 hex 字符", 32, hex.length)
            assertTrue("锚点 $name 的参照应是小写 hex", hex.all { it in "0123456789abcdef" })
        }
    }
}

package com.lsp.hypersidebar.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * arrowfix 是「两半」结构：缺任何一半都**不会编译失败**，只会静默失效。
 *
 * 2026-10-13 真机踩过一次：清理 `recover` 档时误删 UP 支的 `if (fixed) g_arrow_due = 1;`，
 * 症状是 `HSBNative: arrow FIX #n state[…]=1→0` 照打、但 `arrow CLEANUP` 永不出现
 * ⇒ 松手后原生返回箭头残留（UP 被我们吞掉，launcher 自己那次收尾调用根本没发生）。
 *
 * 这条用例用源码文本守住两半 + 消费点，比字符串核验（只看 log 文案在不在）更靠近语义。
 */
class NativeArrowfixInvariantTest {

    private fun nativeSource(): File {
        val candidates = listOf(
            File("src/main/cpp/hypersidebar_native.c"),        // AGP 单测工作目录 = 模块目录
            File("app/src/main/cpp/hypersidebar_native.c")     // 从仓库根跑时的兜底
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 native 源码，试过：${candidates.joinToString { it.absolutePath }}")
    }

    @Test
    fun arrowfixKeepsBothHalves() {
        val src = nativeSource().readText()

        // 前半：抬手（UP）/取消（CANCEL）时把窗口状态字节 1/2 打成 0 ⇒ 走 launcher 自己的收尾支
        assertTrue(
            "缺少 hsb_arrow_state_release() 的调用（arrowfix 前半）：${nativeSource().absolutePath}",
            src.contains("hsb_arrow_state_release()")
        )

        // 后半：UP 被吞掉 ⇒ 必须在 stub 的吞分支补调一次原函数，否则收尾永远不会跑
        assertTrue(
            "缺少 `if (fixed) g_arrow_due = 1;`（arrowfix 后半被删 ⇒ 箭头残留）",
            src.contains("if (fixed) g_arrow_due = 1;")
        )
        assertTrue(
            "stub 吞分支没有消费 g_arrow_due（缺少 `g_arrow_due && g_orig` 判据）",
            src.contains("g_arrow_due && g_orig")
        )
        assertTrue(
            "缺少 arrow CLEANUP 补调日志（补调原函数跑收尾）",
            src.contains("arrow CLEANUP")
        )
    }
}

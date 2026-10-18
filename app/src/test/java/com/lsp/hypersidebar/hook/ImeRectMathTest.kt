package com.lsp.hypersidebar.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImeRectMath] 单测 —— 横屏浮窗键盘的正确处理（jadx `DisplayImeController` 实证的三条规则）。
 *
 * 覆盖：普通贴底模式不扩展、浮窗模式照 ROM 扩 80dp、空矩形绝不扩展（否则凭空造带）、
 * 高度恰等于导航栏高度算浮窗、导航栏高度未知(0)时退化为原始矩形。
 */
class ImeRectMathTest {

    private val density = 3f          // xxhdpi
    private val navBar = 48 * 3       // 导航栏 48dp ⇒ 144px

    @Test
    fun `普通贴底键盘不扩展`() {
        val r = ImeRectMath.resolve(ImeRectMath.Box(0, 1364, 1080, 2400), navBar, density)
        assertEquals(ImeRectMath.Box(0, 1364, 1080, 2400), r)
        assertFalse(ImeRectMath.isFloating(ImeRectMath.Box(0, 1364, 1080, 2400), navBar))
    }

    @Test
    fun `浮窗键盘按 ROM 扩 80dp`() {
        val floating = ImeRectMath.Box(400, 1600, 1000, 1700)   // 高 100px ≤ navBar(144) ⇒ 浮窗
        assertTrue(ImeRectMath.isFloating(floating, navBar))
        val r = ImeRectMath.resolve(floating, navBar, density)
        assertEquals(ImeRectMath.Box(400, 1600, 1000, 1700 + 80 * 3), r)
    }

    @Test
    fun `空矩形绝不扩展`() {
        val empty = ImeRectMath.Box(0, 2400, 1080, 2400)        // height==0，ROM 也算"浮窗"
        assertTrue(ImeRectMath.isFloating(empty, navBar))
        val r = ImeRectMath.resolve(empty, navBar, density)
        assertEquals(empty, r)                        // 不能凭空造出 80dp 的带
        assertTrue(r.isEmpty)
    }

    @Test
    fun `高度恰等导航栏算浮窗`() {
        val eq = ImeRectMath.Box(0, 2400 - navBar, 1080, 2400)
        assertTrue(ImeRectMath.isFloating(eq, navBar))
    }

    @Test
    fun `导航栏高度未知时退化为原始矩形`() {
        val floating = ImeRectMath.Box(400, 1600, 1000, 1700)
        val r = ImeRectMath.resolve(floating, 0, density)
        assertEquals(floating, r)
    }

    @Test
    fun `入参不被改动`() {
        val src = ImeRectMath.Box(400, 1600, 1000, 1700)
        ImeRectMath.resolve(src, navBar, density)
        assertEquals(ImeRectMath.Box(400, 1600, 1000, 1700), src)
    }
}

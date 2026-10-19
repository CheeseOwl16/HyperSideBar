package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统手势带判据（[GestureThresholds.inSystemGestureBand]）：
 * 四边 [GestureThresholds.SYSTEM_GESTURE_EDGE_DP] 带内 = 系统手势（返回/通知/桌面）的地盘，
 * 输入接管不得抢。背景（2026-10-19）：返回手势起点在边缘空白 ⇒ 被接管抢走后小窗应用
 * 收不到 BACK（用户诉求"小窗获焦时返回手势=应用内返回"的对立面）。
 */
class SystemGestureBandTest {

    private val density = 3f          // 1080x2400 @3x 的典型机
    private val w = 1080
    private val h = 2400
    private val edge = (GestureThresholds.SYSTEM_GESTURE_EDGE_DP * density).toInt()  // 72px

    @Test
    fun `左右缘带内算手势带`() {
        assertTrue(GestureThresholds.inSystemGestureBand(0f, 1200f, w, h, density))
        assertTrue(GestureThresholds.inSystemGestureBand((edge - 1).toFloat(), 1200f, w, h, density))
        assertTrue(GestureThresholds.inSystemGestureBand((w - edge + 1).toFloat(), 1200f, w, h, density))
        assertTrue(GestureThresholds.inSystemGestureBand(w.toFloat(), 1200f, w, h, density))
    }

    @Test
    fun `顶底缘带内算手势带`() {
        assertTrue(GestureThresholds.inSystemGestureBand(540f, 0f, w, h, density))
        assertTrue(GestureThresholds.inSystemGestureBand(540f, (edge - 1).toFloat(), w, h, density))
        assertTrue(GestureThresholds.inSystemGestureBand(540f, (h - edge + 1).toFloat(), w, h, density))
    }

    @Test
    fun `带外正常区域不算`() {
        assertFalse(GestureThresholds.inSystemGestureBand(edge.toFloat() + 1, 1200f, w, h, density))
        assertFalse(GestureThresholds.inSystemGestureBand(540f, (edge + 1).toFloat(), w, h, density))
        assertFalse(GestureThresholds.inSystemGestureBand(540f, (h - edge - 1).toFloat(), w, h, density))
        assertFalse(GestureThresholds.inSystemGestureBand((w - edge - 1).toFloat(), 1200f, w, h, density))
    }

    @Test
    fun `密度换算正确（低密屏带宽更窄）`() {
        val d2 = 2f
        // x=60px 在 @3x（带=72px）算带内，在 @2x（带=48px）算带外
        assertTrue(GestureThresholds.inSystemGestureBand(60f, 1200f, w, h, density))
        assertFalse(GestureThresholds.inSystemGestureBand(60f, 1200f, w, h, d2))
    }
}

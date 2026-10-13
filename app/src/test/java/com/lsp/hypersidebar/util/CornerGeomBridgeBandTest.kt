package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 侧滑触发带换算 ([CornerGeomBridge.bandPx]) 的公式守门。
 *
 * ## 为什么必须有
 * 这条带是 Java（写属性）与 native（读属性判"带内起手"）之间的**唯一契约**，而 c 侧的
 * 判定是"命中即吞掉整条手势"——公式漂移的后果不是少呼出一次，是**用户正常返回手势被吞**。
 * 契约两端都不能靠注释提醒，所以把 Java 这一端的换算钉成测试。
 *
 * native 那一端（`hsb_band_in`）无法在 JVM 里跑，靠真机的 `band geom ok …` 行验收：
 * 它打的是 native **实读**到的四个值，与这里的期望一比即可。
 */
class CornerGeomBridgeBandTest {

    @Test
    fun `marble 竖屏 1080x2400 默认档`() {
        // 真机口径：1080x2400 / density 2.75 / 条带 32dp、带高 33%、带位 50%
        val g = CornerGeomBridge.bandPx(
            screenW = 1080, screenH = 2400, density = 2.75f,
            bandDp = 32, heightPercent = 33, positionPercent = 50,
        )
        assertEquals(1080, g.screenW)
        assertEquals(2400, g.screenH)
        assertEquals(88f, g.wPx, 0.01f)      // 32dp × 2.75
        assertEquals(792f, g.hPx, 0.01f)     // 2400 × 33%
        assertEquals(804f, g.topPx, 0.01f)   // (2400 − 792) × 50%
    }

    @Test
    fun `横屏入参也归一到竖屏口径`() {
        // 模块 App 进程自己的朝向不可控 ⇒ bandPx 必须先 min/max 归一，否则横屏跑一次
        // 就会把错误的一套带写进属性（此后一直错到下次推送）。
        val portrait = CornerGeomBridge.bandPx(
            screenW = 1080, screenH = 2400, density = 2.75f,
            bandDp = 32, heightPercent = 33, positionPercent = 50,
        )
        val landscape = CornerGeomBridge.bandPx(
            screenW = 2400, screenH = 1080, density = 2.75f,
            bandDp = 32, heightPercent = 33, positionPercent = 50,
        )
        assertEquals(portrait, landscape)
    }

    @Test
    fun `带位极端值不跑出屏幕`() {
        val top = CornerGeomBridge.bandPx(
            screenW = 1080, screenH = 2400, density = 2.75f,
            bandDp = 32, heightPercent = 100, positionPercent = 50,
        )
        assertEquals(0f, top.topPx, 0.01f)   // 带高铺满 ⇒ top 必为 0
        assertEquals(2400f, top.hPx, 0.01f)
    }
}

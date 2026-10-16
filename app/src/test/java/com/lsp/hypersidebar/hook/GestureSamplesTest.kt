package com.lsp.hypersidebar.hook

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批处理展开器的单测。守两条不变量 + 一条真正的回归：
 *  ① 采样按时间递增、当前点永远在最后；
 *  ② historySize=0 时只有一个点（不改变无批处理时的行为）；
 *  ③ **回归**：一个"整段被并进一个事件"的快滑（只给 history、当前点也在终点），
 *     展开后必须能让 [CornerTrigger] 判出 SHOW —— 改动前只读当前点，
 *     这类手势在真机上是 `moves=0 ⇒ NO_MOVE`（2026-10-14 实测 20/41 次）。
 */
class GestureSamplesTest {

    /** 把一串"手指路径点"变成一个批处理事件：前 n-1 个是 history，最后一个是当前点。 */
    private fun hist(points: List<Pair<Float, Float>>, t0: Long, step: Long) =
        expandGestureSamples(
            historySize = points.size - 1,
            historicalX = { points[it].first },
            historicalY = { points[it].second },
            historicalEventTime = { t0 + it * step },
            currentX = points.last().first,
            currentY = points.last().second,
            currentEventTime = t0 + (points.size - 1) * step,
        )

    @Test
    fun `expands history in order with current point last`() {
        val pts = listOf(100f to 2300f, 140f to 2260f, 190f to 2210f, 260f to 2140f)
        val s = hist(pts, t0 = 1000, step = 8)
        assertEquals(4, s.size)
        assertEquals(pts, s.map { it.x to it.y })
        assertEquals(listOf(1000L, 1008L, 1016L, 1024L), s.map { it.eventTime })
    }

    @Test
    fun `empty history yields exactly the current point`() {
        val s = hist(listOf(500f to 2000f), t0 = 42, step = 0)
        assertEquals(1, s.size)
        assertEquals(500f, s[0].x); assertEquals(2000f, s[0].y); assertEquals(42L, s[0].eventTime)
    }

    @Test
    fun `monotonic guard repairs unordered or zero history timestamps`() {
        // 上游给了乱序/零值时间戳：展开结果仍必须递增，否则速度窗/锥形判定全歪
        val pts = listOf(10f to 10f, 20f to 20f)
        val s = expandGestureSamples(
            historySize = 2,
            historicalX = { pts[it].first }, historicalY = { pts[it].second },
            historicalEventTime = { 0L },                 // 全零
            currentX = 30f, currentY = 30f, currentEventTime = 0L,
        )
        val times = s.map { it.eventTime }
        assertEquals(3, times.size)
        assertTrue("时间戳必须递增: $times", times.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `batched flick that only exists as history now triggers the corner state machine`() {
        // 复现真机形态：一次 MOVE 批处理带 12 个历史采样点（≈100ms 的斜滑），
        // 只读当前点时 onMove 一次都不会被调用 ⇒ moves=0 ⇒ NO_MOVE。
        val w = 1080f; val h = 2400f
        val downX = 60f; val downY = 2380f
        val hotPx = h * 0.10f           // 设备现值：角区热区=屏高 10%
        val cornerPx = w * 0.18f        // 设备现值：角窗宽=18%（=194px）
        val ct = CornerTrigger()
        assertTrue(
            ct.onDown(
                downX, downY, w, h,
                hotSpacePx = hotPx, cornerWidth = cornerPx,
                confirmPx = 12f * 2.75f, inwardMinPx = 12f * 2.75f,
                minAngleDeg = 25f, maxAngleDeg = 65f,
                abandonExtraDeg = 3f, confirmFrames = 1,   // OS4 = FAST
            ),
        )
        // 左下角 → 右上方斜滑：24 个采样点，全部在批处理 history 里
        val pts = (0..23).map { i -> downX + i * 6f to downY - i * 5.5f }
        val samples = hist(pts, t0 = 500, step = 4)
        val shown = samples.any { ct.onMove(it.x, it.y) == CornerTrigger.Action.SHOW }
        assertTrue("批处理快滑必须能判出 SHOW（改动前是 NO_MOVE）", shown)
        assertTrue("内滑幅度应被记录", ct.diag().contains("maxIn="))
    }

    @Test
    fun `vertical tail still abandons even when fed as history`() {
        // 防误触护栏不能因为喂了 history 而失效：明显竖直（>68°）仍然要让路给系统
        val w = 1080f; val h = 2400f
        val downX = 60f; val downY = 2380f
        val ct = CornerTrigger()
        assertTrue(
            ct.onDown(
                downX, downY, w, h,
                hotSpacePx = h * 0.10f, cornerWidth = w * 0.18f,
                confirmPx = 12f * 2.75f, inwardMinPx = 12f * 2.75f,
                minAngleDeg = 25f, maxAngleDeg = 65f,
                abandonExtraDeg = 3f, confirmFrames = 1,
            ),
        )
        val pts = (0..23).map { i -> downX + i * 1.2f to downY - i * 9f }   // ≈82° 竖直
        val samples = hist(pts, t0 = 500, step = 4)
        val actions = samples.map { ct.onMove(it.x, it.y) }
        assertTrue("竖直尾巴必须 ABANDON_REPLAY", actions.contains(CornerTrigger.Action.ABANDON_REPLAY))
        assertTrue("且不能同时 SHOW", !actions.contains(CornerTrigger.Action.SHOW))
    }

    @Test
    fun `corner window gate unchanged - outside corner is not claimed`() {
        // 触发区域判据不受本次改动影响：角窗外起手仍然不接管
        val w = 1080f; val h = 2400f
        val ct = CornerTrigger()
        val claimed = ct.onDown(
            540f, 2380f, w, h,                       // 底部中间：非角窗
            hotSpacePx = h * 0.10f, cornerWidth = w * 0.18f,
            confirmPx = 12f * 2.75f, inwardMinPx = 12f * 2.75f,
            minAngleDeg = 25f, maxAngleDeg = 65f,
            abandonExtraDeg = 3f, confirmFrames = 1,
        )
        org.junit.Assert.assertFalse(claimed)
    }
}

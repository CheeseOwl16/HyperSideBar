package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BlankTapDetector` 判定核单测。
 *
 * 覆盖的是**会真出现在用户手上的两种误判方向**：
 * - 拖拽/长按被当成点（会在用户做别的事时把小窗收掉）；
 * - 从小窗内往外拖的尾巴被当成点（DOWN 在小窗内、UP 在空白处）。
 * 另外覆盖多指、无配对 DOWN、以及"一次 UP 之后状态必须复位"。
 */
class BlankTapDetectorTest {

    private fun detector(): BlankTapDetector = BlankTapDetector.also {
        BlankTapDetector.configure(12f * 3f, 300L)   // 与 1080×2340@3x 的真实调用一致
        BlankTapDetector.reset()
    }

    @Test
    fun adjacentDownUpIsTap() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertTrue(BlankTapDetector.onUp(104f, 203f, 1_120L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun dragIsNotTap() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertFalse(BlankTapDetector.onUp(160f, 200f, 1_120L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun exactlyAtSlopIsStillTap() {
        detector()
        val slop = 12f * 3f
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertTrue(BlankTapDetector.onUp(100f + slop, 200f, 1_100L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun tooLongIsNotTap() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertFalse(BlankTapDetector.onUp(100f, 200f, 1_301L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun multiPointerIsNotTap() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 2)
        assertFalse(BlankTapDetector.onUp(100f, 200f, 1_050L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun touchingInsideFreeformAtDownIsNotBlank() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertFalse(BlankTapDetector.onUp(100f, 200f, 1_050L, blankAtDown = false, blankAtUp = true))
    }

    @Test
    fun draggingOutOfFreeformIsNotBlank() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertFalse(BlankTapDetector.onUp(101f, 201f, 1_050L, blankAtDown = true, blankAtUp = false))
    }

    @Test
    fun upWithoutDownIsNotTap() {
        detector()
        assertFalse(BlankTapDetector.onUp(100f, 200f, 1_050L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun stateResetsAfterUp() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        assertTrue(BlankTapDetector.onUp(100f, 200f, 1_050L, blankAtDown = true, blankAtUp = true))
        // 第二次没有 DOWN：不能把上一条手势的状态算进来
        assertFalse(BlankTapDetector.onUp(100f, 200f, 1_100L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun resetDropsTracking() {
        detector()
        BlankTapDetector.onDown(100f, 200f, 1_000L, 1)
        BlankTapDetector.reset()
        assertFalse(BlankTapDetector.onUp(100f, 200f, 1_050L, blankAtDown = true, blankAtUp = true))
    }

    @Test
    fun countersAccumulate() {
        detector()
        val before = BlankTapDetector.hits
        BlankTapDetector.onDown(10f, 10f, 100L, 1)
        BlankTapDetector.onUp(10f, 10f, 150L, blankAtDown = true, blankAtUp = true)
        assertTrue(BlankTapDetector.hits >= before + 1)
    }
}

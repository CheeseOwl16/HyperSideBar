package com.lsp.hypersidebar.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GestureTakeover] 的纯核测试：记录保真、上限溢出、结束语义。 */
class GestureTakeoverTest {

    private fun take(): GestureTakeover = GestureTakeover()

    @Test
    fun `samples keep order and timestamps`() {
        val t = take()
        t.begin(downTime = 1_000L, deviceId = 3, source = 4098)
        t.append(0, 1_000L, 10f, 20f, 0, 4098)   // DOWN
        t.append(2, 1_010L, 11f, 21f, 0, 4098)   // MOVE
        t.append(1, 1_030L, 12f, 22f, 0, 4098)   // UP
        val snap = t.end()!!
        assertEquals(1_000L, snap.downTime)
        assertEquals(3, snap.deviceId)
        assertEquals(4098, snap.source)
        assertFalse(snap.overflow)
        assertEquals(listOf(0, 2, 1), snap.samples.map { it.action })
        assertEquals(listOf(1_000L, 1_010L, 1_030L), snap.samples.map { it.eventTime })
        assertEquals(12f, snap.samples[2].x, 0.001f)
    }

    @Test
    fun `end without samples returns null and clears active`() {
        val t = take()
        t.begin(1L, 0, 0)
        assertTrue(t.active)
        assertNull(t.end())
        assertFalse(t.active)
        assertNull(t.end())
    }

    @Test
    fun `count overflow marks snapshot and yields empty plan`() {
        val t = take()
        t.begin(0L, 0, 0)
        repeat(GestureTakeover.MAX_SAMPLES + 5) { i ->
            t.append(2, i.toLong(), i.toFloat(), 0f, 0, 0)
        }
        val snap = t.end()!!
        assertTrue(snap.overflow)
        assertEquals(GestureTakeover.MAX_SAMPLES, snap.samples.size)
        assertTrue(t.plan(snap).isEmpty())
    }

    @Test
    fun `time overflow marks snapshot`() {
        val t = take()
        t.begin(0L, 0, 0)
        t.append(0, 0L, 0f, 0f, 0, 0)
        t.append(2, GestureTakeover.MAX_MS + 1, 5f, 5f, 0, 0)
        val snap = t.end()!!
        assertTrue(snap.overflow)
        assertEquals(1, snap.samples.size)
        assertTrue(t.plan(snap).isEmpty())
    }

    @Test
    fun `normal plan keeps all samples in order`() {
        val t = take()
        t.begin(0L, 0, 4098)
        t.append(0, 0L, 1f, 1f, 0, 4098)
        t.append(2, 8L, 1f, 2f, 0, 4098)
        t.append(1, 16L, 1f, 3f, 0, 4098)
        val snap = t.end()!!
        assertEquals(listOf(0, 2, 1), t.plan(snap).map { it.action })
    }

    @Test
    fun `begin resets previous recording`() {
        val t = take()
        t.begin(0L, 0, 0)
        t.append(0, 0L, 1f, 1f, 0, 0)
        t.begin(500L, 7, 4098)
        assertTrue(t.active)
        t.append(0, 500L, 9f, 9f, 0, 4098)
        val snap = t.end()!!
        assertEquals(500L, snap.downTime)
        assertEquals(7, snap.deviceId)
        assertEquals(1, snap.samples.size)
        assertEquals(9f, snap.samples[0].x, 0.001f)
    }

    @Test
    fun `clear drops everything`() {
        val t = take()
        t.begin(0L, 0, 0)
        t.append(0, 0L, 1f, 1f, 0, 0)
        t.clear()
        assertFalse(t.active)
        assertNull(t.end())
    }

    @Test
    fun `append after end is ignored`() {
        val t = take()
        t.begin(0L, 0, 0)
        t.append(0, 0L, 1f, 1f, 0, 0)
        t.end()
        t.append(2, 5L, 2f, 2f, 0, 0)
        assertNull(t.end())
    }

    // ===== stillTapCandidate（"拖动被吞"修法的纯规则）=====

    @Test
    fun `tap candidate within slop and time`() {
        val t = take()
        assertTrue(t.stillTapCandidate(1, 5f, 5f, 100L, slopPx = 33f, maxMs = 300L))
        // 边界：正好 slop / 正好 maxMs 都算候选
        assertTrue(t.stillTapCandidate(1, 33f, 0f, 300L, slopPx = 33f, maxMs = 300L))
        assertTrue(t.stillTapCandidate(1, 0f, -33f, 300L, slopPx = 33f, maxMs = 300L))
    }

    @Test
    fun `tap candidate rejects over slop multi touch and long press`() {
        val t = take()
        assertFalse(t.stillTapCandidate(1, 34f, 0f, 100L, slopPx = 33f, maxMs = 300L))
        assertFalse(t.stillTapCandidate(1, 0f, 34f, 100L, slopPx = 33f, maxMs = 300L))
        // 斜向合成位移超限（不是逐轴判）
        assertFalse(t.stillTapCandidate(1, 30f, 30f, 100L, slopPx = 33f, maxMs = 300L))
        assertFalse(t.stillTapCandidate(2, 0f, 0f, 100L, slopPx = 33f, maxMs = 300L))
        assertFalse(t.stillTapCandidate(1, 0f, 0f, 301L, slopPx = 33f, maxMs = 300L))
    }
}

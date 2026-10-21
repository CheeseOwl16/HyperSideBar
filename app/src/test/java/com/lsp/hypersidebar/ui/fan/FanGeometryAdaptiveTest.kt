package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanGeometryAdaptiveTest {
    private val config = FanConfig()
    private val apps = List(7) { FanAppInfo(packageName = "pkg$it") } +
        FanAppInfo(ALL_APPS_PKG, "全部应用")

    private fun compute(
        x: Float,
        y: Float,
        landscape: Boolean = false,
        quickApps: List<FanAppInfo> = listOf(FanAppInfo("quick")),
        corner: Boolean = false
    ) = computeFanGeometry(
        anchor = Offset(x, y),
        screenSize = if (landscape) IntSize(800, 400) else IntSize(400, 800),
        apps = apps,
        quickApps = quickApps,
        config = config,
        density = 1f,
        isLandscape = landscape,
        cornerAnchor = corner
    )

    @Test
    fun `empty quick bar keeps a fitting portrait fan symmetric on both edges`() {
        for (x in listOf(0f, 400f)) {
            val g = compute(x, 550f, quickApps = emptyList())
            assertEquals(150f, g.spanAngle, 0.001f)
            assertEquals(if (x == 0f) -75f else 105f, g.startAngle, 0.001f)
            assertEquals(2f * g.anchor.y, g.items[0].centerY + g.items[6].centerY, 0.001f)
        }
    }

    @Test
    fun `empty quick bar keeps a fitting landscape fan symmetric on both edges`() {
        for (x in listOf(0f, 800f)) {
            val g = compute(x, 220f, landscape = true, quickApps = emptyList())
            assertEquals(75f, g.spanAngle, 0.001f)
            assertEquals(if (x == 0f) -37.5f else 142.5f, g.startAngle, 0.001f)
        }
    }

    @Test
    fun `all apps entry remains in the inner ring when quick bar is empty`() {
        val g = compute(400f, 550f, quickApps = emptyList())
        val allApps = g.items.last()
        assertEquals(ALL_APPS_PKG, allApps.app.packageName)
        assertTrue(!allApps.isOuter)
        assertTrue(g.quickApps.isEmpty())
        assertEquals(apps.size, g.items.size)
    }

    @Test
    fun `visible quick bar keeps a fitting portrait fan symmetric on both edges`() {
        for (x in listOf(0f, 400f)) {
            val g = compute(x, 480f)
            assertEquals(150f, g.spanAngle, 0.001f)
            assertEquals(if (x == 0f) -75f else 105f, g.startAngle, 0.001f)
            // Same ring endpoints must straddle the horizontal axis symmetrically.
            assertEquals(2f * g.anchor.y, g.items[0].centerY + g.items[6].centerY, 0.001f)
            assertEquals(config.iconSizeDp, g.quickIconSize, 0.001f)
            assertTrue(g.quickBarY + g.quickIconSize * 1.5f <= 800f - 72f)
        }
    }

    @Test
    fun `visible quick bar keeps a fitting landscape fan symmetric on both edges`() {
        for (x in listOf(0f, 800f)) {
            val g = compute(x, 180f, landscape = true)
            assertEquals(75f, g.spanAngle, 0.001f)
            assertEquals(if (x == 0f) -37.5f else 142.5f, g.startAngle, 0.001f)
            assertEquals(2f * g.anchor.y, g.items[0].centerY + g.items[4].centerY, 0.001f)
        }
    }

    @Test
    fun `visible quick bar still reserves space and adapts a lower fan`() {
        val quickApps = listOf(FanAppInfo(packageName = "quick"))
        for (x in listOf(0f, 400f)) {
            val g = compute(x, 550f, quickApps = quickApps)
            assertTrue(g.items[0].centerY + g.items[6].centerY < 2f * g.anchor.y)
            assertEquals(config.outerRadiusDp, g.outerRadius, 0.001f)
        }
    }

    @Test
    fun `visible quick bar still adapts near either vertical boundary and mirrors left to right`() {
        for (y in listOf(120f, 550f)) {
            val right = compute(0f, y)
            val left = compute(400f, y)
            right.items.zip(left.items).forEach { (r, l) ->
                assertEquals(400f, r.centerX + l.centerX, 0.001f)
                assertEquals(r.centerY, l.centerY, 0.001f)
                assertTrue(r.centerY in 72f..728f)
            }
            val endpointSum = right.items[0].centerY + right.items[6].centerY
            assertTrue(if (y < 400f) endpointSum > 2f * y else endpointSum < 2f * y)
        }
    }

    @Test
    fun `one or six quick items use the same vertical reservation`() {
        val one = compute(0f, 480f)
        val six = compute(0f, 480f, quickApps = List(6) { FanAppInfo("quick$it") })
        assertEquals(one.items, six.items)
        assertEquals(one.startAngle, six.startAngle, 0f)
        assertEquals(one.quickBarY, six.quickBarY, 0f)
    }

    @Test
    fun `corner fan item layout does not depend on quick bar presence`() {
        for (x in listOf(0f, 400f)) {
            val empty = compute(x, 790f, corner = true, quickApps = emptyList())
            val visible = compute(x, 790f, corner = true, quickApps = listOf(FanAppInfo("quick")))
            assertEquals(visible.items, empty.items)
            assertEquals(visible.startAngle, empty.startAngle, 0f)
            assertEquals(visible.spanAngle, empty.spanAngle, 0f)
        }
    }
}

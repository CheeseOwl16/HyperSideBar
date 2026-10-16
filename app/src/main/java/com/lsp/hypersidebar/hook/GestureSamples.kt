package com.lsp.hypersidebar.hook

import android.view.MotionEvent

/** 一个输入采样点：可能来自 MotionEvent 的历史采样，也可能是它的当前点。 */
data class GestureSample(val x: Float, val y: Float, val eventTime: Long)

/**
 * 把"一个可能带历史采样点的 MotionEvent"展开成**按时间递增**的采样序列：
 * `history[0..historySize)` 之后跟当前点。
 *
 * 为什么需要：Android 的输入是**批处理**的。接收端跟不上时，InputDispatcher 不丢事件，
 * 而是把一段时间的 MOVE 合并成一个 `MotionEvent`——`getX()/getY()` 只是最后一个点，
 * 中间的点全部在 `getHistoricalX/getHistoricalY(i)` 里。只读当前点，等于把中间的方向
 * 与幅度信息整段丢掉。
 *
 * 真机实测（2026-10-14 21:35，作者本人手势，41 次底角起手）：自然速度的底角斜滑
 * **DOWN→UP 只有 0–23ms、moves=0**，41 次里 20 次 `NO_MOVE`、10 次 `SHORT`，触发率
 * 仅 19.5%；而少数成功那几次的位移差达 55px ⇒ 位移数据确实存在，只是被并进了 history。
 * `adb input swipe`（800ms）同样拿到 moves=0，进一步佐证。
 *
 * 纯逻辑、无 Android 依赖 ⇒ 可单测（喂合成序列，锁死"顺序 + 当前点在最后 + 空 history"）。
 */
fun expandGestureSamples(
    historySize: Int,
    historicalX: (Int) -> Float,
    historicalY: (Int) -> Float,
    historicalEventTime: (Int) -> Long,
    currentX: Float,
    currentY: Float,
    currentEventTime: Long,
): List<GestureSample> {
    val n = if (historySize > 0) historySize else 0
    if (n == 0) return listOf(GestureSample(currentX, currentY, currentEventTime))
    val out = ArrayList<GestureSample>(n + 1)
    var t = Long.MIN_VALUE
    for (i in 0 until n) {
        val x = historicalX(i)
        val y = historicalY(i)
        // 单调保护：理论 eventTime 递增；万一上游给了乱序/零值，用索引兜一个递增值，
        // 保证"顺序"这个不变量不被破坏（顺序错了速度窗/锥形判定全歪）。
        val raw = historicalEventTime(i)
        val et = if (raw > t) raw else t + 1
        t = et
        out.add(GestureSample(x, y, et))
    }
    val curEt = if (currentEventTime > t) currentEventTime else t + 1
    out.add(GestureSample(currentX, currentY, curEt))
    return out
}

/**
 * MotionEvent 适配：把事件展开成采样序列（历史点 → 当前点）。
 *
 * 注意：调用方拿到的 `ev` 必须**没有**被 `obtainNoHistory` 之类剥过 history
 * （本仓 spy 的合流复制用的是 `MotionEvent.obtain(ev)`，广播走 Parcelable，
 * 两条路径都完整保留 history）。
 */
fun MotionEvent.toGestureSamples(): List<GestureSample> = expandGestureSamples(
    historySize = historySize,
    historicalX = { getHistoricalX(it) },
    historicalY = { getHistoricalY(it) },
    historicalEventTime = { getHistoricalEventTime(it) },
    currentX = x,
    currentY = y,
    currentEventTime = eventTime,
)

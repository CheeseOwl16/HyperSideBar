package com.lsp.hypersidebar.hook

/**
 * 小窗「点击空白处」的判定核（纯 JVM，无 Android 依赖）。
 *
 * ## 为什么是纯核
 * 仓库既有分工：状态机/几何/契约这类逻辑放纯 `object` + 单测（`CornerTrigger`、`FanGeometry`、
 * `MisclickCaliber`、`AnchorSpec` 都是），Android/Xposed 那层只做接线。本判定核同理——
 * 它只接收坐标与时刻，**不认识 MotionEvent，也不认识小窗**。两个外部事实由调用方注入：
 * - `pointerCount`：多指 ⇒ 不是"点"（双指缩放/多指操作绝不能收小窗）；
 * - `blankAtDown` / `blankAtUp`：该点是否落在**所有在册小窗之外**（判定实现在
 *   `FreeformBlankTest`，或反射 ROM 的 `MulWinSwitchInteractUtil.isTouchedInFreeformWindow`）。
 *
 * ## 判据（全部满足才算一次"点空白"）
 * 1. 有配对的 DOWN（`onDown` 过一次且没被 `reset`），且 DOWN 时是单指；
 * 2. UP 与 DOWN 的位移 ≤ `slopPx`（拖拽不算——用户在小窗外拖动是在做别的事）；
 * 3. 时长在 `0..maxDurationMs`（长按不算）；
 * 4. DOWN 与 UP **两处都是空白**：只判一处会漏掉"从小窗内往外拖出去"这种（那种也不该收）。
 *
 * 任一不满足返回 false；`onUp` 后无论结果都复位，下一次必须重新 `onDown`。
 *
 * ## 阈值从哪来
 * `GestureThresholds.BLANK_TAP_SLOP_DP` / `BLANK_TAP_MAX_MS`；`slopPx` 由调用方按
 * `dp × density` 折算后用 [configure] 注入（本核不认识 density，保持纯）。
 *
 * ## 线程
 * 只在 SystemUI 主线程（spy 的 receiver Looper）被调用，故不加同步；`@Volatile` 是给
 * 诊断读准备的（`armed` 等状态的读取可能来自别的线程）。
 */
object BlankTapDetector {

    private var slopPx = GestureThresholds.BLANK_TAP_SLOP_DP * 3f   // 未 configure 时的保守兜底
    private var maxDurationMs = GestureThresholds.BLANK_TAP_MAX_MS

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var tracking = false
    private var multiPointer = false

    /** 判定核被调用的次数（DOWN 计一次）与命中次数——诊断用，恒开累计。 */
    @Volatile var taps = 0L; private set
    @Volatile var hits = 0L; private set

    /** 由接线层按本机 density 注入阈值（幂等，可重复调用）。 */
    fun configure(slopPxIn: Float, maxDurationMsIn: Long) {
        if (slopPxIn > 0f) slopPx = slopPxIn
        if (maxDurationMsIn > 0L) maxDurationMs = maxDurationMsIn
    }

    /** DOWN：开始跟踪。`pointerCount > 1` 直接标记本手势不可为 tap。 */
    fun onDown(x: Float, y: Float, t: Long, pointerCount: Int) {
        taps++
        downX = x
        downY = y
        downT = t
        tracking = true
        multiPointer = pointerCount > 1
    }

    /**
     * UP：给出结论。返回 true = 这是一次"点空白"。
     * 无论结果如何都会复位（避免下一次 UP 误用上一条手势的 DOWN）。
     */
    fun onUp(
        x: Float,
        y: Float,
        t: Long,
        blankAtDown: Boolean,
        blankAtUp: Boolean,
    ): Boolean {
        if (!tracking) return false
        val d = kotlin.math.hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
        val dt = t - downT
        val ok = !multiPointer &&
            d <= slopPx &&
            dt in 0..maxDurationMs &&
            blankAtDown &&
            blankAtUp
        if (ok) hits++
        reset()
        return ok
    }

    /** 手势被打断（CANCEL / 流被吞 / 功能关闭）：丢弃跟踪状态，不产生结论。 */
    fun reset() {
        tracking = false
        multiPointer = false
        downT = 0L
    }
}

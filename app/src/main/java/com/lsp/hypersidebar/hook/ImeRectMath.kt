package com.lsp.hypersidebar.hook

/**
 * IME 矩形的纯核（横屏浮窗键盘的正确处理；纯 JVM，无 Android 依赖，可单测）。
 *
 * 背景（jadx `.ota-extract/jadx/wmshell-os4-full/.../DisplayImeController.java`）：
 * - ROM 自己的浮窗判据 `calcIsFloating`（`:367`）= `frame.height() == 0 || frame.height() <= navBarFrameHeight`；
 * - 浮窗时 ROM 把 `mImeFrame.bottom` 往下扩 `80dp`（`:435`，常量 `FLOATING_IME_BOTTOM_INSET = -80`，
 *   代码是 `bottom -= density * (-80f)` ⇒ 等价 `bottom += 80dp`）——那是浮窗键盘下方的抓手/阴影区。
 *
 * 我们要的只是"点在这里不算空白"，所以：
 * - 普通（贴底满宽）模式：用原始矩形即可；
 * - 浮窗模式：照抄 ROM 扩 80dp，免得点抓手区误触发关闭；
 * - 空矩形（height==0，ROM 也算浮窗）：**不扩展**（扩了会凭空造出一条带），直接当"无可排除区"。
 */
object ImeRectMath {

    /** ROM 同款抓手区高度（dp）。 */
    const val GRABBER_DP = 80f

    /** 矩形值类型（纯 JVM；与 `android.graphics.Rect` 的转换在调用方胶水层做）。 */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = height <= 0 || right <= left
    }

    /** ROM 同款浮窗判据（含 height==0）。 */
    fun isFloating(frame: Box, navBarHeightPx: Int): Boolean =
        frame.height == 0 || frame.height <= navBarHeightPx

    /**
     * 把 ROM 的原始 IME 矩形换算成"该排除的触摸区"。
     *
     * @param navBarHeightPx 导航栏高度（px）；0 = 不判定浮窗（只用原始矩形）
     */
    fun resolve(frame: Box, navBarHeightPx: Int, density: Float): Box {
        if (frame.height > 0 && navBarHeightPx > 0 && isFloating(frame, navBarHeightPx)) {
            return frame.copy(bottom = frame.bottom + (GRABBER_DP * density).toInt())
        }
        return frame
    }
}

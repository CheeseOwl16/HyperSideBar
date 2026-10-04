package com.lsp.hypersidebar.hook

import android.graphics.Rect
import android.os.Looper
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import com.lsp.hypersidebar.util.HLog

/**
 * B1 spy 骨架（os4-trigger-final-plan §6，log-only 零抢占）：
 *
 * B 路线的输入侧地基。OS4 桌面 Rust 手势区持有边缘输入所有权，:ui 把手窗口被饿死
 * （os4-trigger-input-ownership-report §2.3：4 份导出零 `touch:`）——恢复 OS3 触发
 * 必须在能拿到全屏输入的进程里自建观察点。SystemUI uid=1000 且自家
 * EdgeBackGestureHandler 就用同族 API（monitorInput spy + 命中后 pilfer），机制有
 * 平台级先例。本阶段**只观测**：
 *
 *  - monitorInput spy = 观察副本，原始目标照常收事件，系统行为零变化（机制保证）；
 *  - 每事件打点走 [HLog.verboseEnabled] 门控（60~120Hz MOVE 会刷爆环形缓冲）；
 *  - 计数器恒开：带内 DOWN / 底部手势带 DOWN（B2 排除清单的前置证据）。
 *
 * 全反射实现（monitorInput/getInputChannel/setTouchableRegion 均 @hide），
 * 任一步失败=惰性退出并 W（romer 可能裁剪；fail-closed 不比现状差）。
 * dispose 必须在 owner Looper 执行（Android 17 强制）——本 receiver 挂主 Looper，
 * 当前无热重载路径，生命周期=进程生死，无需 dispose。
 */
object SystemUiInputSpy {

    private const val TAG = "SysInputSpy"

    @Volatile private var started = false

    /** 恒开计数（B2 前置证据）：累计 DOWN / 底部 1/4 带 DOWN / 屏缘 40px 带 DOWN */
    @Volatile var totalDown = 0L; private set
    @Volatile var bottomBandDown = 0L; private set
    @Volatile var edgeBandDown = 0L; private set

    fun start() {
        if (started) return
        started = true
        runCatching {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
            val monitor = im.javaClass.getMethod("monitorInput", String::class.java, Int::class.java)
                .invoke(im, "hypersidebar-spy", 0)
                ?: error("monitorInput returned null")
            // B1 观测面=全屏（B0 结论决定 B2 触发带；spy 只读不抢，全屏面无行为影响）
            monitor.javaClass.getMethod("setTouchableRegion", Rect::class.java)
                .invoke(monitor, Rect(0, 0, w, h))
            val channel = monitor.javaClass.getMethod("getInputChannel").invoke(monitor)
                as android.view.InputChannel
            object : InputEventReceiver(channel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        if (event is MotionEvent) onMotion(event, w, h)
                    } finally {
                        finishInputEvent(event, false)
                    }
                }
            }
            HLog.i(TAG, "spy started: display ${w}x$h (observe-only, full-screen region)")
        }.onFailure {
            started = false
            HLog.w(TAG, "spy start failed (inert): ${it.message}")
        }
    }

    private fun onMotion(ev: MotionEvent, w: Int, h: Int) {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            totalDown++
            if (ev.y > h * 3f / 4f) bottomBandDown++
            if (ev.x < 40 || ev.x > w - 40 || ev.y < 40) edgeBandDown++
            // 每 DOWN 恒打一行（低频）：判读"流到达 spy"的最小证据
            HLog.i(TAG, "down (${"%.0f".format(ev.x)},${"%.0f".format(ev.y)}) " +
                "src=${ev.source} dev=${ev.deviceId} edge=${ev.edgeFlags} " +
                "t=${ev.downTime}/${ev.eventTime} | totals d=$totalDown bottom=$bottomBandDown edge=$edgeBandDown")
        } else if (HLog.verboseEnabled && (a == MotionEvent.ACTION_MOVE ||
                a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
        ) {
            // 高频明细走 vlog 模式（门控在调用侧，关时零字符串构造）
            if (a != MotionEvent.ACTION_MOVE) {
                HLog.i(TAG, "a=$a x=${ev.x.toInt()} y=${ev.y.toInt()} dt=${ev.downTime}")
            } else {
                HLog.i(TAG, "move x=${ev.x.toInt()} y=${ev.y.toInt()} dt=${ev.downTime}")
            }
        }
    }
}

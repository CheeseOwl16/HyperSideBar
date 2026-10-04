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

    /** 取证类清单（api dump 用） */
    private val CANDIDATE_CLASSES = arrayOf(
        "android.hardware.input.InputManagerGlobal",
        "android.hardware.input.InputManager",
        "miui.hardware.input.MiuiInputManager",
    )

    /** 连接候选（类名, 方法名）：marble framework dex 反汇编实锤（framework-marble-os4.jar
     *  classes2.dex 方法表）=InputManagerGlobal.getInstance().monitorGestureInput(String,int):
     *  InputMonitor；InputManager.getInstance 已不存在。其余候选=旧 ROM 兜底。 */
    private val CANDIDATES = arrayOf(
        "android.hardware.input.InputManagerGlobal" to "monitorGestureInput",
        "android.hardware.input.InputManagerGlobal" to "monitorInput",
        "android.hardware.input.InputManager" to "monitorInput",
    )

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
            val channel = openSpyChannel() ?: error("no usable monitorInput candidate")
            object : InputEventReceiver(channel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        if (event is MotionEvent) onMotion(event, w, h)
                    } finally {
                        finishInputEvent(event, false)
                    }
                }
            }
            HLog.i(TAG, "spy started: display ${w}x$h (observe-only)")
        }.onFailure {
            started = false
            HLog.w(TAG, "spy start failed (inert): ${it.message}")
        }
    }

    /**
     * 多候选解析（2026-10-04 marble 实锤：`InputManager.getInstance` 不存在——Android 15+
     * 该 API 已迁 InputManagerGlobal，具体方法名 ROM 各异）。每次启动先把候选类的
     * monitor 系与 getInstance 方法清单 dump 进日志（下一份导出=ROM API 形状铁证），
     * 再按序尝试。返回 InputChannel（InputMonitor 路径先 setTouchableRegion(全屏)
     * 再 getInputChannel；直返 InputChannel 的路径无 region=观测全屏）。
     */
    private fun openSpyChannel(): android.view.InputChannel? {
        // 取证：候选类方法清单（只打名字+参数形状，一行一个，恒打——低频一次性）
        for (cn in CANDIDATE_CLASSES) {
            runCatching {
                val c = Class.forName(cn, false, SystemUiInputSpy::class.java.classLoader)
                val lines = c.declaredMethods
                    .filter { it.name.contains("monitor") || it.name == "getInstance" }
                    .joinToString { m ->
                        m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" + m.returnType.simpleName
                    }
                HLog.i(TAG, "api dump $cn: ${lines.ifEmpty { "(no monitor/getInstance methods)" }}")
            }.onFailure { HLog.i(TAG, "api dump $cn: class not found") }
        }
        for ((cn, mn) in CANDIDATES) {
            val r = runCatching {
                val c = Class.forName(cn, false, SystemUiInputSpy::class.java.classLoader)
                val inst = c.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }
                    ?.invoke(null) ?: error("getInstance() missing")
                val m = c.methods.firstOrNull {
                    it.name == mn && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: error("$mn(String,int) missing")
                val arg0 = "hypersidebar-spy"
                val arg1 = 0
                m.invoke(inst, arg0, arg1)
            }.getOrElse { failure ->
                HLog.w(TAG, "candidate $cn.$mn failed: ${failure.message}")
                continue
            }
            when (r) {
                is android.view.InputChannel -> return r
                else -> {
                    // InputMonitor 路径：marble 的 InputMonitor 无 setTouchableRegion
                    // （dex 方法表实锤）——gesture monitor 无 region=全屏观察副本，正合 B1 用途
                    val mon = r ?: continue
                    val ch = mon.javaClass.getMethod("getInputChannel").invoke(mon)
                        as? android.view.InputChannel ?: continue
                    return ch
                }
            }
        }
        return null
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

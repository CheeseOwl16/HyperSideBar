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
    @Volatile private var gaveUp = false
    private var attempts = 0
    private var appContext: android.content.Context? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /** 重试上限与间隔（1004 实锤：attach 极早期 display/surface 未就绪，服务侧静默返回
     *  空 channel 的 monitor——SystemUI 自己的 MultiTaskSwitch monitor 创建于就绪后） */
    private const val MAX_ATTEMPTS = 12
    private const val RETRY_MS = 5_000L

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

    /**
     * InputMonitor 强引用保活（1005 实锤：register 成功、channel 有效、receiver 就位，
     * 却零事件到达——monitor 对象在 openSpyChannel 返回后即不可达，GC 回收触发 native
     * dispose ⇒ dispatcher 摘除 spy 窗口。两个参考实现（MiuiBackGestureHook
     * NativeBackInputMonitor / AOSP ClipboardOverlayController）都把 monitor 存字段保活）。
     * 同时保 channel 引用（receiver 原生侧持有，双保险）。
     */
    @Volatile private var monitorRef: Any? = null
    @Volatile private var channelRef: android.view.InputChannel? = null

    fun start(context: android.content.Context? = null) {
        if (started || gaveUp) return
        // 存原始 context（attach 传的是 base ContextImpl）：1004 实测 applicationContext
        // 在 Application.attach 时刻仍为 null（LoadedApk 未挂完），存它导致 ctx 路径全程失联
        if (appContext == null) appContext = context
        attemptStart()
    }

    private fun attemptStart() {
        if (started || gaveUp) return
        attempts++
        runCatching {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val channel = openSpyChannel(appContext) ?: error("no usable monitorInput candidate")
            channelRef = channel
            object : InputEventReceiver(channel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        if (event is MotionEvent) onMotion(event, w, h)
                    } finally {
                        finishInputEvent(event, false)
                    }
                }
            }
            started = true
            HLog.i(TAG, "spy started: display ${w}x$h (observe-only, attempts=$attempts)")
        }.onFailure {
            if (attempts >= MAX_ATTEMPTS) {
                gaveUp = true
                HLog.w(TAG, "spy gave up after $attempts attempts (inert): ${it.message}")
            } else {
                HLog.i(TAG, "spy attempt #$attempts failed: ${it.message} — retry in ${RETRY_MS}ms")
                mainHandler.postDelayed({ attemptStart() }, RETRY_MS)
            }
        }
    }

    /**
     * 多候选解析。首选=MiuiBackGestureHook（同 ROM 生产验证）的姿势：SystemUI context
     * 的 getSystemService(InputManager) 实例直接反射 monitorGestureInput——20:40 导出
     * 实锤 InputManagerGlobal.getInstance() 路径 invoke 成功却拿不到 channel（静默
     * continue=返回 null 或 getInputChannel null）。每步结果都落日志，再失败可精确定位。
     * InputMonitor 路径不调 setTouchableRegion（marble 无此方法，gesture monitor=全屏观察）。
     */
    private fun openSpyChannel(context: android.content.Context?): android.view.InputChannel? {
        // 取证：候选类方法清单（低频一次性，恒打）
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
        // 候选 0（首选，MiuiBackGestureHook 同款）：SystemUI context 的 InputManager 服务实例
        if (context != null) {
            runCatching {
                val im = context.getSystemService(android.hardware.input.InputManager::class.java)
                    ?: error("getSystemService(InputManager)=null")
                val m = im.javaClass.methods.firstOrNull {
                    it.name == "monitorGestureInput" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: error("InputManager.monitorGestureInput(String,int) missing")
                val r = m.invoke(im, "hypersidebar-spy", 0)
                HLog.i(TAG, "ctx candidate: monitor=${r?.javaClass?.name}")
                monitorRef = r
                return monitorToChannel(r, "ctx")
            }.onFailure { HLog.w(TAG, "ctx candidate failed: ${it.message}") }
        } else {
            HLog.i(TAG, "ctx candidate skipped: no context yet")
        }
        // 兜底：InputManagerGlobal 单例路径（20:40 实锤拿不到 channel，保留观测）
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
                val res = m.invoke(inst, "hypersidebar-spy", 0)
                HLog.i(TAG, "global candidate $cn.$mn: result=${res?.javaClass?.name}")
                monitorRef = res
                res
            }.getOrElse { failure ->
                HLog.w(TAG, "candidate $cn.$mn failed: ${failure.message}")
                continue
            }
            return monitorToChannel(r, cn.substringAfterLast('.')) ?: continue
        }
        return null
    }

    /** InputMonitor→InputChannel（带逐步日志）；InputChannel 直返。失败返回 null 不抛。
     *  toString 打印 channel/host/surface 三字段——判读"服务端到底回了什么"的钥匙：
     *  host/surface 在而 channel null=unparcel 丢字段；三者全 null=服务端短路回了个空壳。 */
    private fun monitorToChannel(r: Any?, label: String): android.view.InputChannel? {
        if (r == null) {
            HLog.w(TAG, "[$label] monitor invoke returned null")
            return null
        }
        if (r is android.view.InputChannel) return r
        return runCatching {
            HLog.i(TAG, "[$label] monitor=${r}")
            val ch = r.javaClass.getMethod("getInputChannel").invoke(r) as? android.view.InputChannel
            if (ch == null) HLog.w(TAG, "[$label] getInputChannel returned null")
            ch
        }.getOrElse {
            HLog.w(TAG, "[$label] getInputChannel failed: ${it.message}")
            null
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

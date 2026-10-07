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
 * 全反射实现（monitorInput/getInputChannel 均 @hide），任一步失败=惰性退出并 W
 * （romer 可能裁剪；fail-closed 不比现状差）。
 *
 * **启动时序（1005 双时机探针定案）**：SystemUI 起来后 ~0.3s（Application.attach）建的
 * monitor 注册进 dispatcher 但**永久聋**（dumpsys 可见、零事件）；SystemUI 完全初始化后
 * 建的 monitor 事件正常。⇒ 不在 attach 时刻建：+5s 首建 + 15s 观察窗自愈（窗口内零 DOWN
 * =dispose 重建，直到 armed）。dispose 必须在 owner Looper 执行（Android 17 强制）——
 * 本对象全部活动都在主线程 Handler 上。
 */
object SystemUiInputSpy {

    private const val TAG = "SysInputSpy"

    /** receiver 存在（spy 已构建，未必已 armed） */
    @Volatile private var started = false
    /** 首个事件已到达（投递链真正接通）——B2 的可用判据 */
    @Volatile var armed = false; private set
    @Volatile private var gaveUp = false
    private var attempts = 0
    /** 聋 monitor 重建轮次（自愈循环） */
    private var deafRebuilds = 0
    private var appContext: android.content.Context? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /** 构建失败重试（构造抛异常时；1004 教训：attach 极早期 context/display 未就绪） */
    private const val MAX_ATTEMPTS = 12
    private const val RETRY_MS = 5_000L

    /**
     * 自愈式启动参数（1005 双时机探针定案：+0.3s 建的 monitor 注册成功但永久聋
     * ——dumpsys 可见而事件零到达；+60s 建的事件正常。⇒ 不在 attach 时刻建 monitor，
     * 而是延迟首建 + 观察窗自愈：窗口内零 DOWN=判聋=dispose 重建，直到首个事件到达
     * （armed）。阈值无论落在启动后哪一秒，循环自然跨过；重建前 dispose 防泄漏。
     */
    private const val FIRST_BUILD_DELAY_MS = 5_000L
    private const val DEAF_CHECK_WINDOW_MS = 15_000L
    private const val MAX_DEAF_REBUILDS = 12

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
    /** receiver 三保活（参考工程 NativeBackInputMonitor/ContextualSearchInputReceiver
     *  均存字段；native 侧 Looper 注册持一层引用，双保险） */
    @Volatile private var receiverRef: InputEventReceiver? = null

    fun start(context: android.content.Context? = null) {
        if (started || gaveUp) return
        // 存原始 context（attach 传的是 base ContextImpl）：1004 实测 applicationContext
        // 在 Application.attach 时刻仍为 null（LoadedApk 未挂完），存它导致 ctx 路径全程失联
        if (appContext == null) appContext = context
        // 1005 定案：attach 时刻（+0.3s）建的 monitor 永久聋 ⇒ 延迟首建（自愈循环起点）
        mainHandler.postDelayed({ attemptStart() }, FIRST_BUILD_DELAY_MS)
    }

    private fun attemptStart() {
        if (started || gaveUp) return
        attempts++
        runCatching {
            val channel = openSpyChannel(appContext) ?: error("no usable monitorInput candidate")
            channelRef = channel
            receiverRef = object : InputEventReceiver(channel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        if (event is MotionEvent) {
                            // 尺寸逐事件现读（构建时捕获在旋转后全部失效——1005 16:41 导出
                            // 实锤：横屏 x=2306 用竖屏 w=1080 判带恒拒 ⇒ 横屏无法唤出）
                            val dm = android.content.res.Resources.getSystem().displayMetrics
                            onMotion(event, dm.widthPixels, dm.heightPixels)
                        }
                    } finally {
                        finishInputEvent(event, false)
                    }
                }
            }
            started = true
            val dm0 = android.content.res.Resources.getSystem().displayMetrics
            HLog.i(TAG, "spy built: display ${dm0.widthPixels}x${dm0.heightPixels} (observe-only, build #$attempts" +
                "${if (deafRebuilds > 0) ", deaf rebuild #$deafRebuilds" else ""})")
            // 门控快照（1007 导出 162021 教训：角落 DOWN 被门静默拒=导出无法判读是哪个门关了）。
            // sys prefs=启动快照，本行即本进程全部转发决策的依据
            HLog.i(TAG, "forward gates: sysInputOwner=${sysInputOwner()} cornerSwipe=${cornerSwipeEnabled()} " +
                "cornerHot=%=${cornerHotPercent()} band=${bandWidthDp()}dp/${bandHeightPercent()}%+${bandPositionPercent()}%")
            // 聋 monitor 观察窗：窗口内零 DOWN ⇒ dispose 重建（时序根因的自愈收口）
            mainHandler.postDelayed({ deafCheck() }, DEAF_CHECK_WINDOW_MS)
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
     * 聋 monitor 自愈检查（1005 双时机探针定案的收口实现）：build 成功 ≠ 投递链接通
     * （早建 monitor 注册进 dispatcher 却零事件）。观察窗内零 DOWN ⇒ dispose 全套重建；
     * 有事件 ⇒ armed（此后不再检查——spy 无状态，接通即稳定）。
     */
    private fun deafCheck() {
        if (!started || gaveUp || armed) return
        if (totalDown > 0) {
            armed = true
            HLog.i(TAG, "spy armed: events flowing (down=$totalDown)")
            return
        }
        deafRebuilds++
        if (deafRebuilds > MAX_DEAF_REBUILDS) {
            gaveUp = true
            HLog.w(TAG, "spy deaf after $deafRebuilds rebuilds — giving up (inert)")
            return
        }
        HLog.i(TAG, "spy deaf after ${DEAF_CHECK_WINDOW_MS / 1000}s " +
            "(monitor built too early, rebuild #$deafRebuilds)")
        disposeSpy()
        started = false
        attemptStart()
    }

    /** dispose 全套（重建/放弃前调用，防 monitor 泄漏——0501~0505 期间 dumpsys 已漏数个）。
     *  InputMonitor.dispose 经反射（@hide）；receiver.dispose 在 owner Looper（本对象全部
     *  活动都在主线程 Handler 上=合规）。任一步失败只记日志继续。 */
    private fun disposeSpy() {
        runCatching { receiverRef?.dispose() }
            .onFailure { HLog.w(TAG, "receiver dispose failed: ${it.message}") }
        runCatching {
            monitorRef?.let { m -> m.javaClass.getMethod("dispose").invoke(m) }
        }.onFailure { HLog.w(TAG, "monitor dispose failed: ${it.message}") }
        receiverRef = null
        channelRef = null
        monitorRef = null
    }

    /**
     * 多候选解析。首选=MiuiBackGestureHook（同 ROM 生产验证）的姿势：SystemUI context
     * 的 getSystemService(InputManager) 实例直接反射 monitorGestureInput——20:40 导出
     * 实锤 InputManagerGlobal.getInstance() 路径 invoke 成功却拿不到 channel（静默
     * continue=返回 null 或 getInputChannel null）。每步结果都落日志，再失败可精确定位。
     * InputMonitor 路径不调 setTouchableRegion（marble 无此方法，gesture monitor=全屏观察）。
     */
    private fun openSpyChannel(
        context: android.content.Context?,
        monitorName: String = "hypersidebar-spy",
    ): android.view.InputChannel? {
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
                val r = m.invoke(im, monitorName, 0)
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

    // ===== B2-1/B2-2（os4-trigger-final-plan §7）：带内流转发 + 抢占执行 =====
    // 触发带几何（半宽/y 带高/带位）2026-10-07 起全部设置项化（bandWidthDp/bandHeightPercent/
    // bandPositionPercent，参考 MiuiBackGestureHook 百分比模型）；历史沿革：首版 130px 过宽
    // → 32dp（=官方小白条宽）→ 现滑条。密度运行时现算，对屏宽/密度漂移免疫。

    /** B 路线开关（sys 进程 prefs=启动快照，改动须重启 SystemUI 生效——设置页已注明） */
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private fun sysInputOwner(): Boolean = runCatching {
        prefs?.getBoolean(
            com.lsp.hypersidebar.prefs.PrefKeys.SYS_INPUT_OWNER,
            com.lsp.hypersidebar.prefs.LayoutDefaults.SYS_INPUT_OWNER
        ) ?: false
    }.getOrDefault(false)

    /** 当前转发中的手势五要素（DOWN 时快照，供抢占指令匹配） */
    @Volatile private var fwdDownTime = 0L
    @Volatile private var fwdDeviceId = -1
    @Volatile private var fwdSource = 0
    @Volatile private var fwdEdgeFlags = 0
    @Volatile private var fwdPointerId = 0
    /** 当前转发流的区域（DOWN 处判定，单源，随每事件下发；:ui 据此路由状态机） */
    @Volatile private var fwdZone = com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_STRIP

    /** MOVE 合流窗（ms）：窗口内只留最新一帧，终态事件前冲刷。事件时间计。 */
    private const val MOVE_COALESCE_MS = 16L
    /** 合流窗内被扣下的最新 MOVE（框架回收 ev 前的副本；UP/CANCEL 前冲刷保序） */
    private var pendingMove: MotionEvent? = null
    private var lastMoveFwdEventTime = 0L

    /** 注册抢占指令接收器（:ui STALL-TIMER fire → 五要素全匹配才 pilferPointers）。
     *  Token 校验同 QS 点击接收器（0912 审查口径：RECEIVER_EXPORTED 必须带令牌）。 */
    fun registerPilferReceiver(ctx: android.content.Context, modulePrefs: android.content.SharedPreferences) {
        prefs = modulePrefs
        runCatching {
            // 直接用传入的原始 ctx 注册：attach 时刻 applicationContext=null
            //（1005 三犯——start/sink 同款教训，全部收敛为原始 ctx）
            ctx.registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                        if (!sysInputOwner()) return
                        // 全链留痕（低频，不会被 MOVE 刷掉）：token/身份/pilfer 结果都可判读
                        if (!com.lsp.hypersidebar.util.RelayToken.verifyFan(
                                intent, com.lsp.hypersidebar.util.RelayToken.read(modulePrefs)
                            )
                        ) {
                            HLog.w(TAG, "pilfer command rejected: token mismatch")
                            return
                        }
                        val dt = intent.getLongExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_DOWN_TIME, 0L)
                        val dev = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_DEVICE_ID, -1)
                        val src = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_SOURCE, 0)
                        val ef = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_EDGE_FLAGS, 0)
                        val pid = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_POINTER_ID, 0)
                        // 五要素全匹配才碰流（MiuiBackGestureHook accepted-DOWN 令牌模型；
                        // 不匹配=完全不碰——pilfer 不可回滚）
                        val matched = dt == fwdDownTime && dev == fwdDeviceId &&
                            src == fwdSource && ef == fwdEdgeFlags && pid == fwdPointerId
                        if (!matched) {
                            HLog.w(TAG, "pilfer identity mismatch: req(dt=$dt,dev=$dev,src=$src,ef=$ef,pid=$pid) " +
                                "cur(dt=$fwdDownTime,dev=$fwdDeviceId,src=$fwdSource,ef=$fwdEdgeFlags,pid=$fwdPointerId)")
                            return
                        }
                        runCatching {
                            monitorRef?.javaClass?.getMethod("pilferPointers")?.invoke(monitorRef)
                            HLog.i(TAG, "pilfered stream dt=$dt — Rust 判定将收 CANCEL")
                        }.onFailure { HLog.w(TAG, "pilferPointers failed: ${it.message}") }
                    }
                },
                android.content.IntentFilter(com.lsp.hypersidebar.prefs.PrefKeys.SPY_PILFER_ACTION),
                android.content.Context.RECEIVER_EXPORTED
            )
            HLog.i(TAG, "pilfer receiver registered")
        }.onFailure { HLog.w(TAG, "pilfer register failed: ${it.message}") }
    }

    /** 触发带判定（转发前置粗筛；精判定在 ：ui 状态机）：**仅竖屏**——侧带 × 百分比
     *  y 带（2026-10-07 参考 MiuiBackGestureHook：带高/带位双百分比，默认 33/50=
     *  现役 [H/3,2H/3]；OS2/3 launcher 通道不受影响，仍用 GestureThresholds 比例）。
     *  横屏不转发：OS2/3 横屏语义=条上触发（条上 hook 路径成熟且触发面小），
     *  spy 接管横屏既无增益又把返回手势区整段灌进广播（1005 教训）。 */
    private fun inTriggerBand(x: Float, y: Float, w: Int, h: Int): Boolean {
        if (w > h) return false
        val bandPx = bandWidthDp() * android.content.res.Resources.getSystem().displayMetrics.density
        val nearSide = x <= bandPx || x >= w - bandPx
        val bandH = h * bandHeightPercent() / 100f
        val top = (h - bandH) * bandPositionPercent() / 100f
        return y >= top && y <= top + bandH && nearSide
    }

    /** 底角三角（B4 转发门，os4-trigger-final-plan §14.6）：竖屏，左右角窗
     *  （宽=屏宽×CORNER_WIDTH_RATIO，:ui 同键）× 底部热区（屏高百分比，
     *  cornerHotPercent——原 25dp≈3% 实机反馈"不易触发"，升为可调滑条）。与条带在
     *  DOWN 处天然不相交（默认条带下沿 2H/3 << 热区上沿），zone 判定无歧义。 */
    private fun inCornerTriangle(x: Float, y: Float, w: Int, h: Int): Boolean {
        if (w > h) return false
        val hotPx = h * cornerHotPercent() / 100f
        if (y < h - hotPx) return false
        val cornerPx = w * cornerWidthRatio()
        return x <= cornerPx || x >= w - cornerPx
    }

    private fun bandWidthDp(): Int = runCatching {
        prefs?.getInt(
            com.lsp.hypersidebar.prefs.PrefKeys.BAND_WIDTH_DP,
            com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_WIDTH_DP
        ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_WIDTH_DP
    }.getOrDefault(com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_WIDTH_DP)

    private fun bandHeightPercent(): Int = runCatching {
        prefs?.getInt(
            com.lsp.hypersidebar.prefs.PrefKeys.BAND_HEIGHT_PERCENT,
            com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_HEIGHT_PERCENT
        ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_HEIGHT_PERCENT
    }.getOrDefault(com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_HEIGHT_PERCENT)

    private fun bandPositionPercent(): Int = runCatching {
        prefs?.getInt(
            com.lsp.hypersidebar.prefs.PrefKeys.BAND_POSITION_PERCENT,
            com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_POSITION_PERCENT
        ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_POSITION_PERCENT
    }.getOrDefault(com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_POSITION_PERCENT)

    private fun cornerHotPercent(): Int = runCatching {
        prefs?.getInt(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_HOT_PERCENT,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_HOT_PERCENT
        ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_HOT_PERCENT
    }.getOrDefault(com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_HOT_PERCENT)

    private fun cornerWidthRatio(): Float = runCatching {
        prefs?.getFloat(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_WIDTH_RATIO,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_WIDTH_RATIO
        ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_WIDTH_RATIO
    }.getOrDefault(com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_WIDTH_RATIO)

    /** 底角功能开关（与 OS3 EdgeGestureHook 同键；关=角落流不转发） */
    private fun cornerSwipeEnabled(): Boolean = runCatching {
        prefs?.getBoolean(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SWIPE_ENABLED,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SWIPE_ENABLED
        ) ?: false
    }.getOrDefault(false)

    /** 带内 DOWN 起转发该流（UP/CANCEL 结束）。MOVE 合流（1007 晚用户拍板）：
     *  120Hz 逐事件广播在拖选上表现 1-2 帧滞后——16ms 窗口只留最新一帧，
     *  UP/CANCEL 前冲刷保序（:ui 侧判定/合流的回放顺序不变）。 */
    private fun forwardIfNeeded(ev: MotionEvent, w: Int, h: Int) {
        val p = prefs ?: return
        if (!sysInputOwner()) return
        val ctx = appContext ?: return
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            // zone 判定单源（§14.6）：底角三角优先（条带与三角不相交，顺序无歧义）；
            // 底角另有独立开关门控（关=角落流不转发，OS3 同键语义）
            val corner = inCornerTriangle(ev.x, ev.y, w, h)
            if (corner) {
                if (!cornerSwipeEnabled()) return
            } else if (!inTriggerBand(ev.x, ev.y, w, h)) {
                return
            }
            fwdZone = if (corner) {
                com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER
            } else {
                com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_STRIP
            }
            fwdDownTime = ev.downTime
            fwdDeviceId = ev.deviceId
            fwdSource = ev.source
            fwdEdgeFlags = ev.edgeFlags
            fwdPointerId = ev.getPointerId(0)
            pendingMove?.recycle()
            pendingMove = null
            lastMoveFwdEventTime = ev.eventTime
        } else if (fwdDownTime != ev.downTime) {
            return // 非转发流的 MOVE/UP（带外起手）——静默忽略
        }
        if (a == MotionEvent.ACTION_MOVE) {
            if (ev.eventTime - lastMoveFwdEventTime < MOVE_COALESCE_MS) {
                // 窗口内：只留最新（框架会在 finishInputEvent 后回收 ev，故必须 obtain 副本）
                pendingMove?.recycle()
                pendingMove = MotionEvent.obtain(ev)
                return
            }
            lastMoveFwdEventTime = ev.eventTime
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            pendingMove?.let {
                sendStream(it, ctx, w, h, p)
                it.recycle()
            }
            pendingMove = null
        }
        sendStream(ev, ctx, w, h, p)
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            fwdDownTime = 0L
        }
    }

    private fun sendStream(ev: MotionEvent, ctx: android.content.Context, w: Int, h: Int, p: android.content.SharedPreferences) {
        val intent = android.content.Intent(com.lsp.hypersidebar.prefs.PrefKeys.SPY_STREAM_ACTION)
            .setPackage(com.lsp.hypersidebar.prefs.HostPackages.UI_HOST)
            .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_EVENT, ev)
            .putExtra(
                com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_LANDSCAPE,
                w > h
            )
            .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_ZONE, fwdZone)
        com.lsp.hypersidebar.util.RelayToken.attach(
            intent, com.lsp.hypersidebar.util.RelayToken.read(p)
        )
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { HLog.w(TAG, "stream forward failed: ${it.message}") }
    }

    private fun onMotion(ev: MotionEvent, w: Int, h: Int) {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            totalDown++
            if (ev.y > h * 3f / 4f) bottomBandDown++
            if (ev.x < 40 || ev.x > w - 40 || ev.y < 40) edgeBandDown++
            // 首个事件=投递链接通（自愈循环的终止判据；deafCheck 也会兜底置位）
            if (!armed) {
                armed = true
                HLog.i(TAG, "spy armed on first event")
            }
            // 每 DOWN 恒打一行（低频）：判读"流到达 spy"的最小证据
            HLog.i(TAG, "down (${"%.0f".format(ev.x)},${"%.0f".format(ev.y)}) " +
                "src=${ev.source} dev=${ev.deviceId} edge=${ev.edgeFlags} " +
                "t=${ev.downTime}/${ev.eventTime} | totals d=$totalDown bottom=$bottomBandDown edge=$edgeBandDown")
        } else if (HLog.verboseEnabled && (a == MotionEvent.ACTION_MOVE ||
                a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
        ) {
            // B2 转发模式下 MOVE 打点=纯噪音（1000 条环形缓冲 ~2s 即被刷光，pilfer 等
            // 低频关键日志全被冲掉——0505 15:30 导出教训）：仅非转发流记 MOVE
            if (a == MotionEvent.ACTION_MOVE) {
                if (fwdDownTime != ev.downTime) {
                    HLog.i(TAG, "move x=${ev.x.toInt()} y=${ev.y.toInt()} dt=${ev.downTime}")
                }
            } else {
                HLog.i(TAG, "a=$a x=${ev.x.toInt()} y=${ev.y.toInt()} dt=${ev.downTime}")
            }
        }
        // B2-1：带内流转发（关=零开销；带外流在 DOWN 处即被排除）
        if (a == MotionEvent.ACTION_DOWN || fwdDownTime == ev.downTime) {
            forwardIfNeeded(ev, w, h)
        }
    }
}

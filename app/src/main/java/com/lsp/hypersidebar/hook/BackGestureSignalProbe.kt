package com.lsp.hypersidebar.hook

import android.view.MotionEvent
import com.lsp.hypersidebar.util.HLog
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook

/**
 * 信号可得性探针 0（log-only，冻结线上允许的唯一形态：不改任何行为、不新增机制）。
 *
 * 要回答的问题（决定"门控"落 Java 还是必须 native）：
 * OS4 上系统自己的手势识别信号，能否在 SystemUI 的 **Java** 侧观测到、时序多早。
 * 若能——模块就不必再和桌面 Rust 抢跑（当前底角 83 次仅 10 成的根因），改成
 * "等系统表态认领边缘手势 → 模块在 commit 前接管（pilfer 仍有效，参考工程
 * accepted-DOWN 模型）"，误触过滤白捡系统那套（IME/导航栏/单手/锁屏/手掌抑制）。
 *
 * 六个观测点各自回答一件事（全部存在性 + 命中次数 + 时刻，不改 param.result）：
 * 1. EdgeBackGestureHandler.updateIsEnabled —— handler 存活与启停（三代稳定锚点）
 * 2. BackPanelController.onMotionEvent     —— ★ 系统已认领边缘返回手势的跟踪流（含坐标）
 * 3. CrossActivityBackAnimation.onGestureCommitted —— commit 时刻 = 我们的接管窗口下界
 * 4. DisplayBackGestureHandlerImpl 的 inputEventReceiver.onInputEvent
 *    —— OS4 新增的 Java 贴边输入入口（若可解析：连"Java 拿原始流"都有第二条路）
 * 5. LauncherProxyService.onInputConsumerChanged —— launcher（Rust）认领/释放输入消费者
 *    = 所有权变更的 Java 可见信号（也证明 OS4 Rust 仍走 AIDL 消费者协议）
 * 6. LauncherProxyService 其余 LauncherProxyListener 回调（存在性 dump，找 Home 相关）
 *
 * 开关：`adb shell setprop debug.hsb.bgprobe 1`（默认关=完全惰性，重启 SystemUI 生效）。
 * OS2/OS3 上类名/包名有差异 ⇒ 解析失败只记 NOT_FOUND，天然按 ROM 惰性退出。
 * 判读方式见 docs/adaptation/os4-trigger-final-plan.md §15.4 探针 0。
 */
class BackGestureSignalProbe : BaseHook() {

    private val TAG = "BgProbe"

    override val name: String = "BackGestureSignalProbe"

    private val candidates = listOf(
        EdgeCls("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
            "updateIsEnabled"),
        EdgeCls("com.android.systemui.navigationbar.gestural.BackPanelController",
            "onMotionEvent", perEvent = true),
        EdgeCls("com.android.wm.shell.back.BackPanelController",
            "onMotionEvent", perEvent = true),
        EdgeCls("com.android.wm.shell.back.CrossActivityBackAnimation",
            "onGestureCommitted"),
        EdgeCls("com.android.systemui.LauncherProxyService",
            "onInputConsumerChanged"),
        EdgeCls("com.android.systemui.recents.LauncherProxyService",
            "onInputConsumerChanged"),
    )

    private class EdgeCls(val cls: String, val method: String, val perEvent: Boolean = false)

    override fun init() {
        if (!enabled()) {
            HLog.i(TAG, "probe inert: setprop debug.hsb.bgprobe=1 后重启系统界面才开")
            return
        }
        HLog.i(TAG, "probe armed (log-only, 六观测点)")
        candidates.forEach { c -> hookEdge(c) }
        hookDisplayHandler()
        dumpProxyListener()
        // ===== 第二轮（225324 判读后追加）=====
        // 第一轮结论：BackPanelController.onMotionEvent / onGestureCommitted 整窗零命中
        // （38 DOWN、edge=8、bottom=18），EdgeBackGestureHandler 只有启动期 updateIsEnabled
        // ⇒ OS4 贴边手势的认领方大概率不在 SystemUI Java。第二轮问三件收尾的事：
        hookClassAll(
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
            "EdgeHandler.*", 4,
        )
        hookClassAll(
            "com.android.systemui.LauncherProxyService",
            "ProxySvc.*", 4,
        )
        hookMonitorCreators()
        dumpDisplayFields()
    }

    /**
     * 某类全部声明方法 log-only 挂（每方法命中上限 [cap]，超量只计数不再打——
     * 防 0505 那种刷屏冲掉关键行的事故）。回答"SystemUI 这套类在整个手势窗口里
     * 到底有没有被调用过"，比逐个猜方法名可靠。
     */
    private fun hookClassAll(cn: String, label: String, cap: Int) {
        val cl = ClassLoaderProvider.safeClassLoader
        val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: run {
            HLog.i(TAG, "NOT_FOUND class $cn（$label 二轮跳过）")
            return
        }
        val counters = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var n = 0
        c.declaredMethods.forEach { m ->
            runCatching {
                m.isAccessible = true
                m.createBeforeHook { param ->
                    val key = m.name
                    val hits = counters.merge(key, 1) { a, b -> (a ?: 0) + (b ?: 0) } ?: 0
                    if (hits > cap) return@createBeforeHook
                    val mv = param.args.firstOrNull { it is MotionEvent } as? MotionEvent
                    val argDesc = if (mv != null) {
                        "a=${mv.actionMasked} xy=${mv.rawX.toInt()},${mv.rawY.toInt()} t=${mv.eventTime}"
                    } else {
                        param.args.joinToString(",") { a -> a?.toString()?.take(20) ?: "null" }
                    }
                    HLog.i(TAG, "$label${m.name}#$hits($argDesc)")
                }
                n++
            }.onFailure { HLog.w(TAG, "hook $cn#${m.name} failed: ${it.message}") }
        }
        HLog.i(TAG, "$label: ${c.declaredMethods.size} 声明方法，挂上 $n 个（每方法上限 $cap）")
    }

    /**
     * 谁在创建手势监视器：SystemUI 进程内 monitorGestureInput/monitorInput 的调用方栈。
     * 若整窗零调用 ⇒ SystemUI 根本没有自己的边手势输入通道（认领方在桌面 Rust），
     * Java 门控判死；若有调用 ⇒ 打印调用方类名，看它是不是我们该挂的地方。
     */
    private fun hookMonitorCreators() {
        val cl = ClassLoaderProvider.safeClassLoader
        val c = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal", false, cl)
        }.getOrNull() ?: run {
            HLog.i(TAG, "NOT_FOUND InputManagerGlobal（monitor 创建观测跳过）")
            return
        }
        var n = 0
        c.declaredMethods.filter { it.name.startsWith("monitor") }.forEach { m ->
            runCatching {
                m.isAccessible = true
                m.createBeforeHook { param ->
                    if (n >= 6) return@createBeforeHook
                    n++
                    val nameArg = param.args.firstOrNull { it is String } as? String
                    val caller = Thread.currentThread().stackTrace
                        .drop(2)
                        .filter { f ->
                            !f.className.startsWith("android.hardware.input") &&
                                !f.className.startsWith("android.view") &&
                                !f.className.startsWith("com.lsp.hypersidebar")
                        }
                        .take(4)
                        .joinToString(" < ") { f ->
                            "${f.className.substringAfterLast('.')}.${f.methodName}"
                        }
                    HLog.i(TAG, "monitorCreate#$n ${m.name} name=$nameArg by $caller")
                }
                HLog.i(TAG, "hooked monitor creator ${m.name}(${m.parameterTypes.size}p)")
            }.onFailure { HLog.w(TAG, "hook ${m.name} failed: ${it.message}") }
        }
    }

    /**
     * DisplayBackGestureHandlerImpl 第一轮 declaredClasses=0（内部类未加载/命名不同），
     * 改看字段清单——`$inputEventReceiver$1` 之类会以字段存在，字段名+类型能告诉我们
     * OS4 的 Java 边手势输入接收器到底建没建。
     */
    private fun dumpDisplayFields() {
        val cl = ClassLoaderProvider.safeClassLoader
        val c = runCatching {
            Class.forName(
                "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl",
                false, cl
            )
        }.getOrNull() ?: return
        val fs = c.declaredFields.joinToString(" | ") {
            "${it.type.simpleName} ${it.name}"
        }
        HLog.i(TAG, "DisplayHandler fields: $fs")
    }

    private fun enabled(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "debug.hsb.bgprobe") == "1"
    }.getOrDefault(false)

    private fun hookEdge(c: EdgeCls) {
        val cl = ClassLoaderProvider.safeClassLoader
        val clazz = runCatching { Class.forName(c.cls, false, cl) }.getOrNull()
        if (clazz == null) {
            HLog.i(TAG, "NOT_FOUND class ${c.cls}")
            return
        }
        val m = clazz.declaredMethods.firstOrNull { it.name == c.method } ?: run {
            HLog.w(TAG, "NOT_FOUND method ${c.cls}#${c.method}")
            return
        }
        runCatching {
            m.isAccessible = true
            var n = 0
            m.createBeforeHook { param ->
                n++
                val mv = param.args.firstOrNull() as? MotionEvent
                if (c.perEvent) {
                    // 逐事件类只记 DOWN/UP（MOVE 会刷爆环形缓冲，0505 教训）
                    val a = mv?.actionMasked ?: -1
                    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP ||
                        a == MotionEvent.ACTION_CANCEL
                    ) {
                        HLog.i(TAG, "hit#$n ${c.cls.substringAfterLast('.')}.${c.method} " +
                            "a=$a xy=${mv?.let { "${it.rawX.toInt()},${it.rawY.toInt()}" } ?: "-"} " +
                            "t=${mv?.eventTime ?: 0}")
                    }
                } else {
                    val p = param.args.joinToString(",") { it?.toString()?.take(24) ?: "null" }
                    HLog.i(TAG, "hit#$n ${c.cls.substringAfterLast('.')}.${c.method}($p)")
                }
            }
            HLog.i(TAG, "hooked ${c.cls}#${c.method}(${m.parameterTypes.size}p)")
        }.onFailure { HLog.w(TAG, "hook ${c.cls}#${c.method} failed: ${it.message}") }
    }

    /**
     * OS4 新增：per-display 边返回手势的 Java 输入接收器（`$inputEventReceiver$1`，
     * 实现 InputChannelCompat$InputEventListener）。内部类名是编译产物、易漂移，
     * 故按"实现了 InputEventListener 且声明 onInputEvent"扫描，不写死类名。
     */
    private fun hookDisplayHandler() {
        val cl = ClassLoaderProvider.safeClassLoader
        val outer = runCatching {
            Class.forName(
                "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl",
                false, cl
            )
        }.getOrNull() ?: run {
            HLog.i(TAG, "NOT_FOUND DisplayBackGestureHandlerImpl（OS2/3 无此类，正常）")
            return
        }
        var hooked = 0
        outer.declaredClasses.forEach { inner ->
            val m = inner.declaredMethods.firstOrNull { it.name == "onInputEvent" } ?: return@forEach
            runCatching {
                m.isAccessible = true
                var n = 0
                m.createBeforeHook { param ->
                    n++
                    val ev = param.args.firstOrNull() as? MotionEvent
                    val a = ev?.actionMasked ?: -1
                    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP ||
                        a == MotionEvent.ACTION_CANCEL
                    ) {
                        HLog.i(TAG, "hit#$n DisplayHandler.${inner.simpleName}.onInputEvent " +
                            "a=$a xy=${ev?.let { "${it.rawX.toInt()},${it.rawY.toInt()}" } ?: "-"}")
                    }
                }
                hooked++
            }.onFailure { HLog.w(TAG, "hook ${inner.name}#onInputEvent failed: ${it.message}") }
        }
        HLog.i(TAG, "DisplayBackGestureHandlerImpl: 内部类 ${outer.declaredClasses.size} 个，" +
            "onInputEvent 挂上 $hooked 个")
    }

    /** LauncherProxyService 的全部方法清单 dump（一次性）：找 Home/上滑相关的回调名 */
    private fun dumpProxyListener() {
        val cl = ClassLoaderProvider.safeClassLoader
        for (cn in listOf(
            "com.android.systemui.LauncherProxyService",
            "com.android.systemui.recents.LauncherProxyService",
        )) {
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: continue
            val sigs = c.declaredMethods
                .map { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }
                .sorted()
                .joinToString(" | ")
            HLog.i(TAG, "api $cn: $sigs")
            c.declaredClasses.forEach { inner ->
                if (inner.name.contains("Listener")) {
                    val ms = inner.declaredMethods.joinToString(" | ") { mth ->
                        mth.name + "(" + mth.parameterTypes.joinToString(",") { p -> p.simpleName } + ")"
                    }
                    HLog.i(TAG, "api ${inner.name}: $ms")
                }
            }
            return
        }
        HLog.i(TAG, "NOT_FOUND LauncherProxyService（两代包名都没有）")
    }
}

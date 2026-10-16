package com.lsp.hypersidebar.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.MotionEvent
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.RelayToken
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook

/**
 * B2-1 流回流终点（os4-trigger-final-plan §7，:ui 进程）：接收 SystemUI spy 转发的
 * 触发带内手势流，喂给 TurboLayout 条上状态机——判定逻辑单源（计划 §7 铁律），
 * SystemUI 只是哑管道。
 *
 * 门控与退路（与条上 hook 同款）：isOs4Sidebar + 模块启用 + 未熔断 + 数据未死
 *（owner 实例提供，见 TurboLayout.b2Enabled）。条上 hook 让位纯吞（防同一触摸双喂），
 * 本 sink 是竖屏唯一驱动源（全边缘触发恒开，2026-10-13 起无实验开关）。
 *
 *（2026-10-13 清理，用户 m03028）「抢占指令」只保留**底角线**用途：
 * 条上（竖屏触发带）STALL 后的抢占已删除——那条是给 native 的冗余兜底，真机实证
 * pilfer 一开 launcher Rust 立刻收 CANCEL、native 停顿判定链走不完，而 native 才是
 * 判定单源。底角线仍用 [requestPilfer] 夺流（反向接管模型：系统收到 CANCEL 才不弹
 * recents/home），调用方=TurboLayout.feedCornerGesture 的 SHOW 分支。
 *
 * 注册点：[arm] 由 TurboLayout init 调用（:ui），自挂 Application.attach 注册接收器
 * （与 LogDumpBridge/FreeformRelay 同款生命周期，init 早于 attach 恰好赶上）。
 */
object RelayGestureSink {

    private const val TAG = "SpySink"

    @Volatile private var armed = false
    @Volatile private var registered = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var owner: TurboLayout? = null

    /** 当前转发流的手势五要素快照（DOWN 时写入；底角线 [requestPilfer] 的身份匹配用） */
    @Volatile private var streamDownTime: Long = 0L
    @Volatile private var streamDeviceId: Int = -1
    @Volatile private var streamSource: Int = 0
    @Volatile private var streamEdgeFlags: Int = 0
    @Volatile private var streamPointerId: Int = 0
    /** 当前流区域（spy DOWN 处判定单源，随流下发；角落流走独立状态机 §14.6） */
    @Volatile private var streamCorner = false

    /** TurboLayout init 调用（:ui）：挂 attach 钩子，attach 时注册接收器 */
    fun arm(ownerInstance: TurboLayout) {
        if (armed) return
        armed = true
        owner = ownerInstance
        runCatching {
            MethodFinder.fromClass(Application::class.java)
                .filterByName("attach")
                .filterByParamTypes(Context::class.java)
                .firstOrNull()
                ?.createAfterHook {
                    val ctx = it.args[0] as? Context ?: return@createAfterHook
                    register(ctx)
                }
                ?: HLog.w(TAG, "Application.attach hook not found — sink inert")
        }.onFailure { HLog.w(TAG, "arm failed: ${it.message}") }
    }

    private fun register(ctx: Context) {
        if (registered) return
        registered = true
        // 存原始 context（attach 传 base ContextImpl）：applicationContext 在 attach 时刻
        // 仍为 null（LoadedApk 未挂完）——1005 实锤 register NPE，SystemUiInputSpy 同款教训
        appCtx = ctx
        runCatching {
            ctx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val ev = intent.getParcelableExtra<MotionEvent>(PrefKeys.SPY_EXTRA_EVENT)
                            ?: return
                        try {
                            dispatch(c, intent, ev)
                        } finally {
                            ev.recycle()
                        }
                    }
                },
                IntentFilter(PrefKeys.SPY_STREAM_ACTION),
                Context.RECEIVER_EXPORTED
            )
            HLog.i(TAG, "spy stream sink registered (OS4 恒开，b2Enabled 门控)")
        }.onFailure { HLog.w(TAG, "register failed: ${it.message}") }
    }

    private fun dispatch(c: Context, intent: Intent, ev: MotionEvent) {
        val o = owner ?: return
        if (!o.b2Enabled()) return
        val landscape = intent.getBooleanExtra(PrefKeys.SPY_EXTRA_LANDSCAPE, false)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            streamDownTime = ev.downTime
            streamDeviceId = ev.deviceId
            streamSource = ev.source
            streamEdgeFlags = ev.edgeFlags
            streamPointerId = ev.getPointerId(0)
            streamCorner = intent.getIntExtra(
                PrefKeys.SPY_EXTRA_ZONE, PrefKeys.SPY_ZONE_STRIP
            ) == PrefKeys.SPY_ZONE_CORNER
            HLog.i(TAG, "stream DOWN (${ev.rawX.toInt()},${ev.rawY.toInt()}) land=$landscape" +
                if (streamCorner) " zone=corner" else "")
        }
        if (streamCorner) {
            o.feedCornerGesture(
                c, ev,
                sysPilfered = intent.getBooleanExtra(
                    com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_SYS_PILFERED, false
                ),
            )
        } else {
            o.feedStripGesture(c, landscape, ev)
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // 流结束清身份：防后置的过期 requestPilfer 撞旧手势（1005 实锤 mismatch）
            streamDownTime = 0L
        }
    }

    /** 底角线抢占指令：底角锥内确认（TurboLayout feedCornerGesture 的 SHOW）→ 定向广播回
     *  SystemUI，带手势五要素；SystemUI 全匹配才 pilferPointers。条上（竖屏触发带）路径
     *  已于 2026-10-13 删除调用（用户 m03028：那是给 native 的冗余兜底）。 */
    fun requestPilfer() {
        val ctx = appCtx ?: return
        if (streamDownTime == 0L) return
        val o = owner ?: return
        val intent = Intent(PrefKeys.SPY_PILFER_ACTION)
            .setPackage(HostPackages.SYSTEM_UI)
            .putExtra(PrefKeys.SPY_EXTRA_DOWN_TIME, streamDownTime)
            .putExtra(PrefKeys.SPY_EXTRA_DEVICE_ID, streamDeviceId)
            .putExtra(PrefKeys.SPY_EXTRA_SOURCE, streamSource)
            .putExtra(PrefKeys.SPY_EXTRA_EDGE_FLAGS, streamEdgeFlags)
            .putExtra(PrefKeys.SPY_EXTRA_POINTER_ID, streamPointerId)
        RelayToken.attach(intent, RelayToken.read(o.b2Prefs))
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { HLog.w(TAG, "pilfer request failed: ${it.message}") }
        HLog.i(TAG, "pilfer requested: dt=$streamDownTime dev=$streamDeviceId")
    }
}

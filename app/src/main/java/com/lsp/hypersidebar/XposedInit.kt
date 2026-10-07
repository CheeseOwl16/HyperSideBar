package com.lsp.hypersidebar

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.LogDumpBridge
import com.lsp.hypersidebar.hook.BaseHook
import com.lsp.hypersidebar.hook.BackGestureSignalProbe
import com.lsp.hypersidebar.hook.EdgeGestureHook
import com.lsp.hypersidebar.hook.FreeformRelayHook
import com.lsp.hypersidebar.hook.SidebarOverlayProbe
import com.lsp.hypersidebar.hook.SystemUiHook
import com.lsp.hypersidebar.hook.TurboLayout
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefsFiles

class XposedInit : XposedModule() {

    private val TAG = "XposedInit"
    private var turboLayoutHook: TurboLayout? = null
    private var freeformRelayHook: FreeformRelayHook? = null
    private var edgeGestureHook: EdgeGestureHook? = null
    private var systemUiHook: SystemUiHook? = null
    private var sidebarOverlayProbe: SidebarOverlayProbe? = null
    private var backGestureSignalProbe: BackGestureSignalProbe? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // 权威进程名播种（API @NonNull）：极早期 HLog 归类不再依赖任何反射链，
        // 1002 实机 [?] 段教训——全链 null 时 "?" 被缓存，整环标签报废
        HLog.setProcOverride(param.processName)
        // NativeLibLoader 抽 libdexkit.so 需要模块 APK 路径；必须在任何宿主 init 之前就位。
        runCatching { EzXposed.initModuleResources() }
            .onFailure { HLog.w(TAG, "initModuleResources failed: ${it.message}") }
        // wiki legacy 语义（b69f706 定稿）：assets/native_init 声明 + 模块自行
        // System.loadLibrary，框架在 dlopen 时拦截并回调 native_init。
        // 报告走双通道：native 内部照旧打 logcat（tag=HSBNative），同时经
        // NativeProbe.report() 回灌 HLog——logcat 整体失灵（OS4.0.0.14 实机）时
        // 自检导出仍是完整取证链，三段式：loadLibrary 结果 / 报告逐行 / 取报告失败。
        runCatching {
            System.loadLibrary("hypersidebar_native")
            HLog.i("HSBNative", "legacy loadLibrary ok")
            runCatching { com.lsp.hypersidebar.util.NativeProbe.report() }
                .onSuccess { rep ->
                    if (rep.isNullOrEmpty()) {
                        HLog.w("HSBNative", "report null/empty（JNI 符号未导出?）")
                    } else {
                        rep.split('\n').forEach { HLog.i("HSBNative", it) }
                    }
                }
                .onFailure { HLog.w("HSBNative", "report fetch failed: ${it.message}") }
        }.onFailure {
            HLog.e("HSBNative", "legacy loadLibrary FAILED: ${it.message}")
        }
        // 日志桥无条件武装（2026-10-02 晚升级：原 pkg==HOME 门控失效——home 进程
        // 的 onPackageReady 可能不触发或报异名包，条件匹配永远走不到，[launcher]
        // 段在 onModuleLoaded 已跑的情况下依旧拉不出来）。onModuleLoaded=模块在
        // 进程内的最早时刻，挂 Application.attach 全进程通吃；LogDumpBridge 幂等，
        // 各 hook 的富 provider 注册照常升级。HyperOS runtime（Rust 桌面）进程若
        // 有标准 Application 生命周期即被覆盖。
        armEarlyLogDumpBridge()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        EzXposed.initOnPackageReady(param)

        val procName = currentProcessName()
        HLog.i(TAG, "onPackageReady: pkg=${param.packageName} proc=$procName")
        when {
            param.packageName == HostPackages.UI_HOST && procName.endsWith(HostPackages.UI_PROCESS_SUFFIX) -> {
                // 横屏 B 路线触发端 + 竖屏小白条隐藏穿透宿主 + 执行端（fan 选中动作本进程直执行）
                // SyncedPrefs 包装（同 home 端批次 2）：总开关/横屏 dwell 等读取走同步广播
                // 缓存命中，实时性不再单靠 LSPosed push 订阅
                val prefs = com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                if (turboLayoutHook == null) {
                    turboLayoutHook = TurboLayout(prefs)
                }
                if (freeformRelayHook == null) {
                    // prefs 同时供 FreeformRelay 做跨进程广播令牌校验（批次 0 安全修复）
                    freeformRelayHook = FreeformRelayHook(prefs)
                }
                // E0/R1-J 前置探针（OS4 适配）：ISidebarOverlay 呼出链路观测，只打日志。
                // OS2/OS3 无该类=init 内自门控静默退出，三 ROM 通用。
                if (sidebarOverlayProbe == null) sidebarOverlayProbe = SidebarOverlayProbe()
                initHooks(turboLayoutHook!!, freeformRelayHook!!, sidebarOverlayProbe!!)
            }
            param.packageName == HostPackages.HOME && procName == HostPackages.HOME -> {
                // 竖屏边缘手势通道（内滑+停顿零干扰透传；横屏触发已移交 :ui B 路线）
                if (edgeGestureHook == null) {
                    // SyncedPrefs 包装（批次 2）：配置同步广播缓存命中优先——
                    // RemotePreferences 本体是死快照且 LSPosed 框架侧还做进程级
                    // 缓存（重调 API 也是同一实例），配置实时性只能走广播通道
                    edgeGestureHook = EdgeGestureHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                    )
                }
                initHooks(edgeGestureHook!!)
            }
            // 仅主进程：SystemUI 子进程（截图等）若也注册接收器，有序广播可能被
            // 子进程抢答 resultCode=0 覆盖主进程的点击结果
            param.packageName == HostPackages.SYSTEM_UI && procName == HostPackages.SYSTEM_UI -> {
                // 批次 3：QS 磁贴数据层直点桥（click-tile 门禁在回调层，QSTile.click 无约束）
                if (systemUiHook == null) {
                    systemUiHook = SystemUiHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                    )
                }
                // 探针 0（log-only，prop 门控：debug.hsb.bgprobe=1）：判"系统手势识别信号
                // 在 OS4 的 Java 侧是否可得"，决定门控落 Java 还是必须 native。
                if (backGestureSignalProbe == null) backGestureSignalProbe = BackGestureSignalProbe()
                initHooks(systemUiHook!!, backGestureSignalProbe!!)
            }
            else -> HLog.w(TAG, "Skip package/process: pkg=${param.packageName} proc=$procName")
        }
    }

    /** 单次守卫：attach hook 每进程只装一份（各 hook 自己的 attach hook 独立共存） */
    @Volatile
    private var earlyBridgeHooked = false

    /**
     * 日志桥无条件武装（onModuleLoaded 调用，全进程最早时刻）：hook
     * Application.attach 后只注册 LogDumpBridge，不依赖任何包事件分发/hook init。
     * HyperOS runtime（Rust 桌面）进程若有标准 Application 生命周期即被覆盖。
     * 方法找不到 = 记 W 级（本身就是证据）。
     */
    private fun armEarlyLogDumpBridge() {
        if (earlyBridgeHooked) return
        earlyBridgeHooked = true
        runCatching {
            val hooked = MethodFinder.fromClass(android.app.Application::class.java)
                .filterByName("attach")
                .filterByParamTypes(Context::class.java)
                .firstOrNull()
                ?.createAfterHook {
                    val ctx = it.args[0] as? Context ?: return@createAfterHook
                    LogDumpBridge.register(ctx)
                    HLog.i(TAG, "early log dump bridge armed (via Application.attach)")
                }
            if (hooked == null) HLog.w(TAG, "Application.attach hook 未找到方法，日志桥未武装")
        }.onFailure { HLog.w(TAG, "armEarlyLogDumpBridge failed: ${it.message}") }
    }

    /**
     * hook 进程侧 remote prefs 获取（0915 读 libxposed-service 源码纠正了此前的错误认知）。
     *
     * **事实**：读侧的 `RemotePreferences` 永远是 `newInstance` 那一刻的快照——它内部只有一份
     * 本地 `mMap`（`getXxx` 全读它），而 `IXposedService` 的 AIDL 里**没有任何 prefs 变更
     * 推送接口**；`registerOnSharedPreferenceChangeListener` 只对**本进程自己的写入**回调
     * （`Editor.doUpdate()`），hook 进程从不写这些键，且它内部用 `WeakHashMap` 存 listener。
     *
     * 所以此前那条「注册监听是承重件、注册后 push 全量到达、下一呼出即读到新值」的注释与
     * 对应实现都是错的（那个监听既不会被触发、还会被 GC 回收），已删除。
     * 配置实时性完全走 `ConfigSync` 广播 / `ConfigPullBridge` 拉取通道，
     * 读取分流由 `SyncedPrefs` 装饰器负责。
     */
    private fun hookSidePrefs(): SharedPreferences {
        val prefs = getRemotePreferences(PrefsFiles.REMOTE)
        // 高频明细日志开关（§11.2）：init 读一次，此后随 ConfigSync.applySync 刷新
        HLog.verboseEnabled = runCatching {
            prefs.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.DEBUG_VERBOSE_LOGS, false)
        }.getOrDefault(false)
        return prefs
    }

    private fun currentProcessName(): String {
        return try {
            val clz = Class.forName("android.app.ActivityThread")
            val method = clz.getMethod("currentProcessName")
            method.invoke(null) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (h in hooks) {
            if (h.isInit) continue
            try {
                h.init()
                h.isInit = true
                HLog.i(TAG, "Hook [${h.name}] registered OK")
            } catch (e: Exception) {
                HLog.e(TAG, "Hook [${h.name}] FAILED: ${e.message}", e)
            }
        }
    }
}

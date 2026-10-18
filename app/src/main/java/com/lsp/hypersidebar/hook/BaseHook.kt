package com.lsp.hypersidebar.hook


abstract class BaseHook {
    abstract fun init()
    abstract val name: String
    var isInit: Boolean = false

    /**
     * API 102 热重载：旧代被退休前的释放钩子（`XposedInit.onHotReloading` 逐个调用）。
     *
     * 为什么必须实现：热重载会把旧代的 classloader 换成新代，**旧代持有的
     * InputChannel / WindowManager 悬浮窗 / 已注册广播接收器 / 线程与 Handler 回调
     * 都不会自动消失**——它们继续挂在旧 classloader 上（classloader 因此无法回收），
     * 且新代再装一遍就出现"两个 monitor / 两份接收器"的重复资源。
     * 参考工程 MiuiBackGestureHook 的 AGENTS.md 把这条列为硬规则
     * （onHotReloading 里 detach input monitor / overlay / receiver）。
     *
     * 默认空实现：只保证"退出前把已注册的东西摘掉"这条语义对所有子类可用，
     * 没资源的子类不必写。释放必须**幂等**（可能被调用多次）。
     * 注意：**不要**在这里停 native 侧的东西——native hook 绑宿主 spawner 世代，
     * 热重载不重装它（见 XposedInit.onHotReloaded 的说明）。
     */
    open fun releaseForReload() {}

    /**
     * API 102 热重载：**新代**重新武装宿主侧通道（`XposedInit.onHotReloaded` 逐个调用）。
     *
     * 为什么必须有：本模块大量注册体挂在 `Application.attach` 的钩子里（QS 点击接收器、
     * spy 的 pilfer 接收器、各 hook 自己的 broadcast receiver、`ConfigSync`/`LogDumpBridge`
     * 的注册…），而热重载**不会重放 attach**（也不会重放 onModuleLoaded/onPackageLoaded/
     * onPackageReady）。于是 `releaseForReload()` 摘掉它们之后，若不在这里重挂，这些通道会
     * 一直死到宿主进程重启——表现为"热重载后功能少几个、且日志里看不出为什么"。
     *
     * 契约：把注册体抽成这个函数，**attach 钩子里也调它**（单一实现，两条路径共用）。
     * 必须幂等（重复调用不产生重复注册）；失败只记日志。默认空实现=本 hook 没有宿主侧注册。
     */
    open fun rearmHostSide(ctx: android.content.Context) {}
}
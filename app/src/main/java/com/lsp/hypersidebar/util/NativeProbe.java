package com.lsp.hypersidebar.util;

/**
 * native 探针报告取数口（adapt/hyperos4，2026-10-02）。
 *
 * 用纯 Java 静态 native 而非 Kotlin object——@JvmStatic external 的 JNI 符号注册
 * 语义在 object 上有歧义坑，静态方法 + 手写符号名最直白（C 侧
 * Java_com_lsp_hypersidebar_util_NativeProbe_report，进 exports-lsposed.map）。
 *
 * 前置：XposedInit.onModuleLoaded 已 System.loadLibrary 成功；否则调用抛
 * UnsatisfiedLinkError，调用方兜 runCatching 并把失败本身记进 HLog（那也是证据）。
 */
public final class NativeProbe {
    private NativeProbe() {}

    /** 多行 ASCII 报告；so 已加载但符号未导出/未初始化时返回 null 或抛异常 */
    public static native String report();

    /**
     * 补一拍装 hook（spike/os4-native）：native_init 那次尝试可能早于桌面自己
     * System.loadLibrary(libapp_launcher.so)，框架的 dlopen 回调是否覆盖该 namespace 未证，
     * 所以由桌面进程的 Java 侧（TurboLayout init + 延迟）再触发一次。
     * prop `debug.hsb.native.hook` 关=空操作。幂等：已装上就直接返回。
     */
    public static native void retry();

    /**
     * 读一个系统属性（`__system_property_get`，2026-10-14 中间态配套）。
     *
     * 存在的理由：`:ui`（com.miui.securitycenter:ui）里要读 native 发布的 `debug.hsb.evoke`，
     * 而 :ui 侧反射 `android.os.SystemProperties.get` 可能被 hidden API 拒（静默返回空）。
     * 本 .so 在 :ui 里同样是已装载的模块 ⇒ 直接用它读，不经 hidden API。
     *
     * 返回：属性值（属性不存在时为空串）；返回 null 表示本口拿不到值（参数非法/取串失败）
     * ⇒ 调用方回退反射。只在 .so 已载入的进程可用；模块 App 进程没载过会抛
     * UnsatisfiedLinkError（调用方 runCatching 兜住 ⇒ 回退反射）。
     */
    public static native String nativeGetProp(String key);
}

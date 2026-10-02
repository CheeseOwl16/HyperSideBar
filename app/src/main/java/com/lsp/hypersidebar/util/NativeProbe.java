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
}

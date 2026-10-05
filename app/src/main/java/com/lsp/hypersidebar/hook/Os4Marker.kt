package com.lsp.hypersidebar.hook

/**
 * OS4 侧边栏在场标记（跨 hook 共享的只读事实，2026-10-05）：
 *
 * TurboLayout 在锚点解析时测定（`com.miui.sidebar.ISidebarOverlay` AIDL 稳定名存在性，
 * 与混淆漂移无关）。消费方：
 * - FreeformRelayHook 探针应答附带 os4=true → 模块 App 状态卡据此把"触发端（桌面）
 *   无应答"判为 OS4 预期形态（桌面纯 native 无 Java hook，U2 定案）而非通道异常；
 * - 后续任何需要"当前宿主是否 OS4 侧边栏形态"的判定都从这里读，勿再各自探测。
 */
object Os4Marker {
    @Volatile
    var sidebarPresent: Boolean = false
}

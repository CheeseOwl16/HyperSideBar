# ===== Xposed 模块入口（必保）=====
# META-INF/xposed/java_init.list 点名 com.lsp.hypersidebar.XposedInit：
# 该资源文件不会被 R8 重写，类若被混淆改名 LSPosed 将找不到模块入口。
# 同时保留 public 成员——libxposed 以反射回调生命周期（onPackageLoaded 等）。
-keep public class com.lsp.hypersidebar.XposedInit { public *; }

# Manifest 组件（MainActivity/AllAppsActivity/ShortcutRelayReceiver）由 AGP
# 默认规则自动保留，无需手写。
# 跨进程/字符串寻址的类名核对清单（改动时过一遍）：
#   - ShortcutRelayReceiver：DirectLaunchStrategy.setClassName 字符串直达（AGP 规则已保原名）
#   - 宿主/框架类（com.miui.dock.*、MiuiMultiWindowUtils、ActivityThread）均为
#     字符串反射目标，不在本 APK 内，不受 R8 影响

# 字符串式 Class.forName 找**自己人**（FanUiWarmup 预热清单等）依赖原始类名——
# R8 重命名后全量 miss（1002 OS4 实机 8 条 preload miss 实锤）。模块反射面多，
# 统一保名（只防混淆不防裁剪，体积代价=名字符串）。
-keepnames class com.lsp.hypersidebar.**

# 崩溃栈可读性（映射文件在 outputs/mapping/release/）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# @hide stub 编译桩（SystemUiInputSpy 用）：运行时 android.view.InputEventReceiver /
# InputChannel 由 boot classloader 真类顶替（parent delegation），本 dex 里的桩只是
# 编译期形状。但 R8 若重命名桩，子类字节码里的 superclass 引用会指向改名后的桩
# （空实现！）——必须保名，否则 spy 收不到事件且无报错。
-keepnames class android.view.InputChannel
-keepnames class android.view.InputEventReceiver

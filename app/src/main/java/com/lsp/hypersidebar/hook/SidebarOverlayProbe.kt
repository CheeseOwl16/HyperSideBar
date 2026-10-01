package com.lsp.hypersidebar.hook

import android.content.Context
import com.lsp.hypersidebar.util.HLog
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * E0/R1-J 前置探针（OS4，adapt/hyperos4）：securitycenter `:ui` 进程内观测
 * "launcher 侧滑停顿 → ISidebarOverlay 呼出"链路，只打日志不拦截。
 *
 * 背景（docs/adaptation/os4-native-hook-plan.md §11-12 / marble-os4 核验 §7）：
 * OS4 桌面原生"贴边滑+停顿"判定后，经 binder 调 securitycenter 的
 * `com.miui.sidebar.ISidebarOverlay`（AIDL，服务端实现
 * `com.miui.gamebooster.service.DockWindowManagerService$SidebarOverlayBinder`，
 * 该服务跑在 :ui 进程）。R1-J 路线=挂这个入口把"呼出 NewDock"改道为"呼出扇形"；
 * 本探针=它的前置测绘（os4-native-hook-plan §12）：确认侧滑停顿时稳定回调、
 * 参数含义（第 5 个 int 疑似 radius/dockType 待对齐）、调用频率、
 * SideSlideHold 设置值与命名空间。
 *
 * 挂点解析**不依赖混淆名**：接口名 `com.miui.sidebar.ISidebarOverlay` 未混淆（AIDL
 * 稳定），在其 abstract 方法里找唯一的 `(IIIII)V`（marble OS4.0.0.12 与
 * dada OS4.0.0.16 均为 `p7`，但名字会随混淆轮次漂移，签名形状才是锚）；再挂实现类
 * 同名方法。注意**不要**挂到实现类里同签名的私有主线程转发助手（dada/marble 均为
 * `O9` 系）——AIDL 入口在 binder 线程，转发助手在主线程，挂错会重复计数。
 *
 * OS2/OS3 无此类（版本差异已证）⇒ 类解析失败即静默退出，天然按 ROM 门控。
 */
class SidebarOverlayProbe : BaseHook() {

    private val TAG = "SidebarProbe"

    /** 接口全名（未混淆，AIDL 稳定） */
    private val ITF = "com.miui.sidebar.ISidebarOverlay"

    /** 实现（binder 服务端）；内含同签名私有主线程转发助手，只挂接口解析出的入口名 */
    private val IMPL = "com.miui.gamebooster.service.DockWindowManagerService\$SidebarOverlayBinder"

    /** 设置键（securitycenter DockDescPreference / launcher backup_settings 读写） */
    private val KEY_HOLD = "SideSlideHold"

    private var loggedFirstCall = false

    override val name: String = "SidebarOverlayProbe"

    override fun init() {
        val cl = ClassLoaderProvider.safeClassLoader
        val itf = runCatching { Class.forName(ITF, false, cl) }.getOrNull() ?: run {
            HLog.i(TAG, "probe: $ITF not found (OS2/OS3?) — probe inert")
            return
        }
        val impl = runCatching { Class.forName(IMPL, false, cl) }.getOrNull() ?: run {
            HLog.w(TAG, "probe: impl $IMPL not found — probe inert")
            return
        }

        // 接口里唯一的 (IIIII)V abstract 方法 = 侧滑停顿呼出入口
        val entry: Method = itf.declaredMethods.filter { m ->
            Modifier.isAbstract(m.modifiers) &&
                m.returnType == Void.TYPE &&
                m.parameterTypes.size == 5 &&
                m.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.singleOrNull() ?: run {
            HLog.w(TAG, "probe: expected exactly 1 (IIIII)V abstract method on $ITF — probe inert")
            return
        }
        HLog.i(TAG, "probe: entry resolved: $ITF.${entry.name}(IIIII)V")

        runCatching {
            val m = impl.getDeclaredMethod(entry.name, *entry.parameterTypes)
            m.isAccessible = true
            m.createBeforeHook { param ->
                val p = param.args.joinToString(" ")
                if (!loggedFirstCall) {
                    loggedFirstCall = true
                    HLog.i(TAG, "first call: $p — 5th param semantics TBD (radius vs dockType)")
                    logHoldSetting()
                }
                HLog.i(TAG, "evoke: $p")
            }
            HLog.i(TAG, "probe: hooked $IMPL.${entry.name}(IIIII)V (log-only, no interception)")
        }.onFailure { HLog.w(TAG, "probe: hook failed: ${it.message}") }
    }

    /** SideSlideHold 设置值三命名空间探测（§12-1）：securitycenter 写入，类型待实测 */
    private fun logHoldSetting() {
        val ctx = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
        if (ctx == null) {
            HLog.w(TAG, "hold setting probe: no app context")
            return
        }
        val cr = ctx.contentResolver
        for (label in listOf("system", "secure", "global")) {
            val s: String? = when (label) {
                "system" -> runCatching { android.provider.Settings.System.getString(cr, KEY_HOLD) }.getOrNull()
                "secure" -> runCatching { android.provider.Settings.Secure.getString(cr, KEY_HOLD) }.getOrNull()
                else -> runCatching { android.provider.Settings.Global.getString(cr, KEY_HOLD) }.getOrNull()
            }
            val i: Int? = when (label) {
                "system" -> runCatching { android.provider.Settings.System.getInt(cr, KEY_HOLD, Int.MIN_VALUE) }.getOrNull()
                "secure" -> runCatching { android.provider.Settings.Secure.getInt(cr, KEY_HOLD, Int.MIN_VALUE) }.getOrNull()
                else -> runCatching { android.provider.Settings.Global.getInt(cr, KEY_HOLD, Int.MIN_VALUE) }.getOrNull()
            }
            HLog.i(TAG, "hold setting [$label] str=$s int=$i")
        }
    }
}

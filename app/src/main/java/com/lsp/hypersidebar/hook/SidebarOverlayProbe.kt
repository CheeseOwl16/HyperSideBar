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
 * 稳定），挂其**全部 abstract 方法**（1003 实测：只挂 (IIIII)V 零命中而 dock 窗口
 * 照常膨胀——真实入口未必是签名形状最特别的那一个，全方法覆盖才是下界）；
 * 另挂 onBind（服务绑定观测）与 dock 管理器 y2 show（.12 名 xb.l0，漂移则跳过）。
 * 注意**不要**挂到实现类里同签名的私有主线程转发助手（dada/marble 均为
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

    /** dock 窗口管理器（.12 名 xb.l0，混淆名会漂移；解析失败仅跳过，不致命） */
    private val MGR = "xb.l0"

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

        // 接口全部 abstract 方法逐个挂（1003 实测教训：只挂 (IIIII)V 唯一项=盲挂，
        // 呼出零命中而 dock 窗口照常膨胀——真实入口未必是 p7，全方法覆盖是下界）
        var hookedCount = 0
        for (entry in itf.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }) {
            runCatching {
                val m = impl.getDeclaredMethod(entry.name, *entry.parameterTypes)
                m.isAccessible = true
                m.createBeforeHook { param ->
                    val p = param.args.joinToString(" ")
                    HLog.i(TAG, "evoke ${entry.name}(${entry.parameterTypes.size}p): $p")
                    if (!loggedFirstCall) {
                        loggedFirstCall = true
                        logHoldSetting()
                    }
                }
                hookedCount++
            }.onFailure { HLog.w(TAG, "probe: hook ${entry.name} failed: ${it.message}") }
        }
        HLog.i(TAG, "probe: $hookedCount/${itf.declaredMethods.size} interface methods hooked (log-only)")

        // onBind 观测（1003：binder 经 DockWindowService action 绑定发布——桌面是否真
        // bind 过、何时 bind，是"全局侧边栏是否走 ：ui"的判据）
        runCatching {
            val onBind = impl.enclosingClass?.getDeclaredMethod("onBind", android.content.Intent::class.java)
            onBind?.isAccessible = true
            onBind?.createBeforeHook { param ->
                val action = (param.args[0] as? android.content.Intent)?.action
                HLog.i(TAG, "onBind action=$action")
            }
        }.onFailure { HLog.w(TAG, "probe: onBind hook failed: ${it.message}") }

        // 直挂 dock 窗口管理器 show（xb.l0.y2 5 参，.12 名；.14 若改名则跳过——
        // 这是不经 binder 的 :ui 内部 show 唯一门，呼出时它响=:ui 参与）
        runCatching {
            val mgr = Class.forName(MGR, false, cl)
            val y2 = mgr.declaredMethods.singleOrNull { m ->
                m.parameterTypes.size == 5 && m.parameterTypes.all { it == Int::class.javaPrimitiveType } &&
                    m.returnType == Void.TYPE
            }
            y2?.isAccessible = true
            y2?.createBeforeHook { param ->
                HLog.i(TAG, "mgr show(${y2!!.name}): ${param.args.joinToString(" ")}")
            }
            HLog.i(TAG, "probe: mgr ${MGR}.${y2?.name}(IIIII)V ${if (y2 != null) "hooked" else "not-found (renamed?)"}")
        }.onFailure { HLog.w(TAG, "probe: mgr hook failed: ${it.message}") }
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

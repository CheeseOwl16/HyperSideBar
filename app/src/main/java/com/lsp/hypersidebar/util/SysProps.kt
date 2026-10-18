package com.lsp.hypersidebar.util

import com.lsp.hypersidebar.util.HLog.i
import com.lsp.hypersidebar.util.HLog.w

/**
 * 读系统属性的唯一入口（2026-10-18 收口，原 TurboLayout.sysPropGet）：
 * **首选模块自己的 .so**（`NativeProbe.nativeGetProp` → `__system_property_get`，绕开
 * hook 进程里的 hidden API 限制），失败才回退反射 `android.os.SystemProperties.get`。
 *
 * 2026-10-14：真机上 `setprop debug.hsb.native.strict 1` 后 :ui 一行 `AUTH` 都没有，
 * 而"反射被拒"会静默成空串 ⇒ 改成本口优先 + 一次性取证日志。
 * 2026-10-18：反射 Method 改为初始化时解析一次（原每次调用都 getMethod）。
 */
object SysProps {
    private const val TAG = "SysProps"
    private var jniOk = false
    private var jniWarned = false

    /** `SystemProperties.get(String, String)`——hidden API，初始化时解析一次。 */
    private val propGetMethod: java.lang.reflect.Method? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    }.getOrNull()

    fun get(key: String): String {
        val viaJni = runCatching {
            NativeProbe.nativeGetProp(key)
        }.onFailure { t ->
            if (!jniWarned) {
                jniWarned = true
                w(TAG, "nativeGetProp 不可用（本进程未载模块 so）：${t.javaClass.simpleName} ⇒ 属性读取回退反射")
            }
        }.getOrNull()
        if (viaJni != null) {
            if (!jniOk) {
                jniOk = true
                i(TAG, "属性读取走 nativeGetProp（__system_property_get，绕开 hidden API）")
            }
            return viaJni
        }
        return runCatching { propGetMethod?.invoke(null, key, "") as? String ?: "" }
            .getOrDefault("")
    }
}

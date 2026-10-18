package com.lsp.hypersidebar.util

import android.content.Context
import android.util.Log

/**
 * ROM 侧"小窗资格"名单（2026-10-17 离线判读 + 反编译实证）。
 *
 * 来源：`android.util.MiuiMultiWindowUtils.getFreeformSuggestionList(Context) → List<String>`。
 * 证据：安全中心自己的代码就是这么调的（`sec-os3-full/sources/p214P3/AbstractC1665b.java:185`，
 * 反射 + `Log.d("FreeformWindowUtils", "…getFreeformSuggestionList cost …")`）。
 * 工具：`tools/scan_freeform_allowlist.py`（在固件里扫这个名字，可复现）。
 *
 * 用途：取代"宿主包失败再试模块包"的纯试错（提交 `11a1b21` 的临时修复）——先按名单判一次，
 * 明确不在名单里的包直接跳过，省掉一次必然失败的 `getActivityOptions`。
 *
 * **fail-closed**：拿不到名单（类/方法缺失、被隐藏 API 限制挡下）⇒ 一律返回 null（"未知"），
 * 调用方保持既有行为（按序全试）——绝不因为拿不到名单就改变小窗判定。
 */
object FreeformEligibility {

    private const val TAG = "FreeformEligibility"
    private const val UTILS_CLASS = "android.util.MiuiMultiWindowUtils"

    /** 名单缓存（进程内一次；名单随 ROM 版本固定）。null = 本 ROM 取不到。 */
    @Volatile private var cached: Set<String>? = null
    @Volatile private var tried = false

    /** 名单本身；null = 取不到（未知）。 */
    fun packages(context: Context): Set<String>? {
        if (tried) return cached
        synchronized(this) {
            if (tried) return cached
            val r = runCatching {
                val cls = Class.forName(UTILS_CLASS)
                val m = cls.getMethod("getFreeformSuggestionList", Context::class.java)
                val list = m.invoke(null, context) as? List<*>
                list?.mapNotNull { it as? String }?.toSet()
            }
            cached = r.getOrNull()
            tried = true
            if (cached == null) {
                Log.i(TAG, "资格名单不可用（${r.exceptionOrNull()?.message}）⇒ 调用方按'未知'处理")
            } else {
                Log.i(TAG, "资格名单可用：${cached!!.size} 个包")
            }
            return cached
        }
    }

    /** `true` 在名单里 / `false` 明确不在 / `null` 未知（名单取不到）。 */
    fun isEligible(context: Context, pkg: String): Boolean? = packages(context)?.contains(pkg)
}

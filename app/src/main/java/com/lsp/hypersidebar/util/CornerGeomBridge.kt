package com.lsp.hypersidebar.util

import android.content.Context
import android.content.SharedPreferences
import com.lsp.hypersidebar.hook.GestureThresholds
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import java.util.concurrent.Executors

/**
 * 手势几何跨进程桥（2026-10-11 引入时只服务底角；2026-10-12 起兼负侧滑触发带）：
 * 把 `:ui` 侧判定用的几何**写进系统属性**，供 `com.miui.home` 里的 native 门控读同一份值。
 *  - 底角（[push]）：角区宽度 / 底部热区，送**比例**——native 在角区锚点上能读到自己那份
 *    权威 w/h（Impl self +0x260/+0x244），比例就地换算；
 *  - 侧滑触发带（[pushBand]）：条带半宽 / y 带高 / 带位，送 **px**——抑制点
 *    `handle_back_gesture` 读不到权威 w/h（见该函数注释），换算只能在 Java 侧做。
 *
 * ## 为什么需要（不修会怎样）
 * native 门控（`hypersidebar_native.c` 的 `hsb_corner_gate`）与 `:ui` 的 `CornerTrigger`
 * 各持一份几何常量：native 原用 `W/5`（=216px），`:ui` 用 `W×cornerWidthRatio`
 * （默认 0.12=129.6px）。左角 `x∈(129.6, 216)` 这段里 native 判"是角区"⇒ 把系统 Home
 * 手势放弃，而 `:ui` 因 `x > cornerWidth` 不 claim ⇒ **手势被吞，扇形和 Home 都没有**。
 * 更糟的是用户拖 `cornerWidthRatio` 滑条会让这段死带实时变宽（0.10~0.25 对应
 * 108~270px，死带 0~54px）。y 方向同理（`cornerHotPercent` 3~25 对应 72~600px）。
 *
 * ## 为什么走属性而不是广播
 * native 门控跑在 `com.miui.home`，而 OS4 上**该进程没有模块 Java 半侧**
 * （`os4-native-spike-step1-testplan.md` §6.5：桌面进程一行 XposedInit/HLog 都没有，
 * 所以 `ConfigSync` 那条广播链在桌面收不到）。`System.loadLibrary` 也不会被调用。
 * 系统属性是唯一"Java 侧写、native 侧读"且不依赖 Java 半侧在场的通道——native 已经在读
 * `debug.hsb.native.*` 四个属性，机制现成。
 *
 * ## 谁来写
 * **模块 App 进程**（设置页宿主）：它是本工程里唯一已实证持 root 的进程
 * （`ShortcutLauncher.isRootAvailable` / `NativeRootProbe.runRoot` 都跑在这里）。
 * `:ui` 虽然也能读到配置，但它的 root 能力未实证，故不依赖它。
 * 因此本桥的调用点是**模块侧配置推送路径**（`ConfigSync.ensureModuleSide`）：
 * 每次设置页写入/绑定完成都跟着推一次。用户不改设置时不推——属性常驻，无需反复写。
 *
 * ## 口径（必须与 :ui 完全一致）
 * 与 `SystemUiInputSpy.inCornerTriangle` / `TurboLayout.feedCornerGesture` 同源：
 *   角区宽 = `round(widthPixels × cornerWidthRatio)`
 *   热区高 = `round(heightPixels × cornerHotPercent / 100)`
 * 用**真实屏**尺寸（`WindowManager.currentWindowMetrics`，与 `dm.widthPixels` 同值）。
 *
 * ## 失败语义
 * 无 root / su 失败 / 属性写不进去 ⇒ 只记日志，**不抛**：native 侧回落它自己的兜底值
 * （合法配置里最窄的一侧），最坏只是 native 少让路一次，不会吞手势。
 */
object CornerGeomBridge {

    private const val TAG = "CornerGeom"

    /**
     * 属性名用 `persist.` 前缀而**不是** `debug.`：`debug.*` 每次重启被清空，而本桥只在
     * "用户改设置"时才推一次（没人改就永远不推）⇒ 用 `debug.` 的话重启后 native 永远
     * 只能读到兜底值，用户设的角区宽度静默失效。`persist.` 由 init 落盘到
     * /data/property 并在开机时回灌，写一次长期有效。
     * 卸载模块后属性会残留（两个小整数，无害）。
     */
    const val PROP_W_RATIO = "persist.hsb.corner.w_ratio"
    const val PROP_HOT_PCT = "persist.hsb.corner.hot_pct"

    /** 单线程串行：避免设置页快速连拖滑条时并发起一堆 su */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CornerGeomBridge").apply { isDaemon = true }
    }

    /** 最近一次写入值（仅去重用；跨进程真值在属性里） */
    @Volatile private var lastW: String? = null
    @Volatile private var lastHot: String? = null

    /**
     * 按当前配置推一次。幂等：值没变则跳过（滑条拖动会高频触发）。
     * 非阻塞——su 在后台线程跑，设置页不卡。
     *
     * ⚠ **只推比例，不推像素**（2026-10-11 实机修正）：先前这里自己按 WindowManager
     * 算像素，`maxOf/minOf` 用反了 ⇒ 写出 `2400×0.18=432`（正确是 `1080×0.18=194`），
     * native 侧又因超上限而回落兜底。屏宽在横竖屏/折叠/多窗下本就不该由另一个进程
     * 代算——native 手里的 w/h 来自 Impl self（+0x260/+0x244），与手势引擎同源，权威。
     */
    fun push(context: Context, prefs: SharedPreferences) {
        val ratio = runCatching {
            prefs.getFloat(PrefKeys.CORNER_WIDTH_RATIO, LayoutDefaults.CORNER_WIDTH_RATIO)
        }.getOrDefault(LayoutDefaults.CORNER_WIDTH_RATIO)
        val hotPercent = runCatching {
            prefs.getInt(PrefKeys.CORNER_HOT_PERCENT, LayoutDefaults.CORNER_HOT_PERCENT)
        }.getOrDefault(LayoutDefaults.CORNER_HOT_PERCENT)

        // 值与上次相同就不重复起 su（滑条拖动会高频回调）
        val wStr = String.format(java.util.Locale.US, "%.4f", ratio)
        val hStr = hotPercent.toString()
        if (wStr == lastW && hStr == lastHot) return
        lastW = wStr
        lastHot = hStr

        executor.execute {
            val ok = runCatching {
                // 一次 su 写两个属性（每条 su 都有进程开销，合并省一半）。命令是纯数字，无注入面。
                val cmd = "setprop $PROP_W_RATIO $wStr; setprop $PROP_HOT_PCT $hStr"
                val p = ProcessBuilder("su", "-c", cmd).start()
                val finished = p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroy()
                    HLog.w(TAG, "su timeout: $cmd")
                    false
                } else {
                    val rc = p.exitValue()
                    if (rc != 0) HLog.w(TAG, "su exit=$rc: $cmd")
                    rc == 0
                }
            }.onFailure { HLog.w(TAG, "su failed: ${it.message}") }.getOrDefault(false)

            if (!ok) {
                // 失败不抛：native 回落兜底（合法配置最窄侧），最坏少让路一次
                HLog.w(TAG, "corner geom 未写入（native 将用兜底值）: w_ratio=$wStr hot_pct=$hStr")
                return@execute
            }

            // 回读校验：setprop 退出码 0 不代表值真写进去了（selinux/属性名非法会静默失败），
            // 而这条链失败的表现是"角区手感变了"——不校验就只能靠猜。
            val readBack = runCatching {
                fun one(k: String): String {
                    val p = ProcessBuilder("getprop", k).start()
                    val v = p.inputStream.bufferedReader().use { it.readText() }.trim()
                    p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    return v
                }
                one(PROP_W_RATIO) to one(PROP_HOT_PCT)
            }.getOrNull()

            if (readBack != null && readBack.first == wStr && readBack.second == hStr) {
                HLog.i(TAG, "pushed corner geom: w_ratio=$wStr hot_pct=$hStr")
            } else {
                HLog.w(
                    TAG,
                    "corner geom 回读不符：期望 $wStr/$hStr，实读 ${readBack?.first}/${readBack?.second}（native 将用兜底值）"
                )
            }
        }
    }

    // ================= 侧滑触发带（2026-10-12，native 侧滑线 S3 抑制档用） =================

    /**
     * 触发带几何属性（px）。native 侧 `hsb_band_geom` 读它们判定"带内起手"：
     * `nearSide = x <= w_px || x >= screen_w_px - w_px`；`bandH = h_px`；
     * `top = top_px`；竖屏判定用 `screen_w_px < screen_h_px`。
     */
    const val PROP_BAND_W_PX = "persist.hsb.band.w_px"
    const val PROP_BAND_H_PX = "persist.hsb.band.h_px"
    const val PROP_BAND_TOP_PX = "persist.hsb.band.top_px"
    const val PROP_BAND_SCREEN_W_PX = "persist.hsb.band.screen_w_px"
    const val PROP_BAND_SCREEN_H_PX = "persist.hsb.band.screen_h_px"

    /**
     * 活体朝向位（0=竖屏 / 1=横屏），2026-10-13 新增。
     *
     * 为什么必需：上面两个屏尺寸字段按**竖屏归一**（见 [bandPx]），native 侧原本想用
     * `screen_w >= screen_h` 自己认出横屏 ⇒ 恒假（属性里永远是 min/max），横屏时它照竖屏
     * 带判定，左/右边缘底部那段会被误判成"带内停顿"而误吞。朝向只有 Java 侧知道，故由这里给真值；
     * native 读不到（或属性停在旧值）时回落到它自己的"事件自证"兜底（坐标越界即拒）。
     */
    const val PROP_BAND_LANDSCAPE = "persist.hsb.band.landscape"

    /**
     * 停顿判据（`suppress=2` 档用）：native 侧的"是否停顿"必须与 `:ui` 的 STALL 定时器
     * 同参，所以阈值也从这里推，不在 C 里再抄一份常量（否则又是三处各一份的漂移）。
     */
    const val PROP_STRIP_DWELL_MS = "persist.hsb.strip.dwell_ms"
    const val PROP_STRIP_STALL_SPEED = "persist.hsb.strip.stall_speed"

    /**
     * 确认门（距离 + 角度）与滑回滞回：native 必须先过与 `:ui` **同一道**"滑动确认"门，
     * 计时/速度窗才允许起算（`:ui` 的 250ms 定时器正是挂在确认那一刻）。
     * 少了这一道，两边相位差 150ms+，判"停顿"永远对不齐（2026-10-12 真机 6:1 分歧的根因）。
     */
    const val PROP_STRIP_CONFIRM_PX = "persist.hsb.strip.confirm_px"
    const val PROP_STRIP_RESET_PX = "persist.hsb.strip.reset_px"
    const val PROP_STRIP_MAX_TAN = "persist.hsb.strip.max_tan"

    /** 触发带几何（px）。`screenW/H` 永远是**竖屏**口径（min/max 归一）。 */
    internal data class BandPx(
        val screenW: Int,
        val screenH: Int,
        val wPx: Float,
        val hPx: Float,
        val topPx: Float,
    )

    /**
     * 触发带换算（纯函数，单测守公式）：与 `:ui` 的 `SystemUiInputSpy.inTriggerBand` 同公式
     * ——半宽用 dp×density，y 带用屏高百分比 + 带位百分比。
     *
     * ⚠ 屏尺寸按**竖屏**归一（min=宽/max=高）：模块 App 进程自己的朝向可能不是竖屏，
     * 而这条带只服务竖屏手势，归一后与朝向无关。native 侧再用 `screenW < screenH` 兜一层。
     */
    internal fun bandPx(
        screenW: Int,
        screenH: Int,
        density: Float,
        bandDp: Int,
        heightPercent: Int,
        positionPercent: Int,
    ): BandPx {
        val w = minOf(screenW, screenH)
        val h = maxOf(screenW, screenH)
        val wPx = bandDp * density
        val hPx = h * heightPercent / 100f
        val topPx = (h - hPx) * positionPercent / 100f
        return BandPx(w, h, wPx, hPx, topPx)
    }

    @Volatile private var lastBand: String? = null

    /**
     * 按当前配置推一次触发带几何给 native（幂等：值没变即跳过，与 [push] 同）。
     *
     * ⚠ **不做回读校验**（[push] 有）：native 侧每次刷新会打 `band geom ok …` 一行，
     * 那是"属性真送达没送达"的权威依据，还额外证明 native 算出的带对不对。
     * 回读只能证明 setprop 成功，反而多两次 su。
     */
    fun pushBand(prefs: SharedPreferences, landscapeHint: Boolean? = null) {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val g = bandPx(
            dm.widthPixels,
            dm.heightPixels,
            dm.density,
            prefs.getInt(PrefKeys.BAND_WIDTH_DP, LayoutDefaults.BAND_WIDTH_DP),
            prefs.getInt(PrefKeys.BAND_HEIGHT_PERCENT, LayoutDefaults.BAND_HEIGHT_PERCENT),
            prefs.getInt(PrefKeys.BAND_POSITION_PERCENT, LayoutDefaults.BAND_POSITION_PERCENT),
        )
        // Locale.US：属性值是纯数字，别让地区设置写出逗号小数点（native strtof 会读成整数部分）
        fun f(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
        val dwellMs = prefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)
        // 确认门：距离（用户可配）→ px，角度→tan（native 用一次乘法比夹角，不引 math.h）
        val distanceDp = prefs.getFloat(
            PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP
        )
        val confirmPx = distanceDp * dm.density
        val resetPx = confirmPx * GestureThresholds.SWIPE_RESET_RATIO
        val maxTan = Math.tan(Math.toRadians(GestureThresholds.MAX_SWIPE_ANGLE_DEG.toDouble())).toFloat()
        val values = listOf(
            PROP_BAND_W_PX to f(g.wPx),
            PROP_BAND_H_PX to f(g.hPx),
            PROP_BAND_TOP_PX to f(g.topPx),
            PROP_BAND_SCREEN_W_PX to g.screenW.toString(),
            PROP_BAND_SCREEN_H_PX to g.screenH.toString(),
            PROP_BAND_LANDSCAPE to if (landscapeHint ?: landscapeNow()) "1" else "0",
            PROP_STRIP_DWELL_MS to dwellMs.toString(),
            PROP_STRIP_STALL_SPEED to f(GestureThresholds.STALL_MAX_SPEED_PX_S),
            PROP_STRIP_CONFIRM_PX to f(confirmPx),
            PROP_STRIP_RESET_PX to f(resetPx),
            PROP_STRIP_MAX_TAN to f(maxTan),
        )
        val key = values.joinToString("|") { it.second }
        if (key == lastBand) return
        lastBand = key

        executor.execute {
            val cmd = values.joinToString("; ") { "setprop ${it.first} ${it.second}" }
            val ok = runCatching {
                val p = ProcessBuilder("su", "-c", cmd).start()
                val finished = p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroy()
                    HLog.w(TAG, "band geom su timeout: $cmd")
                    false
                } else {
                    val rc = p.exitValue()
                    if (rc != 0) HLog.w(TAG, "band geom su exit=$rc: $cmd")
                    rc == 0
                }
            }.onFailure { HLog.w(TAG, "band geom su failed: ${it.message}") }.getOrDefault(false)

            if (ok) HLog.i(TAG, "pushed band geom: $key")
            else HLog.w(TAG, "band geom 未写入（native 抑制档将 fail-closed）: $key")
        }
    }

    /** su 超时（与 HostRestarter 同量级；su 冷启动偶尔要等授权弹窗） */
    private const val SU_TIMEOUT_MS = 5_000L

    /**
     * 活体朝向（旋转后会变）。取**或**是因为两者各有盲点：`Resources.getSystem()` 的
     * configuration 由系统在旋转时刷新，而它的 displayMetrics 在部分版本仍按物理竖屏给；
     * 反过来某些场景 configuration 更新晚一拍而 dm 已经换了。
     * 误报的代价是 native 这一轮不判（不触发），方向是安全的；漏报才危险，故用或。
     */
    internal fun landscapeNow(): Boolean = runCatching {
        val sys = android.content.res.Resources.getSystem()
        sys.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            sys.displayMetrics.widthPixels > sys.displayMetrics.heightPixels
    }.getOrDefault(false)

    @Volatile private var rotationWatchArmed = false

    /**
     * 挂一次旋转监听（幂等）：横竖切换时重推一次触发带，让 [PROP_BAND_LANDSCAPE] 跟上。
     *
     * 为什么必须有人重推：屏尺寸那两个字段是**竖屏归一**的静态值，只有方向位会变；而
     * `pushBand` 本来只在"用户改设置"时跑一次 ⇒ 转屏后 native 会一直按旧朝向判定。
     * 只在**模块 App 进程**注册（只有它持 root 写得进属性，见类头注释）；该进程不在时
     * 属性会停在旧值，此时靠 native 侧的事件自证兜底（坐标越界即拒判）。
     */
    fun ensureRotationWatch(ctx: Context, prefs: SharedPreferences) {
        if (rotationWatchArmed) return
        rotationWatchArmed = true   // 先置位：注册失败也不反复重试（本进程内一次性）
        runCatching {
            val app = ctx.applicationContext ?: return
            app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                    val landscape =
                        newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    runCatching { pushBand(prefs, landscapeHint = landscape) }
                        .onFailure { HLog.w(TAG, "rotate band push failed: ${it.message}") }
                }

                // `onLowMemory()` 在 API 35 被标 deprecated，但它是本接口的必实现项（模块要覆盖老版本，
                // 走旧签名是有意为之）：抑制这条告警，别给编译留新噪声。
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onLowMemory() {}
            })
            HLog.i(TAG, "rotation watch armed（横竖切换重推触发带方向位）")
        }.onFailure { HLog.w(TAG, "rotation watch 未挂上: ${it.message}") }
    }
}

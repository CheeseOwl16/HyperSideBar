package com.lsp.hypersidebar.anchor

/**
 * 结构化锚点解析的规则引擎（**纯逻辑，无 Android 依赖 ⇒ 可在 JVM 上单测**）。
 *
 * 设计要点（详见 docs/adaptation/structured-anchor-resolution-plan.md）：
 * - 规则形态统一为 `包前缀 ∧ 自身声明的精确方法签名 ∧ super 约束`；
 * - **L0 候选名优先**：保证"现在能用的锚点不会被结构化结果换掉"；
 * - **L1 一律现场校验**：名字命中也要过指纹，所以 OS4 上"名字还在但语义变了"（`f` 变成 Drawable）会被拒；
 * - **闭锁**：任一 role 解析失败只是不装对应 hook，不影响其它 role，也绝不改变既有默认路径。
 */

/** super 约束 */
sealed interface SuperRule {
    /** 不做 super 约束（仅用于"声明了某些方法"即为目标的 role） */
    data object Any : SuperRule

    /** 直接父类等于 [fqcn] */
    data class Exactly(val fqcn: String) : SuperRule

    /** super 链上出现 [fqcn]（可跨中间层，如 OS4 的 `e → com.miui.dock.base.b → View`） */
    data class ChainReaches(val fqcn: String) : SuperRule
}

/**
 * L1 校验所需的最小事实。Android 侧由反射实现（`Class.forName(name, false, cl)` +
 * `getDeclaredMethods` + `getSuperclass`，不触发静态初始化）；JVM 单测直接合成。
 */
interface FactsSource {
    fun exists(fqcn: String): Boolean

    /** 该类**自身声明**的方法签名集合（与 [MethodSpec.signature] 同格式） */
    fun declaredSignatures(fqcn: String): Set<String>

    /** 父类点分名；`java.lang.Object` / 不可达时为 null */
    fun superName(fqcn: String): String?
}

/** 一个语义角色的解析规格 */
data class RoleSpec(
    val role: String,
    val pkg: String,
    /** 必须由该类**自身声明**的方法（多个=全部满足）；空 = 纯名字/纯结构定位 */
    val ownAll: List<MethodSpec> = emptyList(),
    val superRule: SuperRule = SuperRule.Any,
    /** L0 候选名（点分，按优先级）。可用则为快路径，失效则回落 L2 */
    val hints: List<String> = emptyList(),
)

enum class Layer { HINT, STRUCTURAL, NONE }

enum class ResolveState { RESOLVED, NOT_FOUND, AMBIGUOUS }

/** 解析结果（同时驱动 hook 安装与自检报告） */
data class RoleResolution(
    val role: String,
    val fqcn: String?,
    val layer: Layer,
    val hits: Int,
    val state: ResolveState,
    val note: String = "",
) {
    val isResolved: Boolean get() = state == ResolveState.RESOLVED && fqcn != null

    /** 自检表一行：`role  class  layer  hits  state` */
    fun oneLine(): String {
        val cls = fqcn ?: "-"
        val extra = if (note.isEmpty()) "" else "  $note"
        return "%-22s %-46s %-10s %-4d %s%s".format(role, cls, layer.name, hits, state.name, extra)
    }
}

object AnchorEngine {

    const val OBJECT = "java.lang.Object"

    /** super 链步数上限：防御性兜底（畸形 dex / 循环继承） */
    const val SUPER_CHAIN_CAP = 12

    fun chainReaches(src: FactsSource, fqcn: String, target: String): Boolean {
        var cur = src.superName(fqcn) ?: return false
        var steps = 0
        while (steps < SUPER_CHAIN_CAP) {
            if (cur == target) return true
            if (cur == OBJECT) return false
            cur = src.superName(cur) ?: return false
            steps++
        }
        return false
    }

    /** L1：现场指纹校验（名字命中同样要过这一关） */
    fun verify(src: FactsSource, spec: RoleSpec, fqcn: String): Boolean {
        if (!src.exists(fqcn)) return false
        if (spec.ownAll.isNotEmpty()) {
            val sigs = src.declaredSignatures(fqcn)
            if (spec.ownAll.any { it.signature !in sigs }) return false
        }
        return when (val rule = spec.superRule) {
            is SuperRule.Any -> true
            is SuperRule.Exactly -> src.superName(fqcn) == rule.fqcn
            is SuperRule.ChainReaches -> chainReaches(src, fqcn, rule.fqcn)
        }
    }

    /**
     * L0(hints) → L2(index) 编排；顺序刻意是"候选名优先"，
     * 因为 L0 命中意味着"与现状一致"，而 L2 只应在漂移时出手。
     */
    fun resolve(src: FactsSource, index: DexIndex?, spec: RoleSpec): RoleResolution {
        for (hint in spec.hints) {
            if (verify(src, spec, hint)) {
                return RoleResolution(spec.role, hint, Layer.HINT, 1, ResolveState.RESOLVED)
            }
        }
        if (index == null) {
            return RoleResolution(
                spec.role, null, Layer.NONE, spec.hints.size, ResolveState.NOT_FOUND,
                "L2 unavailable; ${spec.hints.size} hint(s) rejected"
            )
        }
        if (spec.ownAll.isEmpty()) {
            return RoleResolution(
                spec.role, null, Layer.NONE, spec.hints.size, ResolveState.NOT_FOUND,
                "no fingerprint to search structurally"
            )
        }
        val cands = index.findClasses(spec.pkg, spec.ownAll.first())
        val verified = cands.filter { verify(src, spec, it) }
        return when (verified.size) {
            1 -> RoleResolution(spec.role, verified[0], Layer.STRUCTURAL, cands.size, ResolveState.RESOLVED)
            0 -> RoleResolution(
                spec.role, null, Layer.STRUCTURAL, cands.size, ResolveState.NOT_FOUND,
                "structure: ${cands.size} candidate(s), none passed fingerprint"
            )
            else -> RoleResolution(
                spec.role, null, Layer.STRUCTURAL, verified.size, ResolveState.AMBIGUOUS,
                "structure: ambiguous -> ${verified.joinToString(",")}"
            )
        }
    }
}

/**
 * 角色表。指纹均经三版本离线核对（`tools/resolve_anchors.py` / `current_anchor_drift.py`），
 * 例如 `onTouch(View,MotionEvent):Z` 在同一包内恰好命中 2 个类，靠 super 约束区分：
 * OS2/OS3 = `f`(Object) + `b`(View 链)；OS4 = `k`(Object) + `e`(经 `com.miui.dock.base.b`)。
 */
object AnchorRoles {

    private const val SIDEBAR = "com.miui.dock.sidebar"
    private const val NEWBOX = "com.miui.gamebooster.windowmanager.newbox"

    /**
     * 小窗所在的 WM Shell **根包**。
     *
     * 为什么用根包而不是精确包：OS3/OS4 的 WM Shell 在 `…shell.multitasking.*`，而 OS2
     * （merlin / HyperOS 2.1.1，2026-10-16 设备 dexdump 实证）是 `…shell.miuifreeform` /
     * `…shell.miuimultiwinswitch.*` —— **少一段 `multitasking.`**，类名也是另一族
     * （`MiuiWindowDecorViewModel` vs `MulWinSwitchDecorViewModel`）。
     * 精确包会让下面三条 freeform role 在 OS2 上永远 NOT_FOUND（自检里看起来像缺陷）。
     * 根包 + `ownAll` 指纹 + L0 hint 仍然精确（hint 命中后还要过指纹才认）；结构扫若
     * 真的多命中，现有 AMBIGUOUS 路径照样 fail-closed。
     */
    private const val WM_SHELL = "com.android.wm.shell"

    /** 精确包（仅作 hint 用；两族命名都列上，OS2 走后者） */
    private const val FREEFORM_OS34 = "com.android.wm.shell.multitasking.miuifreeform"
    private const val FREEFORM_OS2 = "com.android.wm.shell.miuifreeform"
    private const val MULTIWIN_DECOR_OS34 =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.muiwindowdecor"
    private const val MULTIWIN_DECOR_OS2 = "com.android.wm.shell.miuimultiwinswitch.muiwindowdecor"

    private val ON_TOUCH = MethodSpec.of(
        "onTouch", listOf("android.view.View", "android.view.MotionEvent"), "boolean"
    )
    private val DRAW = MethodSpec.of("draw", listOf("android.graphics.Canvas"), "void")
    private val DISPATCH_TOUCH = MethodSpec.of(
        "dispatchTouchEvent", listOf("android.view.MotionEvent"), "boolean"
    )
    private val SET_VISIBILITY = MethodSpec.of("setVisibility", listOf("int"), "void")

    /** 小窗动作：缩小/最小化（三代同名同签名，见 docs/adaptation/freeform-blank-tap-scheme.md §6.3） */
    private val FROM_FREEFORM_TO_MINI = MethodSpec.of(
        "fromFreeformToMini", listOf("int"), "void"
    )

    /** OS3/OS4 的事件入口（`MulWinSwitchEventHandler`，jadx 实证 `public void onEvent(MotionEvent, InputMonitor)`） */
    private val ON_EVENT = MethodSpec.of(
        "onEvent", listOf("android.view.MotionEvent", "android.view.InputMonitor"), "void"
    )

    /** OS2 的事件入口（OS2 没有 Handlert/EventController，整个路由在 DecorViewModel；
     *  jadx 实证 `public void handleReceivedMotionEvent(MotionEvent, InputMonitor)`） */
    private val HANDLE_RECEIVED_MOTION = MethodSpec.of(
        "handleReceivedMotionEvent",
        listOf("android.view.MotionEvent", "android.view.InputMonitor"), "void"
    )

    /** 侧边栏触摸监听：监听器语义 ⇒ super 必是 Object（View 有 onTouch 会另算一类） */
    val SIDEBAR_TOUCH = RoleSpec(
        "sidebar_touch", SIDEBAR, listOf(ON_TOUCH),
        SuperRule.Exactly(AnchorEngine.OBJECT), listOf("$SIDEBAR.f")
    )

    /** 覆盖层：自身声明 onTouch 且是 View（OS4 中间隔了 `com.miui.dock.base.b`） */
    val SIDEBAR_COVER = RoleSpec(
        "sidebar_cover", SIDEBAR, listOf(ON_TOUCH),
        SuperRule.ChainReaches("android.view.View"), listOf("$SIDEBAR.b")
    )

    /** 小白条可见像素的唯一出口 */
    val SIDEBAR_DRAWABLE = RoleSpec(
        "sidebar_drawable", SIDEBAR, listOf(DRAW),
        SuperRule.Exactly("android.graphics.drawable.Drawable"), listOf("$SIDEBAR.c")
    )

    /**
     * 把手条。原名 `RegionSamplingImageView` 三版本都稳定，但**不靠名字**：
     * 实测 `dispatchTouchEvent(MotionEvent):Z` 在该包内**唯一**命中它（OS2/OS3/OS4 一致），
     * 且 super 链始终达 ImageView（OS4 变为经 `com.miui.dock.base.a`）。
     */
    val SIDEBAR_HANDLE_BAR = RoleSpec(
        "sidebar_handle_bar", SIDEBAR, listOf(DISPATCH_TOUCH),
        SuperRule.ChainReaches("android.widget.ImageView"),
        listOf("$SIDEBAR.RegionSamplingImageView")
    )

    /** 提示清理（M1/N1）：OS2/OS3 该类不存在、OS4 语义已变 ⇒ 预期 NOT_FOUND（=现状，死代码） */
    val SIDEBAR_HINT_CLEANUP = RoleSpec(
        "sidebar_hint_cleanup", SIDEBAR,
        listOf(MethodSpec.of("M1"), MethodSpec.of("N1")), SuperRule.Any, emptyList()
    )

    /**
     * 工具箱面板可见性。当前硬编码候选（`newbox.e`/`newbox.d`）实测在三个版本上**全部不满足**
     * 指纹（`e` 是 RecyclerView 子类、`d` 是 Runnable），所以这个 hook 至今从未装成功过；
     * 结构化目标唯一命中 `GameToolboxMainView`。**但 base 轮只解析不安装**（语义待复核，见计划 §5）。
     */
    val DOCK_LAYOUT = RoleSpec(
        "dock_layout", NEWBOX, listOf(SET_VISIBILITY),
        SuperRule.ChainReaches("android.view.ViewGroup"),
        listOf("$NEWBOX.e", "$NEWBOX.d")
    )

    /** 需要解析的全部 role（`qs_host_adapter` 走另一套契约校验，见 SystemUiHook） */
    val ALL: List<RoleSpec> = listOf(
        SIDEBAR_TOUCH, SIDEBAR_COVER, SIDEBAR_DRAWABLE, SIDEBAR_HANDLE_BAR,
        SIDEBAR_HINT_CLEANUP, DOCK_LAYOUT
    )

    private const val RECENTS = "com.miui.home.recents"

    /** 贴边手势占位视图：呼出/透传的实际入口 */
    val HOME_GESTURE_STUB = RoleSpec(
        "home_gesture_stub", RECENTS,
        listOf(MethodSpec.of("onTouchEvent", listOf("android.view.MotionEvent"), "boolean")),
        SuperRule.Any,
        listOf("$RECENTS.GestureStubView")
    )

    /** 三键导航的同类占位视图 */
    val HOME_NAV_STUB = RoleSpec(
        "home_nav_stub", RECENTS,
        listOf(MethodSpec.of("onTouchEvent", listOf("android.view.MotionEvent"), "boolean")),
        SuperRule.Any,
        listOf("$RECENTS.NavStubView")
    )

    /**
     * 快滑透传回调（`GestureStubView$3.onSwipeStop(Z,F,Z)`）。
     * 内部类不做 super 约束、也不给结构化搜索指纹（`ownAll` 为空 ⇒ 结构层自动跳过）——
     * 它只是"OS2/OS3 在、OS4 不在"的观测点，靠 hint 命中即可。
     */
    val HOME_SWIPE_STOP = RoleSpec(
        "home_swipe_stop", RECENTS,
        listOf(MethodSpec.of("onSwipeStop", listOf("boolean", "float", "boolean"), "void")),
        SuperRule.Any,
        listOf("$RECENTS.GestureStubView\$3")
    )

    // ===== 每宿主 role 表（OS2 分支 P1） =====
    //
    // 背景：侧边栏/工具箱六个 role 全是 **securitycenter 专属**。base 轮对每个宿主都跑同一张
    // 全表，实测在 `com.miui.home` / `com.android.systemui` 上稳定产出 6 行 NOT_FOUND 噪声，
    // 且白白加载 DexKit（.so 映射 + 5 次查询）。这里按宿主包名分表：
    //   - 无关宿主直接返回空表 ⇒ 不解析、不建索引（`AnchorResolver` 见空表即跳过 L2）；
    //   - 自检报告只剩有意义的行，漂移信号不被噪声淹没。
    //
    // 纪律：**表内 role 一经加入不得随意移除** —— 它们是漂移观测点（例如 `dock_layout`
    // 已删除对应 hook，但仍要解析出 `GameToolboxMainView` 以便在自检里看见）。

    /** 安全中心（侧边栏 + 工具箱 panel 的实际宿主） */
    val SECURITY_CENTER: List<RoleSpec> = ALL

    /**
     * 桌面。OS2/OS3 是 Java（`com.miui.home`），OS4 的 launcher 是 `hyos_spawner` 孵化的
     * Rust 进程（**无 dex**）⇒ 本表在 OS4 上预期全 NOT_FOUND，属正确结果而非故障。
     *
     * 手势三个锚点当前由 `EdgeGestureHook` 直接硬编码（见该类），本轮**只建表不接线**：
     * 目的是把 "OS2/OS3 在 → OS4 没了" 这个漂移事实暴露到自检报告里，为 OS4 的 native
     * 路线提供守门数据。
     */
    val HOME: List<RoleSpec> = listOf(HOME_GESTURE_STUB, HOME_NAV_STUB, HOME_SWIPE_STOP)

    /**
     * SystemUI。磁贴链路的类名/契约校验由 `SystemUiHook` 的候选表负责（P0-1）。
     *
     * 这里放**小窗「点击空白处」**的三条 role（2026-10-16 加，设计见
     * `docs/adaptation/freeform-blank-tap-scheme.md` §6.1/§6.3/§6.4）：
     * - `freeform_controller`（三代）：动作入口，缩小/最小化。**功能硬依赖**——
     *   NOT_FOUND ⇒ 功能静默关闭（fail-closed，写 anchor 表日志），不抛异常；
     * - `freeform_event_handler`（OS3/OS4）/ `freeform_input_router`（OS2）：只作
     *   **ROM 形态自证**——把"OS2 在哪个 / OS3·4 在哪个"的漂移暴露到自检报告。
     *   检测本身走 `SystemUiInputSpy` 的全局 monitor 通道（它已无条件启动），
     *   所以这两条 NOT_FOUND **不关功能**。
     *
     * 为什么用 hint 而不是只靠结构扫：`MiuiFreeformModeController` 里存在**内部类**也声明
     * 同名 `fromFreeformToMini(int):void`，纯结构扫会命中 ≥2 个类 ⇒ AMBIGUOUS；
     * L0 的 hint 是"候选名 + 必须过 ownAll 指纹"（见 `resolve()`），既精确又保留漂移检测。
     */
    val SYSTEM_UI: List<RoleSpec> = listOf(
        RoleSpec(
            "freeform_controller", WM_SHELL, listOf(FROM_FREEFORM_TO_MINI), SuperRule.Any,
            listOf("$FREEFORM_OS34.MiuiFreeformModeController", "$FREEFORM_OS2.MiuiFreeformModeController")
        ),
        RoleSpec(
            "freeform_event_handler", WM_SHELL, listOf(ON_EVENT), SuperRule.Any,
            listOf(
                "$MULTIWIN_DECOR_OS34.MulWinSwitchEventHandler",
                "$MULTIWIN_DECOR_OS2.MulWinSwitchEventHandler"
            )
        ),
        RoleSpec(
            "freeform_input_router", WM_SHELL, listOf(HANDLE_RECEIVED_MOTION), SuperRule.Any,
            listOf(
                "$MULTIWIN_DECOR_OS34.MulWinSwitchDecorViewModel",
                "$MULTIWIN_DECOR_OS2.MiuiWindowDecorViewModel"
            )
        ),
    )

    /** 按宿主包名选表；未知宿主 = 空表（不解析、不加载 DexKit） */
    fun forHost(pkg: String?): List<RoleSpec> = when (pkg) {
        "com.miui.securitycenter" -> SECURITY_CENTER
        "com.miui.home" -> HOME
        "com.android.systemui" -> SYSTEM_UI
        else -> emptyList()
    }
}
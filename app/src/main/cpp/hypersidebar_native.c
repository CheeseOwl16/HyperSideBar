/*
 * LSPosed native entry 探针（OS4 适配前置验证，adapt/hyperos4）。
 *
 * 目的（在 OS3/OS2 上就能验，见 docs/adaptation/marble-os4-decompile-verification.md §6 与
 * F:\hyperos\work\docs\lsposed-native-entry-verification.md §4.2）：
 *   U1：本机的 LSPosed 构建是否真的解析 assets/native_init 并 dlopen 本 so
 *       调用 native_init —— 这决定 OS4 桌面 native hook 路线是否成立；
 *   优雅跳过：OS2/OS3 的 launcher 没有 libapp_launcher.so，本探针必须安静记录后不做任何事。
 *
 * ABI 按官方 wiki《Native Hook》：entries 内容不可改写；返回的回调在之后每次 dlopen 时
 * 被框架以 (name, handle) 调用。hookFunc/unhookFunc 任一为空即视为 API 不完整。
 *
 * 取证双通道（2026-10-02）：logcat tag=HSBNative（logcat 可用时用）+
 * 全局报告 g_report（Java 经 NativeProbe.report() 取，写进 HLog → 自检导出——
 * logcat 整体失灵时的兜底，OS4.0.0.14 实机首发日即命中）。报告在 native_init
 * 回调内填充完毕（该回调在 dlopen 内、JNI_OnLoad 之前），Java 侧 loadLibrary
 * 返回后即可读到完整快照。
 */
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>   /* dl_iterate_phdr / struct dl_phdr_info */
#include <elf.h>    /* ElfW(Phdr) / PT_LOAD / PF_X */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>
#include <time.h>   /* clock_gettime(CLOCK_MONOTONIC) —— 心跳的"安装后秒数" */
#include <sys/system_properties.h>
#include "launcher_profiles.h"

/*
 * 编译期总闸（2026-10-08，替代做不了的"OS3 装机回归"，见测试清单 §7）：
 *   默认 0 ⇒ `hsb_prop_enabled()` 返回常量 0，扫描/装 hook/回调补拍/Java retry 四条路全部
 *   不可达，release 主线包里这段代码不是"运行时不执行"而是"二进制里没有"（实测产物中
 *   `debug.hsb.native.hook` 与锚点名字符串都不存在）；
 *   spike 分支由 CMakeLists 里 `set(HSB_NATIVE_SPIKE ON)` + `add_compile_definitions` 打开。
 * 合回主线时**不用改这个 C 文件**（默认就是关），只要别把 CMakeLists 那两行带过去。
 */
#ifndef HSB_NATIVE_SPIKE
#define HSB_NATIVE_SPIKE 0
#endif

#define LOG_TAG "HSBNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef int  (*HookFunType)(void *func, void *replace, void **backup);
typedef int  (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

/* 感兴趣的宿主库：OS4 桌面的两个 Rust/Flutter so。OS2/OS3 上理应都 absent。
 * kTargetLib = native hook 的目标镜像（OS4 桌面 Rust 侧）。 */
#define kTargetLib "libapp_launcher.so"
static const char *const kReplayLibs[] = {
    kTargetLib,
    "libapp.so",
    NULL,
};

#define HSB_LOG_LIMIT 12u

/* 报告结构：写入集中在加载路径（单线程），读在 JNI，无竞争 */
static struct {
    uint32_t ctor_ran;        /* constructor 探针已执行（=框架真 dlopen 了我们） */
    uint32_t init_fired;      /* native_init 被框架调用（=U1 成立） */
    uint32_t api_version;
    uint32_t has_hook_func;
    uint32_t has_unhook_func;
    uint32_t api_incomplete;  /* 因 API 不完整返回 NULL，未回 dlopen 回调 */
    uint32_t callback_calls;  /* 框架调 on_module_loaded 的次数（回调链活性） */
    uint32_t replay_mask;     /* 回放命中的库位图（仅取证，不再当门控用，见 §门控复盘） */
    uint32_t launcher_cb_hits;/* dlopen 回调里名字命中目标库的次数 */
    char     process[128];
} g_report;

/* LSPosed 交付的 native hook API（探针阶段只暂存，装 hook 时才用） */
static const NativeAPIEntries *g_api;

static void note_process(void) {
    FILE *f = fopen("/proc/self/cmdline", "r");
    char buf[160] = {0};
    if (f) {
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        (void)n;
        fclose(f);
    }
    memcpy(g_report.process, buf, sizeof(g_report.process) - 1);
    g_report.process[sizeof(g_report.process) - 1] = '\0';
}

static void hsb_try_install_hooks(void);
static int hsb_prop_enabled(void);

static int hsb_leaf_is_target(const char *name) {
    if (!name) return 0;
    const char *leaf = strrchr(name, '/');
    leaf = leaf ? leaf + 1 : name;
    /* 精确末段匹配：APK 内嵌时 dlpi/maps 名字形如 "...base.apk!/lib/arm64-v8a/libapp_launcher.so"，
     * 末段仍是裸 soname；用 strcmp 而非前缀，免得 "libapp_launcher_x.so" 误判成目标 */
    return strcmp(leaf, kTargetLib) == 0;
}

static uint32_t g_cb_logged;

static void on_module_loaded(const char *name, void *handle) {
    g_report.callback_calls++;
    (void)handle;
    /* 总闸/prop 关=与冻结线完全一致：只计数，不打日志、不比对名字、不装 hook */
    if (!hsb_prop_enabled()) return;
    /* 回调名取证（限量）：框架是否把桌面的原生库加载也报给我们 = 之后每拍重试的钩子 */
    if (g_cb_logged++ < HSB_LOG_LIMIT) LOGI("dlopen cb: %s", name ? name : "(null)");
    if (hsb_leaf_is_target(name)) {
        g_report.launcher_cb_hits++;
        hsb_try_install_hooks();
    }
}

/*
 * 构造期探针（2026-09-30 复盘新增，os4-native-entry-postmortem §2.1）：
 * 没有它就无法区分"框架 dlopen 了我们但没调 native_init"与"根本没加载 so"——
 * 前者问题在清单/ABI 约定，后者是 native entry 未被处理。ctor 在 dlopen 收尾执行、
 * 早于框架的 native_init 回调，用 constructor 而非 JNI_OnLoad（框架走 dlopen+dlsym，不经 JVM）。
 */
__attribute__((constructor))
static void probe_ctor(void) {
    g_report.ctor_ran = 1;
    note_process();
    LOGI("probe so LOADED (ctor) — framework did dlopen us, native_init should follow");
}

__attribute__((destructor))
static void probe_dtor(void) {
    LOGI("probe so UNLOADING (dtor)");
}

/*
 * ===== native hook 骨架（spike/os4-native 第 1 步：能力验证，默认惰性）=====
 *
 * 开法：`adb shell su -c 'setprop debug.hsb.native.hook 1'` 后重启桌面。关=完全不做任何事
 * （连 maps/phdr 都不扫），所以冻结线/发版包带这段代码也无风险。
 *
 * 本步只证明四件事：地址解析对、inline hook 装得上且不掉、日志能出、桌面不崩。
 * 故只挂 profile 里最小的那一个锚点（try_delegate_back_to_sidebar，188B、非热路径），
 * 并刻意**先不做 madvise 守卫**——那正是本步要验的风险（参考工程认为缺它会被 HYOS 的
 * madvise 把 hook 页打回文件映射而"神秘失效"）。判据含"30 分钟后 hits 仍在增长"；
 * 若停滞则照抄参考实现补守卫。
 *
 * 门控复盘（2026-10-08 实机首轮）：原来把安装条件写成 `replay_mask & 1`，日志给出
 * `replay: libapp_launcher.so absent` + `runs=0` ⇒ 安装代码从未跑过。裸 soname 的
 * `dlopen(RTLD_NOLOAD)` 在**linker namespace 隔离**（模块 so 与桌面原生库不在同一
 * namespace）和**加载时机**（我们的 so 在桌面 System.loadLibrary 之前就被 dlopen）下
 * 都会落空，参考工程自己也从不信它（用 lsplt 读 maps）。故 replay 降级为纯取证，
 * 地址改由两条独立来源交叉给出（phdr + maps），装不装由 prologue 校验决定。
 *
 * fail-closed 三道（升级为）：候选地址的目标落在该库 maps 记录的可执行段内（防 APK 内嵌
 * 时 start-pgoff 推出的偏移错基导致越界读）/ 目标处 32B prologue 与 profile 逐字节相等 /
 * hook_func 返回 0 且 backup 非空。任一不满足 ⇒ 换下一个候选或拒绝安装并记原因（桌面 OTA
 * remap 后表现为"不 hook"而不是崩桌面）。
 */
#define HSB_ONLY_ANCHOR "try_delegate_back_to_sidebar"
#define HSB_MAX_SEGS 16u
#define HSB_MAX_CANDS 8u
#define HSB_MAX_ATTEMPTS 12u
#define HSB_HEARTBEAT_S 60ull
#define HSB_PROLOGUE_BYTES HSB_PROFILE_PROLOGUE_BYTES
#define HSB_ANCHOR_PROP "debug.hsb.native.anchor"

/*
 * 可换的挂点白名单（2026-10-08 加，2026-10-09 第 2 步扩容）：装机轮稀缺，
 * "installed=1 但 hits 恒 0"这种**选点**问题不该再烧一次 APK，
 * `setprop debug.hsb.native.anchor <名字>` + 重启桌面就能换。
 *
 * 2026-10-09 更正（离线反汇编实锤，旧注释是误读）：原来以"序言存 d8-d15 ⇒ 可能有浮点参数"
 * 把 on_swipe_start/on_swipe_process/handle_back_gesture 全排除。实际逐个看过序言：
 *  - on_swipe_start：只存 **d8 一个**（自己用的 callee-saved），**不吃 FP 参数** ⇒ 可挂；
 *  - on_swipe_process / handle_back_gesture：同样只存 x/d8 级，且 args 是 x0-x2 ⇒ 可挂；
 *  - init_gesture_type / match_region_check / is_in_bottom_gesture_region：
 *    **真的以 s0/s1 传坐标**（init_gesture_type 里 `fmov s8,s0`+`stp s0,s1,[x29,#-8]`）
 *    ⇒ 必须靠桩的 d0-d7 透传，见 HsbFn16。
 */
static const char *const kHsbAnchorWhitelist[] = {
    /* --- 第 1 步已验证（返回委托分叉）--- */
    "try_delegate_back_to_sidebar",   /* 188B，back 委托侧边栏的分叉 */
    "on_back_cancelled",              /* 456B，判定失败/取消 */
    "on_back_invoke",                 /* 1476B，commit 时刻 */
    /* --- 第 2 步：门控与底角的观测点 --- */
    "on_swipe_start",                 /* 1504B，系统认领边缘滑=门控主信号，无 FP 参数 */
    "init_gesture_type",              /* 1012B，DOWN 时 (x,y)∈s0/s1 + 定手势类型 */
    "is_in_bottom_gesture_region",    /* 168B，底部手势区判定（底角落点） */
    "match_region_check",             /* 344B，纯分类：4 矩形 → 类型码 0/1/2 */
    NULL,
};

static const char *hsb_chosen_anchor(void) {
    char v[64] = {0};
    if (__system_property_get(HSB_ANCHOR_PROP, v) <= 0 || !v[0]) return HSB_ONLY_ANCHOR;
    for (int i = 0; kHsbAnchorWhitelist[i]; i++) {
        if (strcmp(v, kHsbAnchorWhitelist[i]) == 0) return kHsbAnchorWhitelist[i];
    }
    return HSB_ONLY_ANCHOR;   /* 名单外的值=忽略，仍用默认点（不猜） */
}

typedef uintptr_t (*HsbFn16)(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                             uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                             double d0, double d1, double d2, double d3,
                             double d4, double d5, double d6, double d7);
/*
 * 为什么桩要显式收 d0-d7（2026-10-09 第 2 步）：AAPCS64 的实参/形参分配是**两套独立队列**——
 * 前 8 个整型进 x0-x7，前 8 个浮点/SIMD 进 v0-v7（s0-s7/d0-d7）。Rust 侧
 * `init_gesture_type` / `match_region_check` / `is_in_bottom_gesture_region` 就是用
 * s0/s1 传 DOWN 坐标的。若桩只声明 8 个整型参数，编译器可自由拿 v0-v7 当临时寄存器，
 * 等我们把参数转给 backup 时坐标已经变成垃圾——对纯分类函数来说这等于把系统手势判废。
 *
 * 声明成"8 整型 + 8 double"后，编译器必须把这 16 个值分别放回 x0-x7 / v0-v7，
 * 于是**原样进、原样出**：对不吃 FP 参数的锚点，v0-v7 里是上一个函数留下的垃圾，
 * 我们不看也不碰，转给 backup 时又原样还回去，寄存器状态与入口逐位一致。
 * 用 double 而非 float/uint64：double 才会落进 d0-d7；位级上，调用方若只写了 s0-s7
 * 的低 32 位，转发时高 32 位是无意义填充，被调方只读低 32 位 ⇒ 两种情况都安全。
 */

static struct {
    uint32_t attempts;        /* 扫描+安装尝试次数（多来源每拍一次） */
    uint32_t matches_phdr;    /* dl_iterate_phdr 命中目标镜像的条数 */
    uint32_t matches_maps;    /* maps 里目标库可执行段条数 */
    uint32_t prologue_checked;
    uint32_t prologue_mismatch;
    uint32_t sig_scans;           /* 走特征扫描兜底的次数（偏移路全崩才走） */
    uint32_t sig_hits;            /* 最近一次特征扫描的命中数（≠1 一律不用） */
    uint32_t hook_installed;
    uint32_t hook_failed;
    uint32_t hits;
    uint64_t installed_at_s;   /* 安装时刻（CLOCK_MONOTONIC 秒）——心跳的"存活时长"基准 */
    uint64_t last_log_s;       /* 最近一次出声时刻（心跳去抖） */
    uintptr_t target;          /* 被改写的地址（导出侧 root 读内存复核用，也进日志） */
    uintptr_t bias;           /* 最终采纳的 load bias（sigscan 命中时为 0） */
    char reason[160];
    char anchor[40];          /* 实际挂上的锚点名（桩日志与报告都用它） */
    char how[16];             /* offset | sigscan */
    char lib_path[192];       /* maps 里目标库首条映射路径（namespace 取证） */
    int has_xy;               /* 该锚点以 s0/s1 传坐标 ⇒ 日志打 x/y（见 HsbFn16） */
    int has_ret;              /* 返回值带门控信息 ⇒ 日志打 ret=（见 hsb_anchor_has_ret） */
} g_hook;

static HsbFn16 g_orig;
static volatile uint32_t g_stub_logged;

/* 该锚点是否以 s0/s1 传坐标（决定日志要不要打 x/y；见 HsbFn16 的注释） */
static int hsb_anchor_has_xy(const char *name) {
    static const char *const kXy[] = {
        "init_gesture_type", "is_in_bottom_gesture_region", "match_region_check", NULL,
    };
    for (int i = 0; kXy[i]; i++) if (strcmp(name, kXy[i]) == 0) return 1;
    return 0;
}

/*
 * AArch64 读 FP 参数的正确姿势（2026-10-09 实踩）：**写 S 寄存器会把 V 寄存器的高 32 位
 * 清零**。Rust 侧传的是 float（`match_region_check` 里是 `fcvtzs w8, s0`，double 会写 d0），
 * 于是调用方留下的是 `v0 = [0x00000000][float_bits]`。桩按 double 收参（转发要位级不变），
 * 直接 `(float) d0` 会把整个 64 位当成一个次正规 double（指数域全 0）⇒ 转出来恒为 0.0——
 * 首轮装机就是这么错的：装对了、五个坐标全是 0。
 *  ⇒ 取**低 32 位**按 float 重新解释；double 调用方高位非 0，此时取低 32 位会得 0，
 *    靠同时打原始 hex 分辨（见 hsb_stub 的日志）。
 * 转发侧不用改：把 double 原样传回 backup，被调方只读低 32 位就对——所以这个 bug
 * 从头到尾只影响我们的日志，没有影响过系统行为。
 */
static float hsb_lo32_float(double d) {
    uint32_t bits;
    memcpy(&bits, &d, sizeof(bits));
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
}

/* 原始位型，只为日志里能一眼分清"float 传参"还是"double 传参" */
static uint64_t hsb_bits64(double d) {
    uint64_t b;
    memcpy(&b, &d, sizeof(b));
    return b;
}

/*
 * 返回值是否带门控信息（2026-10-09 定，只给这三个打 ret=）：
 *  - is_in_bottom_gesture_region：168B，`cmp w0,#1; cset w0,eq` ⇒ **返回布尔"在底部手势区"**。
 *    它就是 match_region_check 的一层包装，且只吃 s0/s1、不暴露内部 region 结构，
 *    是底角门控的首选锚点（比直接挂 match_region_check 稳：不用解析 Rust 私有字段布局）。
 *  - match_region_check：344B 纯分类，返回区域类型码。反汇编出的完整映射：
 *        @0x30 矩形              → w0=2
 *        @0x10 矩形              → w0=0（w1=0）
 *        @0x20 矩形              → w0=0（w1=1）
 *        @0x04 矩形+#0x50 开关    → w0=1   ← **底部手势区**（is_in_bottom_* 比的就是它）
 *        @0x40/#0x48/#0x4c 复合条件满足 → w0=1（同上一条的另一分支）
 *        都不中                   → w0=4   ← 对应日志串 "Down event ... outside valid region, redirecting"
 *    我们只收 x0（w0=主判别值）；w1 在 x1、C 收不到，底角门控用 is_in_bottom_* 代替。
 *  - try_delegate_back_to_sidebar：返回 back 是否委托给侧边栏，第 1 步已验有意义。
 * 其余不打：init_gesture_type 的返回值被调用方**整段丢弃**（判定写进 self 字段），
 * on_swipe_start/on_swipe_process 等属过程信号，返回值不是门控判据。
 */
static int hsb_anchor_has_ret(const char *name) {
    static const char *const kRet[] = {
        "is_in_bottom_gesture_region", "match_region_check",
        "try_delegate_back_to_sidebar", NULL,
    };
    for (int i = 0; kRet[i]; i++) if (strcmp(name, kRet[i]) == 0) return 1;
    return 0;
}

/* 心跳用的单调时钟与上次出声时刻（秒） */
static uint64_t hsb_now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec;
}

static uintptr_t hsb_stub(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                          uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                          double d0, double d1, double d2, double d3,
                          double d4, double d5, double d6, double d7) {
    (void) a2; (void) a3; (void) a4; (void) a5; (void) a6; (void) a7;
    (void) d0; (void) d1; (void) d2; (void) d3;
    (void) d4; (void) d5; (void) d6; (void) d7;
    uint32_t n = __atomic_add_fetch(&g_hook.hits, 1u, __ATOMIC_RELAXED);
    /* soak 判据"30 分钟后还活着、还在响"**按时间**成立，不按次数：
     * 次数阈值（原来的 16/32/64 对数里程碑）把两件事混成一件——"hook 没被打回文件映射"
     * 和"用户正好做了 16 次手势"。低频使用下 30 分钟可能只有 3 次命中，阈值永远等不到，
     * 于是"没日志"会被误读成"hook 掉了"。现在：任何一次命中，只要距上次出声 ≥60s，
     * 就打一行带"安装后秒数"的心跳——30 分钟后第一次手势即证据。
     * g_hook.installed_at_s 是"安装→此刻"的固定基准；空闲时长由 logcat 时间戳给出。 */
    const char *anchor = g_hook.anchor[0] ? g_hook.anchor : "?";
    uint64_t now = hsb_now_s();
    uint64_t last = g_hook.last_log_s ? g_hook.last_log_s : g_hook.installed_at_s;
    int quiet = (now >= last + HSB_HEARTBEAT_S);
    int first = __atomic_fetch_add(&g_stub_logged, 1u, __ATOMIC_RELAXED) < HSB_LOG_LIMIT;
    /* 先接住返回值再打日志（原来是尾部调用，返回值直接透传、打不到）。
     * 只给 has_ret 的点打——见 hsb_anchor_has_ret：其余锚点返回值无门控意义
     * （init_gesture_type 甚至被调用方整段丢弃，判定写进对象字段）。 */
    uintptr_t rv = g_orig
                       ? g_orig(a0, a1, a2, a3, a4, a5, a6, a7,
                                d0, d1, d2, d3, d4, d5, d6, d7)
                       : 0u;
    if (first || quiet) {
        char detail[192];
        if (g_hook.has_xy) {
            snprintf(detail, sizeof(detail), "x=%.1f y=%.1f",
                     hsb_lo32_float(d0), hsb_lo32_float(d1));
        } else {
            snprintf(detail, sizeof(detail), "a0=%p a1=%p", (void *) a0, (void *) a1);
        }
        if (g_hook.has_ret) {
            char t[40];
            snprintf(t, sizeof(t), " ret=0x%lx", (unsigned long) rv);
            strncat(detail, t, sizeof(detail) - strlen(detail) - 1);
        }
        LOGI("%s %s #%u %s d0=%016llx d1=%016llx since_install=%llus%s",
             first ? "hook HIT" : "hook ALIVE", anchor, n, detail,
             (unsigned long long) hsb_bits64(d0),
             (unsigned long long) hsb_bits64(d1),
             (unsigned long long) (now - g_hook.installed_at_s),
             quiet ? " (quiet)" : "");
        g_hook.last_log_s = now;
    }
    return rv;
}

/* --- 候选 load bias + 可执行段边界（两条来源共用，先 phdr 后 maps 追加） --- */
typedef struct {
    uintptr_t start;
    uintptr_t end;
} HsbRange;

static HsbRange g_xsegs[HSB_MAX_SEGS];
static unsigned g_xseg_count;

static uintptr_t g_cands[HSB_MAX_CANDS];
static unsigned g_cand_count;

static void hsb_add_cand(uintptr_t b) {
    if (!b) return;
    for (unsigned i = 0; i < g_cand_count; i++) if (g_cands[i] == b) return;
    if (g_cand_count < HSB_MAX_CANDS) g_cands[g_cand_count++] = b;
}

/* --- 来源 A：linker 自己的视图（跨 namespace，APK 内嵌也给出真 bias） --- */
typedef struct {
    uint32_t matches;
} HsbImageRequest;

static void hsb_add_xseg(uintptr_t start, uintptr_t end) {
    if (!start || end <= start || g_xseg_count >= HSB_MAX_SEGS) return;
    g_xsegs[g_xseg_count].start = start;
    g_xsegs[g_xseg_count].end = end;
    g_xseg_count++;
}

static int hsb_find_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    HsbImageRequest *req = (HsbImageRequest *) data;
    const char *name = info->dlpi_name ? info->dlpi_name : "";
    if (!hsb_leaf_is_target(name)) return 0;
    req->matches++;
    hsb_add_cand((uintptr_t) info->dlpi_addr);
    /* 可执行段边界也从 program header 拿一份：maps 万一读不到，仍有界可校验 */
    for (uint16_t i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
        hsb_add_xseg((uintptr_t) info->dlpi_addr + ph->p_vaddr,
                     (uintptr_t) info->dlpi_addr + ph->p_vaddr + ph->p_memsz);
    }
    return 0;
}

/*
 * --- 来源 B：/proc/self/maps（参考工程的 lsplt 路线） ---
 *
 * 实机（1008）读数：这条腿在桌面进程**匹配不到**目标（`lib=` 为空、安装靠 phdr 成功），
 * 原因是结构性的而非权限问题：`extractNativeLibs=false` 时内核映射的是**整个 APK 文件**，
 * maps 里的路径是 `/product/priv-app/MiuiHome/MiuiHome.apk`，`…!/lib/…` 只是 linker 的
 * 内部名（= `dlpi_name`）。所以按末段 soname 匹配 maps 天然落空。
 * 这是**好事**：APK 里还有别的库/dex 也在映射同一个文件，maps 分不清归属，若把它们的可执行段
 * 也当作目标范围，特征扫就可能在整个 APK 的映射里撞到别的库的字节 → 假阳性装错地址。
 * phdr 的 PT_LOAD(R+X) 恰好是目标库自己的紧边界，故本 spike 以 phdr 为准，maps 只在
 * "库被解到磁盘"的形态下补一拍。
 */
static uint32_t g_maps_logged;

static unsigned hsb_scan_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    uintptr_t lowest = (uintptr_t) -1;
    while (fgets(line, sizeof(line), f)) {
        /* 形如 78abcd0000-78abcd4000 r-xp 00040000 07:32 123456 /path/lib.app.so */
        unsigned long start = 0, end = 0, pgoff = 0;
        char perms[5] = {0};
        if (sscanf(line, "%lx-%lx %4s %lx %*x:%*x %*u", &start, &end, perms, &pgoff) != 4) {
            continue;
        }
        /* 路径：跳过前 6 个字段后的剩余文本 */
        char *p = line;
        for (int i = 0; i < 5 && p; i++) p = strchr(p, ' ');
        if (!p) continue;
        while (*p == ' ') p++;
        size_t plen = strlen(p);
        while (plen && (p[plen - 1] == '\n' || p[plen - 1] == '\r')) p[--plen] = '\0';
        if (!hsb_leaf_is_target(p)) continue;
        if (g_hook.lib_path[0] == '\0') {
            snprintf(g_hook.lib_path, sizeof(g_hook.lib_path), "%s", p);
        }
        if (g_maps_logged++ < 4u) LOGI("maps: %s", p);
        if (start < lowest) lowest = start;
        if (perms[2] == 'x') {
            if (g_xseg_count < HSB_MAX_SEGS) {
                g_xsegs[g_xseg_count].start = (uintptr_t) start;
                g_xsegs[g_xseg_count].end = (uintptr_t) end;
                g_xseg_count++;
            }
            /* 常规 ELF：可执行段 start - pgoff = load bias（vaddr ≡ off mod 页）；
             * APK 内嵌时这条会偏一个库在包内的偏移，故只作为候选，由段内校验+prologue 裁决 */
            hsb_add_cand((uintptr_t) (start - pgoff));
        }
    }
    fclose(f);
    if (lowest != (uintptr_t) -1) hsb_add_cand(lowest);  /* 首段（含 ELF 头）起点≈bias */
    return g_xseg_count;
}

/* 目标地址（含 prologue 读取长度）必须落在已记录的可执行段内——越界即拒，防误基引发崩桌面 */
static int hsb_in_exec(uintptr_t addr, size_t len) {
    for (unsigned i = 0; i < g_xseg_count; i++) {
        if (addr >= g_xsegs[i].start && addr + len <= g_xsegs[i].end) return 1;
    }
    return 0;
}

/*
 * phdr 与 maps 会各给一份**同一段内存**的可执行区间（如 .12 里那条 RX PT_LOAD）。
 * 不合并 ⇒ 特征扫会把同一命中数两遍（hits=2）而被"歧义即拒"挡死。故扫之前先区间归并。
 */
static void hsb_merge_xsegs(void) {
    for (unsigned i = 1; i < g_xseg_count; i++) {           /* 插入排序（≤16 条） */
        HsbRange k = g_xsegs[i];
        unsigned j = i;
        while (j > 0 && g_xsegs[j - 1].start > k.start) {
            g_xsegs[j] = g_xsegs[j - 1];
            j--;
        }
        g_xsegs[j] = k;
    }
    unsigned n = 0;
    for (unsigned i = 0; i < g_xseg_count; i++) {
        if (n && g_xsegs[i].start <= g_xsegs[n - 1].end) {  /* 重叠/相接：并入上一条 */
            if (g_xsegs[i].end > g_xsegs[n - 1].end) g_xsegs[n - 1].end = g_xsegs[i].end;
            continue;
        }
        g_xsegs[n++] = g_xsegs[i];
    }
    g_xseg_count = n;
}

/*
 * 兜底定位：按 profile 的 32B prologue 特征在该库可执行段里扫。
 * 为什么要它：设备构建（4.0.0.14）与手头镜像（marble .12 / dada .16）的 .text 会整体
 * 漂移（.12 vs .16 已见 0x390 位移），固定 st_value 在异构建上必然对不上；而同一 Rust
 * crate 的同版本族里**函数序言字节通常不变**，扫特征就能把地址自愈回来。
 * 安全：只在 prop 开时扫；步长 4（AArch64 入口必 4 对齐）；命中数≠1 一律不用（歧义=拒）。
 */
static uintptr_t hsb_sigscan(const uint8_t *pat, uint32_t *hits_out) {
    uintptr_t found = 0;
    uint32_t hits = 0;
    for (unsigned i = 0; i < g_xseg_count; i++) {
        const uint8_t *base = (const uint8_t *) g_xsegs[i].start;
        size_t span = (size_t) (g_xsegs[i].end - g_xsegs[i].start);
        if (span < HSB_PROLOGUE_BYTES) continue;
        size_t n = span - HSB_PROLOGUE_BYTES + 1u;
        for (size_t k = 0; k < n; k += 4u) {
            if (base[k] != pat[0]) continue;
            unsigned b = 1u;
            for (; b < HSB_PROLOGUE_BYTES; b++) if (base[k + b] != pat[b]) break;
            if (b == HSB_PROLOGUE_BYTES) {
                hits++;
                found = (uintptr_t) (base + k);
                if (hits > 1u) { *hits_out = hits; return 0u; }  /* 歧义：早停，别猜 */
            }
        }
    }
    *hits_out = hits;
    return hits == 1u ? found : 0u;
}

static void hsb_install_at(const HsbLauncherAnchor *a, uintptr_t at, uintptr_t base,
                           const char *how) {
    void *backup = NULL;
    int rc = g_api->hook_func((void *) at, (void *) hsb_stub, &backup);
    if (rc == 0 && backup != NULL) {
        g_orig = (HsbFn16) (uintptr_t) backup;
        g_hook.hook_installed++;
        g_hook.bias = base;
        g_hook.target = at;
        g_hook.installed_at_s = hsb_now_s();
        g_hook.last_log_s = g_hook.installed_at_s;
        snprintf(g_hook.anchor, sizeof(g_hook.anchor), "%s", a->name);
        snprintf(g_hook.how, sizeof(g_hook.how), "%s", how);
        g_hook.has_xy = hsb_anchor_has_xy(a->name);
        g_hook.has_ret = hsb_anchor_has_ret(a->name);
        LOGI("hook installed %s target=%p backup=%p size=%llu via=%s bias=%p lib=%s xy=%d ret=%d",
             a->name, (void *) at, backup, (unsigned long long) a->size, how,
             (void *) base, g_hook.lib_path, g_hook.has_xy, g_hook.has_ret);
    } else {
        g_hook.hook_failed++;
        snprintf(g_hook.reason, sizeof(g_hook.reason),
                 "hook_func rc=%d backup=%p at=%p via=%s", rc, backup, (void *) at, how);
        LOGI("hook failed: %s", g_hook.reason);
    }
}

static void hsb_try_install_hooks(void) {
    if (g_hook.hook_installed) return;
    if (!g_api || !g_api->hook_func) return;
    if (g_hook.attempts >= HSB_MAX_ATTEMPTS) return;
    g_hook.attempts++;

    g_cand_count = 0;
    g_xseg_count = 0;
    g_hook.lib_path[0] = '\0';
    HsbImageRequest req = {0u};
    dl_iterate_phdr(hsb_find_cb, &req);
    g_hook.matches_phdr = req.matches;
    unsigned after_phdr = g_xseg_count;
    hsb_scan_maps();
    g_hook.matches_maps = g_xseg_count - after_phdr;
    hsb_merge_xsegs();
    if (g_xseg_count == 0u) {
        snprintf(g_hook.reason, sizeof(g_hook.reason),
                 "target lib absent here (phdr=%u maps=%u cb=%u)",
                 req.matches, g_xseg_count, g_report.launcher_cb_hits);
        LOGI("hook not ready: %s", g_hook.reason);
        return;
    }

    const char *want = hsb_chosen_anchor();
    LOGI("anchor selected: %s (phdr=%u maps_x=%u cands=%u)", want, g_hook.matches_phdr,
         g_hook.matches_maps, g_cand_count);
    for (unsigned i = 0; i < HSB_ANCHOR_COUNT; i++) {
        const HsbLauncherAnchor *a = &kHsbAnchors[i];
        if (strcmp(a->name, want) != 0) continue;

        /* 第一路：profile 偏移 + 候选 load bias（同构建设备应直接命中） */
        for (unsigned c = 0; c < g_cand_count; c++) {
            uintptr_t base = g_cands[c];
            uintptr_t at = base + (uintptr_t) a->offset;
            if (!hsb_in_exec(at, HSB_PROLOGUE_BYTES)) {
                LOGI("cand %p out of exec seg, skip (target=%p)", (void *) base, (void *) at);
                continue;
            }
            g_hook.prologue_checked++;
            const volatile uint8_t *p = (const volatile uint8_t *) at;
            unsigned bad = HSB_PROLOGUE_BYTES;
            for (unsigned b = 0; b < HSB_PROLOGUE_BYTES; b++) {
                if (p[b] != a->prologue[b]) { bad = b; break; }
            }
            if (bad == HSB_PROLOGUE_BYTES) {
                hsb_install_at(a, at, base, "offset");
                return;
            }
            g_hook.prologue_mismatch++;
            snprintf(g_hook.reason, sizeof(g_hook.reason),
                     "%s cand=%p prologue[%u]=%02x want %02x",
                     a->name, (void *) base, bad, p[bad], a->prologue[bad]);
            LOGI("cand rejected: %s", g_hook.reason);
        }

        /* 第二路：偏移全崩（构建漂移）⇒ 按 prologue 特征扫地址 */
        g_hook.sig_scans++;
        uintptr_t sig = hsb_sigscan(a->prologue, &g_hook.sig_hits);
        if (sig) {
            LOGI("sigscan found %s at=%p (offsets stale, hits=1)", a->name, (void *) sig);
            hsb_install_at(a, sig, 0u, "sigscan");
            return;
        }
        snprintf(g_hook.reason, sizeof(g_hook.reason),
                 "offset cands=%u mismatch=%u | sigscan hits=%u segs=%u (build drift or codegen diff)",
                 g_cand_count, g_hook.prologue_mismatch, g_hook.sig_hits, g_xseg_count);
        LOGI("hook refused: %s", g_hook.reason);
        return;
    }
    snprintf(g_hook.reason, sizeof(g_hook.reason), "anchor %s not in profile", want);
    LOGI("hook skipped: %s", g_hook.reason);
}

static int hsb_prop_enabled(void) {
#if !HSB_NATIVE_SPIKE
    /* 编译期总闸（release 线）：返回常量 0 ⇒ 所有 hook 路径（扫描/装 hook/回调补拍/
     * Java retry）都走不到，链接期还能被 dead-strip 掉。OS4 spike 分支由
     * CMake 的 HSB_NATIVE_SPIKE=ON 打开。这条替代"OS3 装机回归"做静态证明：
     * 主线包里这段代码不是"运行时不执行"，而是"根本不存在"。 */
    return 0;
#else
    char v[8] = {0};
    return __system_property_get("debug.hsb.native.hook", v) > 0 && strcmp(v, "1") == 0;
#endif
}

__attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_report.init_fired = 1;
    LOGI("native_init: entries=%p version=%u hookFunc=%p unhookFunc=%p",
         (const void *) entries,
         entries ? entries->version : 0u,
         entries ? (void *) (uintptr_t) entries->hook_func : NULL,
         entries ? (void *) (uintptr_t) entries->unhook_func : NULL);

    if (!entries || !entries->hook_func || !entries->unhook_func) {
        g_report.api_incomplete = 1;
        LOGI("native_init: api incomplete, probe stops (no hooks installed)");
        return NULL;
    }
    g_api = entries;
    g_report.api_version = entries->version;
    g_report.has_hook_func = entries->hook_func ? 1u : 0u;
    g_report.has_unhook_func = entries->unhook_func ? 1u : 0u;

    /* 已加载库回放（照抄参考实现，但**只作取证**）：裸 soname 的 RTLD_NOLOAD 查询受
     * linker namespace 隔离与加载时机影响，实机给出 absent 而桌面库其实在内存里
     * （见 §门控复盘）。hook 侧现在自己扫 maps+phdr，不再依赖这里的结论。 */
    for (int i = 0; kReplayLibs[i]; i++) {
        void *h = dlopen(kReplayLibs[i], RTLD_NOW | RTLD_NOLOAD);
        if (h) {
            g_report.replay_mask |= 1u << i;
            LOGI("replay: %s already loaded (handle=%p)", kReplayLibs[i], h);
            on_module_loaded(kReplayLibs[i], h);
            dlclose(h);
        } else {
            LOGI("replay: %s absent (取证项，非判据)", kReplayLibs[i]);
        }
    }

    /* 门控只看 prop：prop 关=一行扫描代码都不多跑；prop 开=尝试安装（内部 fail-closed） */
    if (hsb_prop_enabled()) hsb_try_install_hooks();

    LOGI("native_init: ready installed=%u hits=%u rejected=%s",
         g_hook.hook_installed, g_hook.hits, g_hook.reason[0] ? g_hook.reason : "-");
    return on_module_loaded;
}

JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_report(JNIEnv *env, jclass clazz);

/*
 * Java 侧补拍（第三个触发点）。native_init 那次尝试很可能太早——桌面的 Rust 库是它自己
 * System.loadLibrary 出来的，可能在我们之后；框架的 dlopen 回调能不能覆盖到这次加载，
 * 取决于 LSPosed 对 `android_dlopen_ext` 的拦截是否作用于该 namespace（未证）。
 * 所以再给一条**确定可达**的路：TurboLayout（桌面进程）init 时和延迟 5s 各调一次。
 * prop 关=直接返回，什么都不做。
 */
JNIEXPORT void JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_retry(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!hsb_prop_enabled()) return;
    hsb_try_install_hooks();
    LOGI("retry from java: installed=%u hits=%u via=%s reason=%s",
         g_hook.hook_installed, g_hook.hits, g_hook.how[0] ? g_hook.how : "-",
         g_hook.reason[0] ? g_hook.reason : "-");
}

/*
 * Java 取报告口（静态 native，符号进 exports-lsposed.map 的 global 段）。
 * 每行一个事实，Java 侧逐行写 HLog → 运行日志导出/自检报告自动携带。
 */
JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_report(JNIEnv *env, jclass clazz) {
    (void)clazz;
    /* ctor 时刻进程名还是 `<pre-initialized>`（ specialize 未完），那时抓的 cmdline 没用：
     * 报告前重读一次（1008 导出里每段都显示 <pre-initialized> 就是这个原因） */
    if (!g_report.process[0] || strstr(g_report.process, "pre-initialized")) note_process();
    char buf[1024];
    snprintf(buf, sizeof(buf),
             "magic=HSB1 process=%s\n"
             "ctor=%u native_init=%u api_version=%u hookFunc=%u unhookFunc=%u api_incomplete=%u\n"
             "dlopen_callback_calls=%u launcher_cb=%u replay_mask=0x%x\n"
             "hook: attempts=%u phdr=%u maps_x=%u checked=%u mismatch=%u sigscan=%u/%u "
             "installed=%u failed=%u hits=%u anchor=%s via=%s bias=%p target=%p alive=%llus\n"
             "hook: lib=%s reason=%s",
             g_report.process[0] ? g_report.process : "(unknown)",
             g_report.ctor_ran, g_report.init_fired, g_report.api_version,
             g_report.has_hook_func, g_report.has_unhook_func, g_report.api_incomplete,
             g_report.callback_calls, g_report.launcher_cb_hits, g_report.replay_mask,
             g_hook.attempts, g_hook.matches_phdr, g_hook.matches_maps,
             g_hook.prologue_checked, g_hook.prologue_mismatch,
             g_hook.sig_scans, g_hook.sig_hits, g_hook.hook_installed,
             g_hook.hook_failed, g_hook.hits,
             g_hook.anchor[0] ? g_hook.anchor : "-",
             g_hook.how[0] ? g_hook.how : "-", (void *) g_hook.bias, (void *) g_hook.target,
             g_hook.installed_at_s ? (unsigned long long) (hsb_now_s() - g_hook.installed_at_s) : 0ull,
             g_hook.lib_path[0] ? g_hook.lib_path : "-",
             g_hook.reason[0] ? g_hook.reason : "-");
    return (*env)->NewStringUTF(env, buf);
}

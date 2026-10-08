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
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>
#include <sys/system_properties.h>
#include "launcher_profiles.h"

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

/* 感兴趣的宿主库：OS4 桌面的两个 Rust/Flutter so。OS2/OS3 上理应都 absent。 */
static const char *const kReplayLibs[] = {
    "libapp_launcher.so",
    "libapp.so",
    NULL,
};

/* 报告结构：写入集中在加载路径（单线程），读在 JNI，无竞争 */
static struct {
    uint32_t ctor_ran;        /* constructor 探针已执行（=框架真 dlopen 了我们） */
    uint32_t init_fired;      /* native_init 被框架调用（=U1 成立） */
    uint32_t api_version;
    uint32_t has_hook_func;
    uint32_t has_unhook_func;
    uint32_t api_incomplete;  /* 因 API 不完整返回 NULL，未回 dlopen 回调 */
    uint32_t callback_calls;  /* 框架调 on_module_loaded 的次数（回调链活性） */
    uint32_t replay_mask;     /* 回放命中的库位图 */
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

static void on_module_loaded(const char *name, void *handle) {
    g_report.callback_calls++;
    (void)name;
    (void)handle;
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
 * （连 dl_iterate_phdr 都不跑），所以冻结线/发版包带这段代码也无风险。
 *
 * 本步只证明四件事：地址解析对、inline hook 装得上且不掉、日志能出、桌面不崩。
 * 故只挂 profile 里最小的那一个锚点（try_delegate_back_to_sidebar，188B、非热路径），
 * 并刻意**先不做 madvise 守卫**——那正是本步要验的风险（参考工程认为缺它会被 HYOS 的
 * madvise 把 hook 页打回文件映射而"神秘失效"）。判据含"30 分钟后 hits 仍在增长"；
 * 若停滞则照抄参考实现补守卫。
 *
 * fail-closed 三道：镜像唯一命中 / 地址处 32B prologue 与 profile 逐字节相等 /
 * hook_func 返回 0 且 backup 非空。任一不满足 ⇒ 拒绝安装并记原因（桌面 OTA remap 后
 * 表现为"不 hook"而不是崩桌面）。
 */
#define HSB_ONLY_ANCHOR "try_delegate_back_to_sidebar"
#define HSB_LOG_LIMIT 12u

typedef uintptr_t (*HsbFn8)(uintptr_t, uintptr_t, uintptr_t, uintptr_t,
                            uintptr_t, uintptr_t, uintptr_t, uintptr_t);

static struct {
    uint32_t scan_runs;
    uint32_t image_matches;
    uint32_t prologue_checked;
    uint32_t prologue_mismatch;
    uint32_t hook_installed;
    uint32_t hook_failed;
    uint32_t hits;
    uintptr_t bias;
    char reason[128];
} g_hook;

static HsbFn8 g_orig_try_delegate;
static volatile uint32_t g_stub_logged;

static uintptr_t hsb_stub_try_delegate(uintptr_t a0, uintptr_t a1, uintptr_t a2,
                                       uintptr_t a3, uintptr_t a4, uintptr_t a5,
                                       uintptr_t a6, uintptr_t a7) {
    uint32_t n = __atomic_add_fetch(&g_hook.hits, 1u, __ATOMIC_RELAXED);
    if (__atomic_fetch_add(&g_stub_logged, 1u, __ATOMIC_RELAXED) < HSB_LOG_LIMIT) {
        LOGI("hook HIT %s #%u a0=%p a1=%p", HSB_ONLY_ANCHOR, n, (void *) a0, (void *) a1);
    }
    return g_orig_try_delegate
           ? g_orig_try_delegate(a0, a1, a2, a3, a4, a5, a6, a7) : 0u;
}

typedef struct {
    uintptr_t base;
    uint32_t matches;
} HsbImageRequest;

static int hsb_find_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    HsbImageRequest *req = (HsbImageRequest *) data;
    const char *name = info->dlpi_name ? info->dlpi_name : "";
    const char *leaf = strrchr(name, '/');
    leaf = leaf ? leaf + 1 : name;
    /* APK 内映射时形如 "...base.apk!/lib/arm64-v8a/libapp_launcher.so"，取末段即可 */
    if (strcmp(leaf, "libapp_launcher.so") != 0) return 0;
    req->matches++;
    req->base = (uintptr_t) info->dlpi_addr;
    return 0;
}

static void hsb_try_install_hooks(void) {
    if (!g_api || !g_api->hook_func) return;

    HsbImageRequest req = {0u, 0u};
    dl_iterate_phdr(hsb_find_cb, &req);
    g_hook.scan_runs++;
    g_hook.image_matches = req.matches;
    if (req.matches != 1u || req.base == 0u) {
        snprintf(g_hook.reason, sizeof(g_hook.reason),
                 "image matches=%u base=%p (need exactly 1)", req.matches, (void *) req.base);
        LOGI("hook refused: %s", g_hook.reason);
        return;
    }
    g_hook.bias = req.base;

    for (unsigned i = 0; i < HSB_ANCHOR_COUNT; i++) {
        const HsbLauncherAnchor *a = &kHsbAnchors[i];
        if (strcmp(a->name, HSB_ONLY_ANCHOR) != 0) continue;
        const volatile uint8_t *at = (const volatile uint8_t *) (uintptr_t) (req.base + a->offset);
        g_hook.prologue_checked++;
        for (unsigned b = 0; b < HSB_PROFILE_PROLOGUE_BYTES; b++) {
            if (at[b] != a->prologue[b]) {
                g_hook.prologue_mismatch++;
                snprintf(g_hook.reason, sizeof(g_hook.reason),
                         "%s prologue[%u]=%02x want %02x (text remap?)",
                         a->name, b, at[b], a->prologue[b]);
                LOGI("hook refused: %s", g_hook.reason);
                return;
            }
        }
        void *target = (void *) (uintptr_t) (req.base + a->offset);
        void *backup = NULL;
        int rc = g_api->hook_func(target, (void *) hsb_stub_try_delegate, &backup);
        if (rc == 0 && backup != NULL) {
            g_orig_try_delegate = (HsbFn8) (uintptr_t) backup;
            g_hook.hook_installed++;
            LOGI("hook installed %s target=%p backup=%p size=%llu bias=%p",
                 a->name, target, backup, (unsigned long long) a->size, (void *) req.base);
        } else {
            g_hook.hook_failed++;
            snprintf(g_hook.reason, sizeof(g_hook.reason), "hook_func rc=%d backup=%p",
                     rc, backup);
            LOGI("hook failed: %s", g_hook.reason);
        }
        return;
    }
    snprintf(g_hook.reason, sizeof(g_hook.reason), "anchor %s not in profile", HSB_ONLY_ANCHOR);
    LOGI("hook skipped: %s", g_hook.reason);
}

static int hsb_prop_enabled(void) {
    char v[8] = {0};
    return __system_property_get("debug.hsb.native.hook", v) > 0 && strcmp(v, "1") == 0;
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

    /* 已加载库回放（照抄参考实现）：框架回调只覆盖之后的加载，HYOS 会在 native_init
     * 之前就把桌面原生镜像加载完 ⇒ 必须主动查一拍，否则永久错过目标。
     * RTLD_NOLOAD 是刻意的：不能因为模块初始化就引入任何新依赖。 */
    for (int i = 0; kReplayLibs[i]; i++) {
        void *h = dlopen(kReplayLibs[i], RTLD_NOW | RTLD_NOLOAD);
        if (h) {
            g_report.replay_mask |= 1u << i;
            LOGI("replay: %s already loaded (handle=%p)", kReplayLibs[i], h);
            on_module_loaded(kReplayLibs[i], h);
            dlclose(h);
        } else {
            LOGI("replay: %s absent (expected on OS2/OS3)", kReplayLibs[i]);
        }
    }

    /* 只有桌面进程 replay 命中 launcher 库时才可能装 hook；prop 关=一行代码都不多跑 */
    if (hsb_prop_enabled() && (g_report.replay_mask & 1u)) hsb_try_install_hooks();

    LOGI("native_init: ready installed=%u hits=%u rejected=%s",
         g_hook.hook_installed, g_hook.hits, g_hook.reason[0] ? g_hook.reason : "-");
    return on_module_loaded;
}

/*
 * Java 取报告口（静态 native，符号进 exports-lsposed.map 的 global 段）。
 * 每行一个事实，Java 侧逐行写 HLog → 运行日志导出/自检报告自动携带。
 */
JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_report(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char buf[768];
    snprintf(buf, sizeof(buf),
             "magic=HSB1 process=%s\n"
             "ctor=%u native_init=%u api_version=%u hookFunc=%u unhookFunc=%u api_incomplete=%u\n"
             "dlopen_callback_calls=%u replay_mask=0x%x\n"
             "hook: runs=%u matches=%u checked=%u mismatch=%u installed=%u failed=%u hits=%u bias=%p reason=%s",
             g_report.process[0] ? g_report.process : "(unknown)",
             g_report.ctor_ran, g_report.init_fired, g_report.api_version,
             g_report.has_hook_func, g_report.has_unhook_func, g_report.api_incomplete,
             g_report.callback_calls, g_report.replay_mask,
             g_hook.scan_runs, g_hook.image_matches, g_hook.prologue_checked,
             g_hook.prologue_mismatch, g_hook.hook_installed, g_hook.hook_failed,
             g_hook.hits, (void *) g_hook.bias,
             g_hook.reason[0] ? g_hook.reason : "-");
    return (*env)->NewStringUTF(env, buf);
}

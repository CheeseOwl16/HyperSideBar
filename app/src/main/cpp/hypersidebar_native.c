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
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>

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
 * 构造期探针（2026-09-30 复盘新增，见 docs/adaptation/os4-native-entry-postmortem.md §2.1）。
 *
 * **为什么必须有它**：探针原来只在 native_init 内部打日志。若框架压根没 dlopen 本 so，
 * 日志一条都没有 —— 无法区分下面两种截然不同的情况：
 *   (a) 框架加载了 so，但没调用 native_init  ⇒ 问题在清单解析 / API 版本 / 入口约定；
 *   (b) 框架根本没加载 so                     ⇒ native entry 未被处理。
 * 有了构造期日志/报告位就能一刀切开：有 ctor 无 native_init = (a)；连 ctor 都没有 = (b)。
 *
 * 注意用 constructor 而非 JNI_OnLoad：框架是 dlopen + dlsym（不走 JVM），ctor 在
 * dlopen 收尾执行、早于框架的 native_init 回调。
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

__attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_report.init_fired = 1;
    LOGI("native_init: entries=%p version=%u hookFunc=%p unhookFunc=%p",
         (const void *)entries,
         entries ? entries->version : 0u,
         entries ? (void *)(uintptr_t)entries->hook_func : NULL,
         entries ? (void *)(uintptr_t)entries->unhook_func : NULL);

    if (!entries || !entries->hook_func || !entries->unhook_func) {
        g_report.api_incomplete = 1;
        LOGI("native_init: api incomplete, probe stops (no hooks installed)");
        return NULL;
    }
    g_report.api_version = entries->version;
    g_report.has_hook_func = entries->hook_func ? 1u : 0u;
    g_report.has_unhook_func = entries->unhook_func ? 1u : 0u;

    /* 已加载库回放（照抄参考实现）：框架回调只覆盖之后的加载，目标若早已在内存
     * 会漏报，这里用 RTLD_NOLOAD 主动查一拍——刻意不引入新依赖、也不真正 dlopen。 */
    for (int i = 0; kReplayLibs[i]; i++) {
        void *h = dlopen(kReplayLibs[i], RTLD_NOW | RTLD_NOLOAD);
        if (h) {
            g_report.replay_mask |= 1u << i;
            LOGI("replay: %s already loaded (handle=%p)", kReplayLibs[i], h);
            on_module_loaded(kReplayLibs[i], h);
        } else {
            LOGI("replay: %s absent (expected on OS2/OS3)", kReplayLibs[i]);
        }
    }

    LOGI("native_init: probe ready, returning dlopen callback");
    return on_module_loaded;
}

/*
 * Java 取报告口（静态 native，符号进 exports-lsposed.map 的 global 段）。
 * 每行一个事实，Java 侧逐行写 HLog → 运行日志导出/自检报告自动携带。
 */
JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_report(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char buf[512];
    snprintf(buf, sizeof(buf),
             "magic=HSB1 process=%s\n"
             "ctor=%u native_init=%u api_version=%u hookFunc=%u unhookFunc=%u api_incomplete=%u\n"
             "dlopen_callback_calls=%u replay_mask=0x%x",
             g_report.process[0] ? g_report.process : "(unknown)",
             g_report.ctor_ran, g_report.init_fired, g_report.api_version,
             g_report.has_hook_func, g_report.has_unhook_func, g_report.api_incomplete,
             g_report.callback_calls, g_report.replay_mask);
    return (*env)->NewStringUTF(env, buf);
}

/*
 * LSPosed native entry 探针（OS4 适配前置验证，adapt/hyperos4）。
 *
 * 目的（在 OS3/OS2 上就能验，见 docs/adaptation/marble-os4-decompile-verification.md §6 与
 * F:\hyperos\work\docs\lsposed-native-entry-verification.md §4.2）：
 *   U1：本机的 LSPosed 构建是否真的解析 META-INF/xposed/native_init.list 并 dlopen 本 so
 *       调用 native_init —— 这决定 OS4 桌面 native hook 路线是否成立；
 *   优雅跳过：OS2/OS3 的 launcher 没有 libapp_launcher.so，本探针必须安静记录后不做任何事。
 *
 * ABI 按官方 wiki《Native Hook》：entries 内容不可改写；返回的回调在之后每次 dlopen 时
 * 被框架以 (name, handle) 调用。hookFunc/unhookFunc 任一为空即视为 API 不完整。
 *
 * 日志走 logcat tag=HSBNative（`adb logcat -s HSBNative`），有意不接 HLog——
 * 探针必须在 HLog/LogDumpBridge 注册之前就能独立取证。
 */
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

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

static void on_module_loaded(const char *name, void *handle) {
    LOGI("module loaded: %s handle=%p", name ? name : "(null)", handle);
}

static void log_self(void) {
    FILE *f = fopen("/proc/self/cmdline", "r");
    char buf[160] = {0};
    if (f) {
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        (void)n;
        fclose(f);
    }
    LOGI("process=%s", buf[0] ? buf : "(unreadable)");
}

__attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    LOGI("native_init: entries=%p version=%u hookFunc=%p unhookFunc=%p",
         (const void *)entries,
         entries ? entries->version : 0u,
         entries ? (void *)(uintptr_t)entries->hook_func : NULL,
         entries ? (void *)(uintptr_t)entries->unhook_func : NULL);
    log_self();

    if (!entries || !entries->hook_func || !entries->unhook_func) {
        LOGI("native_init: api incomplete, probe stops (no hooks installed)");
        return NULL;
    }

    /* 已加载库回放（照抄参考实现）：框架回调只覆盖之后的加载，目标若早已在内存
     * 会漏报，这里用 RTLD_NOLOAD 主动查一拍——刻意不引入新依赖、也不真正 dlopen。 */
    for (int i = 0; kReplayLibs[i]; i++) {
        void *h = dlopen(kReplayLibs[i], RTLD_NOW | RTLD_NOLOAD);
        if (h) {
            LOGI("replay: %s already loaded (handle=%p)", kReplayLibs[i], h);
            on_module_loaded(kReplayLibs[i], h);
        } else {
            LOGI("replay: %s absent (expected on OS2/OS3)", kReplayLibs[i]);
        }
    }

    LOGI("native_init: probe ready, returning dlopen callback");
    return on_module_loaded;
}

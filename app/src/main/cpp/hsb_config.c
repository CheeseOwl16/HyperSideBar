#include "hsb_config.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/system_properties.h>

#define HSB_CFG_PATH   "/data/local/tmp/hsb.conf"
#define HSB_CFG_MAX    8192
#define HSB_CFG_PREFIX "persist.hsb."

static char       g_buf[HSB_CFG_MAX];
static size_t     g_len;
static uint64_t   g_checked_s;
static long       g_mtime;
static long long  g_size;

static uint64_t cfg_now_s(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t) ts.tv_sec;
}

/* 1 秒粒度检查 + mtime/size 判变更 ⇒ 只在文件真变了才读盘。 */
static void cfg_reload_if_changed(void) {
    uint64_t n = cfg_now_s();
    if (g_checked_s == n) return;
    g_checked_s = n;
    struct stat st;
    if (stat(HSB_CFG_PATH, &st) != 0 || !S_ISREG(st.st_mode)) {
        g_len = 0;
        g_mtime = 0;
        g_size = 0;
        return;
    }
    if (st.st_mtime == g_mtime && (long long) st.st_size == g_size) return;
    FILE *f = fopen(HSB_CFG_PATH, "rb");
    if (!f) {
        g_len = 0;
        return;
    }
    size_t n_read = fread(g_buf, 1, sizeof(g_buf) - 1u, f);
    fclose(f);
    g_buf[n_read] = 0;
    g_len = n_read;
    g_mtime = st.st_mtime;
    g_size = (long long) st.st_size;
}

/* 行式 `key=value`（`#` 注释、允许两侧空白与 CRLF）里找 key（短名）。 */
static const char *cfg_file_value(const char *key, size_t *vlen) {
    if (!g_len) return NULL;
    const char *short_key = key;
    size_t plen = strlen(HSB_CFG_PREFIX);
    if (strncmp(key, HSB_CFG_PREFIX, plen) == 0) short_key = key + plen;
    size_t klen = strlen(short_key);
    const char *p = g_buf;
    const char *end = g_buf + g_len;
    while (p < end) {
        const char *eol = (const char *) memchr(p, '\n', (size_t) (end - p));
        const char *stop = eol ? eol : end;
        const char *eq = (const char *) memchr(p, '=', (size_t) (stop - p));
        if (eq && *p != '#') {
            const char *ks = p;
            const char *ke = eq;
            while (ks < ke && (*ks == ' ' || *ks == '\t')) ks++;
            while (ke > ks && (ke[-1] == ' ' || ke[-1] == '\t')) ke--;
            if ((size_t) (ke - ks) == klen && strncmp(ks, short_key, klen) == 0) {
                const char *vs = eq + 1;
                const char *ve = stop;
                while (vs < ve && (*vs == ' ' || *vs == '\t')) vs++;
                while (ve > vs && (ve[-1] == ' ' || ve[-1] == '\t' || ve[-1] == '\r')) ve--;
                *vlen = (size_t) (ve - vs);
                return vs;
            }
        }
        if (!eol) break;
        p = eol + 1;
    }
    return NULL;
}

/* 文件 → 属性（同名，完整 key）→ 都没有返回 0。 */
static int cfg_lookup(const char *key, char *out, size_t cap) {
    cfg_reload_if_changed();
    size_t vlen = 0;
    const char *v = cfg_file_value(key, &vlen);
    if (v && vlen > 0 && vlen < cap) {
        memcpy(out, v, vlen);
        out[vlen] = 0;
        return 1;
    }
    char prop[128] = {0};
    if (__system_property_get(key, prop) > 0 && prop[0]) {
        snprintf(out, cap, "%s", prop);
        return 1;
    }
    return 0;
}

float hsb_cfg_float(const char *key, float lo, float hi, float dflt) {
    char v[32] = {0};
    if (!cfg_lookup(key, v, sizeof(v))) return dflt;
    char *end = NULL;
    float f = strtof(v, &end);
    if (end == v) return dflt;
    if (!(f >= lo && f <= hi)) return dflt;   /* NaN 也落这里 */
    return f;
}

int hsb_cfg_int(const char *key, int lo, int hi, int dflt) {
    char v[32] = {0};
    if (!cfg_lookup(key, v, sizeof(v))) return dflt;
    char *end = NULL;
    long n = strtol(v, &end, 10);
    if (end == v || n < (long) lo || n > (long) hi) return dflt;
    return (int) n;
}

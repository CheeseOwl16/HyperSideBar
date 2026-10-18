package com.lsp.hypersidebar.util

/**
 * 配置通道（native 侧读取实现在 `app/src/main/cpp/hsb_config.c`）：文件优先 → 系统属性 → 缺省。
 *
 * **2026-10-17 真机更正**：原以为 `/data/local/tmp` 对普通 app 可写（"免 root"），
 * OS4 真机实测 **EACCES**（`open failed: /data/local/tmp/hsb.conf.tmp`）⇒ 改为：
 * 先在 app 自己的 cacheDir 写好，再用既有的 `su` 一次性 `cp → mv → chmod 644` 落到
 * `/data/local/tmp/hsb.conf`。好处仍然成立：不受属性长度限制、可注释、**原子替换**（不会让
 * native 读到半截），而且与属性推送共用同一条 su 依赖（不新增权限面）。
 *
 * 键名约定：文件里用**短名**（属性名去掉 `persist.hsb.` 前缀），例如 `corner.hot_pct=8`。
 * native 侧 1 秒粒度 + `stat`(mtime/size) 判变更 ⇒ 改完 ≤1s 生效，无需重启任何宿主。
 *
 * 与属性**并存**而非替代：文件写失败只记日志，老装机继续吃属性值。
 */
object ConfigFileBridge {

    private const val TAG = "ConfigFile"
    private const val DST = "/data/local/tmp/hsb.conf"
    private const val SU_TIMEOUT_MS = 4_000L

    /**
     * 把标量写成 `key=value` 并原子替换。值用 `toString()`；浮点请先自行格式化成与属性一致的
     * 字符串（例如 `"%.4f"`）——native 侧只做 `strtof/strtol`。
     */
    fun pushScalars(entries: Map<String, Any>, context: android.content.Context?) {
        if (entries.isEmpty()) return
        val ctx = context ?: return
        val body = buildString {
            append("# hyperSidebar native 配置（模块 App 自动生成；手改这个文件也有效）\n")
            append("# 键名 = 系统属性名去掉 persist.hsb. 前缀；native 1 秒粒度读取 ⇒ 改完 ≤1s 生效。\n")
            entries.forEach { (k, v) ->
                append(k.removePrefix("persist.hsb.")).append('=').append(v.toString()).append('\n')
            }
        }
        val tmp = java.io.File(ctx.cacheDir, "hsb.conf.new")
        val r = runCatching {
            tmp.writeText(body)
            // 单条 su：cp 到 .tmp 再 mv（同一文件系统内 rename 是原子的）⇒ native 永远读不到半截。
            val cmd = "cp '${tmp.absolutePath}' $DST.tmp && mv $DST.tmp $DST && chmod 644 $DST"
            val p = ProcessBuilder("su", "-c", cmd).start()
            val finished = p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                error("su timeout")
            }
            if (p.exitValue() != 0) error("su exit=${p.exitValue()}")
            true
        }
        if (r.isSuccess) {
            HLog.i(TAG, "config pushed: ${entries.size} keys → $DST")
        } else {
            HLog.w(TAG, "config push failed: ${r.exceptionOrNull()?.message}")
        }
        tmp.delete()
    }

    /** 给离线实验/排障用：读回设备上的内容（async，结果只打日志）。 */
    fun dump(context: android.content.Context?) {
        val ctx = context ?: return
        runCatching {
            val p = ProcessBuilder("su", "-c", "cat $DST 2>/dev/null").start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            HLog.i(TAG, "config dump: ${text.trim().replace('\n', '|')}")
        }.onFailure { HLog.w(TAG, "config dump failed: ${it.message}") }
    }
}

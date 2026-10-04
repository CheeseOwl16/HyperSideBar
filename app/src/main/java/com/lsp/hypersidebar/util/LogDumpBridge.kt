package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.lsp.hypersidebar.prefs.PrefKeys
import org.json.JSONObject

/**
 * 日志拉取回传端（迭代六 §11.2，hook 进程侧）。
 *
 * 与 manifest 快捷方式桥同模式：模块 App（日志页/自检 v2）发 [PrefKeys.LOG_DUMP_REQUEST]
 * 广播，本进程接收器把 HLog 环形缓冲 JSON + 进程状态快照（熔断等）以
 * [PrefKeys.LOG_DUMP_REPLY] 定向回传。模块 App 前台=活进程，必收。
 *
 * 注册点：EdgeGestureHook（launcher）/FreeformRelayHook（:ui）/ **SystemUiHook（sys）**
 * 的 Application.attach，与探针接收器同款生命周期。
 *
 * ⚠️ **新增 hook 进程时必须同时在此注册** —— 否则该进程日志不进自检报告。
 * 09-27 报告即为此盲区的实例：SystemUI 未注册，磁贴通道全盲（无法判断 hook 是否加载、
 * 适配器是否解析、点击走到哪一档），排查只能靠猜。
 */
object LogDumpBridge {

    /** 幂等守卫：XposedInit 提前武装（不等宿主 hook）与各 hook 的 attach 注册可共存 */
    @Volatile
    private var registered = false

    /** provider 存字段不存闭包：后续 register 调用升级替换（取快照在应答时求值） */
    @Volatile
    private var statusProvider: () -> String? = { null }

    @Volatile
    private var statsProvider: () -> String? = { null }

    /** 进程启动时刻（uptime）：object 首次触及即初始化（XposedInit 极早期武装路径），
     *  用于识别"新包装了但 :ui 旧启动"这类历史事故（os4-trigger-final-plan §4 P0-2） */
    private val procStartUptime = SystemClock.uptimeMillis()

    /** 分块大小：1000 条全量 ≈150KB，200 条/块 ≈30KB 远离 binder 1MB 上限 */
    private const val CHUNK = 200

    /**
     * 接收器只注册一次；provider 后到者覆盖（EdgeGestureHook 带 CircuitBreaker/StatsRecorder
     * 的富注册到达时自动顶掉 XposedInit 早期注册的空 provider）。
     *
     * @param statusProvider 进程状态快照（JSON 字符串，可 null）——launcher/:ui 传
     *   CircuitBreaker.snapshot()，模块 App 不注册本接收器（直读本地缓冲）
     * @param statsProvider 使用数据聚合 JSON（§11.3，可 null）——launcher/:ui 传
     *   StatsRecorder.dump()
     */
    fun register(
        context: Context,
        statusProvider: () -> String? = { null },
        statsProvider: () -> String? = { null }
    ) {
        this.statusProvider = statusProvider
        this.statsProvider = statsProvider
        if (registered) return
        registered = true
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val roundId = intent.getLongExtra(PrefKeys.LOG_DUMP_EXTRA_ROUND, 0L)
                        val entries = HLog.snapshot()
                        val meta = JSONObject()
                            .put("entries", entries.size)
                            .put("dropped", HLog.dropped)
                            .put("verbose", HLog.verboseEnabled)
                            .put("upMs", procStartUptime)
                        val chunks = entries.chunked(CHUNK)
                        val total = chunks.size
                        chunks.forEachIndexed { seq, chunk ->
                            val arr = org.json.JSONArray()
                            chunk.forEach { arr.put(it.toJson()) }
                            val reply = Intent(PrefKeys.LOG_DUMP_REPLY)
                                .setPackage(FreeformLauncher.MODULE_PACKAGE)
                                .putExtra(PrefKeys.LOG_DUMP_EXTRA_PROC, HLog.proc())
                                .putExtra(PrefKeys.LOG_DUMP_EXTRA_ROUND, roundId)
                                .putExtra(PrefKeys.LOG_DUMP_EXTRA_CHUNK_SEQ, seq)
                                .putExtra(PrefKeys.LOG_DUMP_EXTRA_CHUNK_TOTAL, total)
                                .putExtra(PrefKeys.LOG_DUMP_EXTRA_META, meta.toString())
                                .putExtra(PrefKeys.LOG_DUMP_EXTRA_LOGS, arr.toString())
                            this@LogDumpBridge.statusProvider()?.takeIf { seq == 0 }?.let {
                                reply.putExtra(PrefKeys.LOG_DUMP_EXTRA_STATUS, it)
                            }
                            this@LogDumpBridge.statsProvider()?.takeIf { seq == 0 }?.let {
                                reply.putExtra(PrefKeys.LOG_DUMP_EXTRA_STATS, it)
                            }
                            runCatching { c.sendBroadcast(reply) }
                                .onFailure { HLog.w("LogDump", "reply send failed: ${it.message}") }
                        }
                    }
                },
                IntentFilter(PrefKeys.LOG_DUMP_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            HLog.i("LogDump", "log dump receiver registered")
        }.onFailure { HLog.w("LogDump", "register failed: ${it.message}") }
    }
}

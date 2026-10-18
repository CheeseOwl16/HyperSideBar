package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import com.lsp.hypersidebar.prefs.PrefKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 日志采集器（迭代六 §11.2，模块 App 进程侧，配对 LogDumpBridge）。
 *
 * 日志页/自检 v2 的数据源：发 [PrefKeys.LOG_DUMP_REQUEST] 广播，各 hook 进程把
 * HLog 环形缓冲 JSON 定向回传；本对象按进程短名（launcher/ui/sys/app）归档到
 * Compose state 供页面直接渲染。模块 App 自身缓冲（proc="app"）不经广播、直读。
 */
object LogCollector {

    /** proc 短名 → 日志条目（时间升序） */
    val logs = mutableStateMapOf<String, List<HLog.Entry>>()

    /** proc 短名 → 进程状态快照 JSON（熔断等，可空缺） */
    val statuses = mutableStateMapOf<String, String>()

    /** proc 短名 → StatsRecorder 聚合 JSON（§11.3，可空缺） */
    val stats = mutableStateMapOf<String, String>()

    /** proc 短名 → 回传元信息 JSON（entries/dropped/verbose/upMs，可空缺） */
    val metas = mutableStateMapOf<String, String>()

    /** 当前轮次 id：只认本轮回传，上一轮迟到块直接丢弃（防串包） */
    @Volatile private var currentRound = 0L

    /** 分块拼装暂存：proc → (seq → 条目)；集齐 total 块后按序合并进 [logs] */
    private val pending = HashMap<String, HashMap<Int, List<HLog.Entry>>>()
    private val pendingTotal = HashMap<String, Int>()

    @Volatile private var registered = false

    /** 注册用的 context 与接收器引用：**原先是匿名注册（无处注销）**——API 102 热重载下
     *  AMS 持有的 receiver 会把旧代 classloader 钉住，且新代再注册一份=两份归档端。 */
    @Volatile private var registeredCtx: Context? = null
    private var receiver: BroadcastReceiver? = null

    /** 幂等注册（MainActivity/日志页进入时调用；模块进程内只需一次） */
    fun register(context: Context) {
        if (registered) return
        registered = true
        // 注册与注销必须用**同一个 context 实例**（LoadedApk 的 receiver 表以外层 context 为键），
        // 所以这里取一次就存字段，不再各算各的 applicationContext
        val appCtx = context.applicationContext
        registeredCtx = appCtx
        runCatching {
            val recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val proc = intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_PROC) ?: return
                    if (intent.getLongExtra(PrefKeys.LOG_DUMP_EXTRA_ROUND, 0L) != currentRound) return
                    intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_META)?.let {
                        if (it.isNotEmpty()) metas[proc] = it
                    }
                    intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_STATUS)?.let {
                        if (it.isNotEmpty()) statuses[proc] = it
                    }
                    intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_STATS)?.let {
                        if (it.isNotEmpty()) stats[proc] = it
                    }
                    val json = intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_LOGS) ?: "[]"
                    val entries = parse(json)
                    val total = intent.getIntExtra(PrefKeys.LOG_DUMP_EXTRA_CHUNK_TOTAL, 1)
                    val seq = intent.getIntExtra(PrefKeys.LOG_DUMP_EXTRA_CHUNK_SEQ, 0)
                    if (total <= 1) {
                        logs[proc] = entries
                        return
                    }
                    synchronized(pending) {
                        pendingTotal[proc] = total
                        val acc = pending.getOrPut(proc) { HashMap() }
                        acc[seq] = entries
                        if (acc.size < total) return
                        pending.remove(proc)
                        pendingTotal.remove(proc)
                        logs[proc] = (0 until total).flatMap { acc[it].orEmpty() }
                    }
                }
            }
            appCtx.registerReceiver(
                recv,
                IntentFilter(PrefKeys.LOG_DUMP_REPLY),
                Context.RECEIVER_EXPORTED
            )
            receiver = recv
        }.onFailure { HLog.w("LogCollector", "register failed: ${it.message}") }
    }

    /**
     * API 102 热重载释放（幂等）。
     *
     * 为什么必须：回传接收器由宿主 AMS 持有——不解注册，旧代 classloader 回收不了，
     * 且新代再注册一份就会把同一次请求的分块归档两遍（自检报告出现重复 proc 段）。
     * 本对象注册在**模块 App 进程**（MainActivity/日志页），该进程通常不在热重载目标里；
     * 入口仍然提供，供"模块进程也在 scope 内"的配置或未来统一清理路径调用，未注册即空操作。
     * 已归档的 [logs]/[statuses]/[stats] 不清：释放的是"注册"，不是"已采到的数据"。
     */
    fun releaseForReload() {
        runCatching {
            val r = receiver
            val c = registeredCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w("LogCollector", "unregister failed: ${it.message}") }
        receiver = null
        registeredCtx = null
        registered = false
        // 半集齐的分块暂存：留着只会与新代（若重注册）的新一轮混在一起
        synchronized(pending) { pending.clear(); pendingTotal.clear() }
    }

    /** 拉一轮：广播请求各 hook 进程 + 直读本进程缓冲（proc="app"）。 */
    fun request(context: Context) {
        mergeLocal()
        currentRound = SystemClock.uptimeMillis()
        synchronized(pending) { pending.clear(); pendingTotal.clear() }
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(PrefKeys.LOG_DUMP_REQUEST)
                    .putExtra(PrefKeys.LOG_DUMP_EXTRA_ROUND, currentRound)
            )
        }.onFailure { HLog.w("LogCollector", "request failed: ${it.message}") }
    }

    /** 收尾：本轮仍有未集齐分块的 proc ⇒ 丢弃并落状态（判读时知道丢了谁）。
     *  调用点：requestAndAwait 等待结束后（自检 v2/导出）。 */
    private fun finalizeRound() {
        val incomplete = synchronized(pending) {
            val out = pending.mapValues { it.value.size to (pendingTotal[it.key] ?: 0) }
            pending.clear(); pendingTotal.clear()
            out
        }
        incomplete.forEach { (proc, have) ->
            statuses[proc] = "日志分块不完整（${have.first}/${have.second} 块）本轮丢弃——重导一次或加大等待"
            HLog.w("LogCollector", "chunked logs incomplete for $proc: dropped this round")
        }
    }

    /** 模块自身缓冲并入 state（不经广播） */
    fun mergeLocal() {
        logs[HLog.proc()] = HLog.snapshot()
    }

    private fun parse(json: String): List<HLog.Entry> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HLog.Entry(
                wallMs = o.optLong("ts"),
                level = o.optString("lvl", "I").firstOrNull() ?: 'I',
                proc = o.optString("proc", "?"),
                tag = o.optString("tag", "?"),
                msg = o.optString("msg")
            )
        }
    }.getOrDefault(emptyList())

    /** 四进程全量快照合并（自检 v2/导出用），按进程分段、段内时间升序。 */
    fun mergedForExport(): Map<String, List<HLog.Entry>> = logs.toMap()

    /** IO 线程拉取并等待回传（自检 v2 用）：请求后短暂等待各进程 REPLY 落账。 */
    suspend fun requestAndAwait(context: Context, waitMs: Long = 1500L) {
        request(context)
        withContext(Dispatchers.IO) { Thread.sleep(waitMs) }
        finalizeRound()
        mergeLocal()
    }
}

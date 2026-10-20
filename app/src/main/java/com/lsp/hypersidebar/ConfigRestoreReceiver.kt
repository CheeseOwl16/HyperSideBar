package com.lsp.hypersidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.RelayToken

private const val TAG = "ConfigRestore"

/**
 * 配置恢复通道（2026-10-19 数据丢失事故后新增）：把外部备份的 remotePrefs 键值
 * 灌回模块（经本进程的 LSPosed 服务写回，走与 UI 保存完全相同的链路 + ConfigSync 广播）。
 *
 * 背景：debug↔release 换签名必须卸载重装，模块本地数据随卸载消失；remotePrefs
 * （LSPosed 守护进程侧）虽幸存，但"编辑器读了空缓存后保存"会整表覆盖用户数据
 * （真机事故：6 条快捷方式被 1 条覆盖）。有这个通道，事故后可以从备份秒级恢复。
 *
 * 防伪：与 [ShortcutRelayReceiver] 同款——运行期随机令牌严格校验
 * （[RelayToken.verifyRelay]：无令牌/不匹配一律拒绝；缓存为空时先等 remotePrefs
 * 绑定补挂令牌，≤3s，超时按拒绝处理）。令牌只在本进程/授权 hook 进程内流转，
 * 第三方 app 拿不到 ⇒ 不能伪造恢复广播。
 *
 * 只允许恢复**白名单内**的键（用户数据类），防止用它改写 enabled/令牌等安全键。
 */
class ConfigRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!RelayToken.verifyRelay(intent)) return
        val key = intent.getStringExtra(PrefKeys.RESTORE_EXTRA_KEY) ?: return
        if (key !in RESTORE_ALLOWED_KEYS) {
            HLog.w(TAG, "restore rejected: key=$key 不在白名单")
            return
        }
        // 值来源二选一：直接 extra，或文件路径（JSON 含引号/换行时走 adb 转义必炸；
        // 限 /data/local/tmp 下、shell 可写目录，且 receiver 读完即删）
        val value = intent.getStringExtra(PrefKeys.RESTORE_EXTRA_VALUE)
            ?: intent.getStringExtra(PrefKeys.RESTORE_EXTRA_PATH)?.let { path ->
                runCatching {
                    require(path.startsWith("/data/local/tmp/")) { "path 越界" }
                    val f = java.io.File(path)
                    val s = f.readText()
                    f.delete()
                    s
                }.getOrElse {
                    HLog.w(TAG, "restore value file 读取失败: ${it.message}")
                    return
                }
            } ?: return
        val trace = intent.getStringExtra(com.lsp.hypersidebar.util.Trace.EXTRA)
        val pending = goAsync()
        Thread {
            try {
                if (RelayToken.current() == null) {
                    val provisioned =
                        com.lsp.hypersidebar.util.RemotePrefsBridge.awaitTokenProvision()
                    HLog.i(TAG, "cold provision: ok=$provisioned")
                }
                val prefs = RemotePrefsBridge.prefs ?: run {
                    HLog.w(TAG, "restore failed: remotePrefs 未绑定（LSPosed 桥不可用）")
                    return@Thread
                }
                prefs.edit().putString(key, value).apply()
                // 与 UI 保存同款：写完显式推一次配置同步（hook 进程实时生效）
                com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged(key)
                HLog.i(TAG, "config restored: $key (${value.length}B) trace=${trace ?: "-"}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** 允许恢复的键（用户数据类；安全键 enabled/relayToken 等绝不在此列）。 */
        val RESTORE_ALLOWED_KEYS = setOf(
            PrefKeys.SHORTCUT_ACTIONS,
            PrefKeys.CUSTOM_APPS,
            PrefKeys.CUSTOM_APPS_ORDER,
            "cachedSuggestions",
        )
    }
}

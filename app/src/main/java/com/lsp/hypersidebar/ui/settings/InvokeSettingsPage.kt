package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.SettingsRepository
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.RemotePrefsBridge
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * **操作设置**二级页（2026-10-17 由"呼出设置"改名并并入小窗段；0913 用户拍板建页，首页交互区入口进入）。
 *
 * 本页 = 一切的"操作"开关：呼出类（滑动距离 / 呼出停顿 / 呼出后常驻）+ 小窗类
 * （点击空白处缩小/关闭 + 动作档）。2026-10-17 用户要求把小窗设置并进来，避免二级页碎片化。
 *
 * - 滑动距离 = 呼出确认线（PrefKeys.TRIGGER_MIN_DISTANCE，dp）：hook 侧两通道 DOWN 换算
 *   px 缓存，下次手势生效；滑回重置=确认距离一半（GestureThresholds.SWIPE_RESET_RATIO 滞回）
 * - 呼出停顿 = 锚点圆 dwell（PrefKeys.TRIGGER_DWELL_MS，ms）：停顿判定逐手势现读，即时生效
 *
 * 滑条同款"拖动暂存、松手落盘（=一次 ConfigSync 广播）"节奏；revision 通道回读外部改动
 *（一键恢复默认后返回本页即刷新）。
 */
@Composable
internal fun InvokeSettingsPage(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    // D1 同款：绑定晚到时参数 prefs 是本地空壳，effectivePrefs 切换强制整页重读
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    val repo = remember(effectivePrefs) { SettingsRepository(effectivePrefs) }
    DisposableEffect(repo) { onDispose { repo.dispose() } }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.invoke_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 量程/步进契约取自 LayoutDefaults（禁止内联），刻度数与吸附由辅助函数推导
                    val distanceMin = LayoutDefaults.TRIGGER_SWIPE_DISTANCE_MIN_DP.toFloat()
                    val distanceMax = LayoutDefaults.TRIGGER_SWIPE_DISTANCE_MAX_DP.toFloat()
                    val distanceStep = LayoutDefaults.TRIGGER_SWIPE_DISTANCE_STEP_DP.toFloat()
                    val dwellMin = LayoutDefaults.TRIGGER_DWELL_MIN_MS.toFloat()
                    val dwellMax = LayoutDefaults.TRIGGER_DWELL_MAX_MS.toFloat()
                    val dwellStep = LayoutDefaults.TRIGGER_DWELL_STEP_MS.toFloat()
                    var swipeDp by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.triggerMinDistanceDp().roundToInt())
                    }
                    SliderPreference(
                        title = stringResource(R.string.trigger_swipe_distance_title),
                        summary = stringResource(R.string.trigger_swipe_distance_summary),
                        valueText = "$swipeDp dp",
                        value = swipeDp.toFloat(),
                        valueRange = distanceMin..distanceMax,
                        steps = sliderSteps(distanceMin, distanceMax, distanceStep),
                        onValueChange = {
                            swipeDp = quantizeToStep(it, distanceMin, distanceMax, distanceStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.TRIGGER_MIN_DISTANCE, swipeDp.toFloat())
                        }
                    )
                    var dwellMs by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.triggerDwellMs())
                    }
                    SliderPreference(
                        title = stringResource(R.string.trigger_dwell_title),
                        summary = stringResource(R.string.trigger_dwell_summary),
                        valueText = "$dwellMs ms",
                        value = dwellMs.toFloat(),
                        valueRange = dwellMin..dwellMax,
                        steps = sliderSteps(dwellMin, dwellMax, dwellStep),
                        onValueChange = {
                            dwellMs = quantizeToStep(it, dwellMin, dwellMax, dwellStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.TRIGGER_DWELL_MS, dwellMs)
                        }
                    )
                    var persistent by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.fanPersistentOnRelease())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.fan_persistent_title),
                        summary = stringResource(R.string.fan_persistent_summary),
                        checked = persistent,
                        onCheckedChange = {
                            persistent = it
                            repo.save(PrefKeys.FAN_PERSISTENT_ON_RELEASE, it)
                        }
                    )
                    // （2026-10-13 清理，用户 m03028）本页原有三项已删除：
                    //   ①「边缘停顿借用系统判定」开关（p7 现一律放行给原生，开关失去意义）
                    //   ②「全边缘触发·实验」总开关（B 路线在 OS4 恒开，spy 转发是竖屏唯一驱动源）
                    //   ③ 触发带三滑条（高度/位置/宽度）——几何收敛为 LayoutDefaults 默认参数
                }
            }
        }
        item { SmallTitle(text = stringResource(R.string.freeform_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    var enabled by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.freeformBlankTapEnabled())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.freeform_blank_tap_title),
                        summary = stringResource(R.string.freeform_blank_tap_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            repo.save(PrefKeys.FREEFORM_BLANK_TAP_ENABLED, it)
                        }
                    )
                    // "吞掉触摸（不穿透）"（2026-10-17，**默认关**）：真机反馈抢流会干扰正常拖动/缩小，
                    // 所以只在用户明确需要"点空白不漏给背后 app"时才开（回退=关掉即可，改完重启 SystemUI 生效）。
                    // 2026-10-18：与总开关联动——总开关关 ⇒ 后面的子项没有意义，不显示（用户口径）。
                    if (enabled) {
                        var takeover by remember(effectivePrefs, repo.revision) {
                            mutableStateOf(repo.freeformTakeoverEnabled())
                        }
                        SwitchPreference(
                            title = stringResource(R.string.freeform_takeover_title),
                            summary = stringResource(R.string.freeform_takeover_summary),
                            checked = takeover,
                            onCheckedChange = {
                                takeover = it
                                repo.save(PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER, it)
                            }
                        )
                    // 动作档：取值顺序 = 契约 LayoutDefaults.FREEFORM_BLANK_TAP_MODE_VALUES，
                    // 下标映射不得内联（与「背景模糊来源」同款约束）
                    if (enabled) {
                    val modeValues = LayoutDefaults.FREEFORM_BLANK_TAP_MODE_VALUES
                    // ⚠ options/summaries 与 FREEFORM_BLANK_TAP_MODE_VALUES **按下标一一对应**
                    //（2026-10-18 曾漏第三项 ⇒ 图标档在下拉里看不见）
                    val modeOptions = listOf(
                        stringResource(R.string.freeform_mode_mini),
                        stringResource(R.string.freeform_mode_pin),
                        stringResource(R.string.freeform_mode_close)
                    )
                    val modeSummaries = listOf(
                        stringResource(R.string.freeform_mode_mini_summary),
                        stringResource(R.string.freeform_mode_pin_summary),
                        stringResource(R.string.freeform_mode_close_summary)
                    )
                    var mode by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.freeformBlankTapMode())
                    }
                    val modeIndex = modeValues.indexOf(mode).coerceAtLeast(0)
                    OverlayDropdownMenu(
                        title = stringResource(R.string.freeform_mode_title),
                        options = modeOptions,
                        selectedIndex = modeIndex,
                        summary = modeSummaries[modeIndex],
                        onSelectedIndexChange = { index ->
                            val value = modeValues[index]
                            mode = value
                            repo.save(PrefKeys.FREEFORM_BLANK_TAP_MODE, value)
                        }
                    )
                    }
                }
            }
        }
        }
        item {
            Text(
                text = stringResource(R.string.freeform_blank_tap_footnote),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

    }
}

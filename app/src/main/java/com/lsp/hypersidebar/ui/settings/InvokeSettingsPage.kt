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
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 呼出设置二级页（0913 用户拍板，首页交互区入口进入）。
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
                    // OS4 边缘停顿时机选择（默认关=条上停顿即呼出，无需松手/OS3 时机；
                    // 开=借用系统判定，边缘任意位置松手后呼出）。OS2/3 无效果。
                    var evokeBorrowSystem by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.evokeBorrowSystem())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.evoke_borrow_system_title),
                        summary = stringResource(R.string.evoke_borrow_system_summary),
                        checked = evokeBorrowSystem,
                        onCheckedChange = {
                            evokeBorrowSystem = it
                            repo.save(PrefKeys.EVOKE_BORROW_SYSTEM, it)
                        }
                    )
                    // OS4 B 路线（实验，默认关）：SystemUI spy 转发边缘流+pilfer 回流，
                    // 恢复全边缘"停顿即呼出+拖选"（OS3 语义）。改动后重启系统界面生效。
                    var sysInputOwner by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.sysInputOwner())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.sys_input_owner_title),
                        summary = stringResource(R.string.sys_input_owner_summary),
                        checked = sysInputOwner,
                        onCheckedChange = {
                            sysInputOwner = it
                            repo.save(PrefKeys.SYS_INPUT_OWNER, it)
                        }
                    )
                    // B 路线触发带几何（2026-10-07 参考 MiuiBackGestureHook 百分比模型）：
                    // 带高/带位双百分比（默认 33/50=[H/3,2H/3]）+ 半宽 dp（默认=官方条宽）。
                    // 仅 OS4 B 路线消费（sys 启动快照，改动须重启系统界面）；OS2/3 无效果。
                    val bandHMin = LayoutDefaults.BAND_HEIGHT_PERCENT_MIN.toFloat()
                    val bandHMax = LayoutDefaults.BAND_HEIGHT_PERCENT_MAX.toFloat()
                    val bandHStep = LayoutDefaults.BAND_HEIGHT_PERCENT_STEP.toFloat()
                    var bandHeightPct by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.bandHeightPercent())
                    }
                    SliderPreference(
                        title = stringResource(R.string.band_height_title),
                        summary = stringResource(R.string.band_height_summary),
                        valueText = "$bandHeightPct %",
                        value = bandHeightPct.toFloat(),
                        valueRange = bandHMin..bandHMax,
                        steps = sliderSteps(bandHMin, bandHMax, bandHStep),
                        onValueChange = {
                            bandHeightPct = quantizeToStep(it, bandHMin, bandHMax, bandHStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.BAND_HEIGHT_PERCENT, bandHeightPct)
                        }
                    )
                    val bandPMin = LayoutDefaults.BAND_POSITION_PERCENT_MIN.toFloat()
                    val bandPMax = LayoutDefaults.BAND_POSITION_PERCENT_MAX.toFloat()
                    val bandPStep = LayoutDefaults.BAND_POSITION_PERCENT_STEP.toFloat()
                    var bandPositionPct by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.bandPositionPercent())
                    }
                    SliderPreference(
                        title = stringResource(R.string.band_position_title),
                        summary = stringResource(R.string.band_position_summary),
                        valueText = "$bandPositionPct %",
                        value = bandPositionPct.toFloat(),
                        valueRange = bandPMin..bandPMax,
                        steps = sliderSteps(bandPMin, bandPMax, bandPStep),
                        onValueChange = {
                            bandPositionPct = quantizeToStep(it, bandPMin, bandPMax, bandPStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.BAND_POSITION_PERCENT, bandPositionPct)
                        }
                    )
                    val bandWMin = LayoutDefaults.BAND_WIDTH_DP_MIN.toFloat()
                    val bandWMax = LayoutDefaults.BAND_WIDTH_DP_MAX.toFloat()
                    val bandWStep = LayoutDefaults.BAND_WIDTH_DP_STEP.toFloat()
                    var bandWidthDp by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.bandWidthDp())
                    }
                    SliderPreference(
                        title = stringResource(R.string.band_width_title),
                        summary = stringResource(R.string.band_width_summary),
                        valueText = "$bandWidthDp dp",
                        value = bandWidthDp.toFloat(),
                        valueRange = bandWMin..bandWMax,
                        steps = sliderSteps(bandWMin, bandWMax, bandWStep),
                        onValueChange = {
                            bandWidthDp = quantizeToStep(it, bandWMin, bandWMax, bandWStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.BAND_WIDTH_DP, bandWidthDp)
                        }
                    )
                }
            }
        }
    }
}

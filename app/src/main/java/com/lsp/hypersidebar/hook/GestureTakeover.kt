package com.lsp.hypersidebar.hook

/**
 * 输入接管记录核（纯 JVM，可单测）。
 *
 * 背景（2026-10-17 血案）：`SystemUiInputSpy` 是 monitor（只观察不消费）。为了"点小窗外空白
 * 不把这次点击漏给背后 app"，必须在 **DOWN** 用 `pilferPointers()` 抢流；而 pilfer **不可回滚**，
 * 所以抢了之后必须**必然二选一**：
 *
 * - 我们的动作命中 ⇒ **消费**（丢弃记录，不重放）；
 * - 否则（拖拽/多指/超 slop/窗口消失/记录溢出）⇒ **重放**（把整条手势用 `injectInputEvent` 还给系统）。
 *
 * 本类只做"记录 + 计划"（纯数据，不碰 android.os），注入与 `MotionEvent` 拆解在
 * [com.lsp.hypersidebar.hook.SystemUiInputSpy] 里（那里才能真正拿到 `InputManager`）。
 *
 * 上限是硬约束：`MAX_SAMPLES` / `MAX_MS` 保证内存 O(1)，且超出即标记 [Snapshot.overflow]
 * ——**半条手势不能还给系统**（那比丢失更糟：会造出一个假的 DOWN）。
 */
class GestureTakeover {

    /** 一个待重放采样。[action] 用 `MotionEvent.ACTION_*`；history 采样一律 ACTION_MOVE。 */
    class Sample(
        val action: Int,
        val eventTime: Long,
        val x: Float,
        val y: Float,
        val metaState: Int,
        val source: Int,
    )

    /** 一条完整手势的快照（[overflow] 为真表示记录被截断 ⇒ 不可重放）。 */
    class Snapshot(
        val downTime: Long,
        val deviceId: Int,
        val source: Int,
        val samples: List<Sample>,
        val overflow: Boolean,
    )

    /** 是否正在记录（同一时刻只允许一条：接管与"当前手势"一一对应）。 */
    var active = false
        private set

    private var downTime = 0L
    private var deviceId = -1
    private var source = 0
    private var startMs = 0L
    private var overflow = false

    // 预分配平行数组（2026-10-18 性能审计：append 在输入线程逐事件跑，原 `ArrayList<Sample>`
    // 每样本一个对象+扩容；数组化后追加零分配，List 只在 end() 物化一次）。
    private val sAction = IntArray(MAX_SAMPLES)
    private val sEventTime = LongArray(MAX_SAMPLES)
    private val sX = FloatArray(MAX_SAMPLES)
    private val sY = FloatArray(MAX_SAMPLES)
    private val sMeta = IntArray(MAX_SAMPLES)
    private val sSource = IntArray(MAX_SAMPLES)
    private var count = 0

    /** 开始记录。[downTime] 既是身份键也是时间基准。 */
    fun begin(downTime: Long, deviceId: Int, source: Int) {
        this.downTime = downTime
        this.deviceId = deviceId
        this.source = source
        this.startMs = downTime
        overflow = false
        count = 0
        active = true
    }

    /** 记录一个采样；超上限/超时长 ⇒ 只置 [overflow]（不抛、不阻塞输入线程）。 */
    fun append(action: Int, eventTime: Long, x: Float, y: Float, metaState: Int, source: Int) {
        if (!active) return
        if (count >= MAX_SAMPLES || eventTime - startMs > MAX_MS) {
            overflow = true
            return
        }
        sAction[count] = action
        sEventTime[count] = eventTime
        sX[count] = x
        sY[count] = y
        sMeta[count] = metaState
        sSource[count] = source
        count++
    }

    /** 结束记录并取快照；无样本 ⇒ null。调用方随后必须 [clear]。 */
    fun end(): Snapshot? {
        if (!active) return null
        active = false
        if (count == 0) return null
        val out = ArrayList<Sample>(count)
        for (i in 0 until count) {
            out.add(Sample(sAction[i], sEventTime[i], sX[i], sY[i], sMeta[i], sSource[i]))
        }
        return Snapshot(downTime, deviceId, source, out, overflow)
    }

    fun clear() {
        active = false
        overflow = false
        count = 0
    }

    /**
     * 计划重放的采样序列：顺序 = 记录顺序；[Snapshot.overflow] 为真 ⇒ **空**
     * （宁可让背后 app 丢这一次手势，也不能还一条残缺手势）。
     */
    fun plan(snap: Snapshot): List<Sample> = if (snap.overflow) emptyList() else snap.samples

    /**
     * "这条手势是否仍是**轻点候选**"——纯规则，可单测。
     *
     * 用途（2026-10-17 真机反馈"拖动被吞"的修法）：接管期间每个事件都过一遍本规则；一旦不再是
     * 轻点候选（越 slop / 超时长 / 多指），立刻把已缓冲的事件按序注入并转入实时转发，
     * **不能等抬手**——等抬手整条重放，一次拖动会被还原成"起点的一次点击"，滚动/fling 全失真。
     *
     * 参数与 `GestureThresholds.BLANK_TAP_SLOP_DP` / `BLANK_TAP_MAX_MS` 同源（调用方负责换算像素）。
     */
    fun stillTapCandidate(
        pointerCount: Int,
        dx: Float,
        dy: Float,
        durMs: Long,
        slopPx: Float,
        maxMs: Long,
    ): Boolean {
        if (pointerCount > 1) return false
        if (durMs > maxMs) return false
        return (dx * dx + dy * dy) <= slopPx * slopPx
    }

    companion object {
        const val MAX_SAMPLES = 512
        const val MAX_MS = 2_000L
    }
}

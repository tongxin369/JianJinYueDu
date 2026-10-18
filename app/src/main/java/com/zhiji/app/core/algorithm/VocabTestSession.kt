package com.zhiji.app.core.algorithm

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.max

/**
 * 词汇掌握度**连续自适应**诊断（纯逻辑，可单测）。
 *
 * 用一个"精确认知位置" `pos` 表示当下难度：0.00 = A0.00、1.00 = A1.00、…、6.99 = C2.99（负值为 A0 之下）。
 *  - 每次抽题都从 `pos` 出发的**窄频带**取词（跨度约 [pos, pos+0.10]），严格匹配用户当前水平；
 *  -「认识」→ 认知位置上移一小步（抽更难词）；「不认识」→ 下调较大一步（生词是"偏难"的更强信号）；
 *  - **防倒退**：认知位置不低于上次等级 [floorLevel]，因此结果不会出现大幅等级倒退；
 *  - 测满 [maxQuestions] 题后，把最终 `pos` 映射为 粗等级(level) + 0..999 子分(finalSub)。
 */
class VocabTestSession(
    private val startPos: Float,
    private val floorLevel: Int,
    private val maxQuestions: Int
) {
    companion object {
        const val MAX_POS = 6.99f
        const val MIN_POS = -0.6f
        /** 认识后上移幅度：收紧、不跨太大（约 A0.00→A0.10），稳妥渐进 */
        const val STEP_UP = 0.10f
        /** 半熟（会但不完全会）后小幅上移：比认识更小，介于认识与不认识之间（约 0.04） */
        const val STEP_SEMI = 0.04f
        /** 不认识后下调幅度：可大些，快速回落到真实水平（约 0.4） */
        const val STEP_DOWN = 0.40f
        /** 窄带跨度（层内占比）：如 A0.00 ~ A0.10，跨度小 → 抽词难度更精准、更循序渐进 */
        const val BAND_WIDTH = 0.10f
    }

    var pos: Float = startPos.coerceIn(MIN_POS, MAX_POS)
        private set
    var answered = 0
        private set
    var knownCount = 0
        private set
    var semiCount = 0
        private set
    var finished = false
        private set

    var finalLevel = -99
        private set
    var finalSub = 0
        private set

    /** 当前粗等级索引（0= A0 … 6= C2；负 pos → A0） */
    fun currentTier(): Int {
        val p = max(pos, 0f)
        return floor(p).toInt().coerceIn(0, VocabGrader.TIERS.lastIndex)
    }

    fun currentTierCode(): String = VocabGrader.tierCodeFor(currentTier())

    /** 当前应抽词的窄频带占比 [loFrac, hiFrac]，落在当前层级频带内且跨度 ≤ [BAND_WIDTH] */
    fun currentBandFrac(): DoubleArray {
        val p = max(pos, 0f).coerceAtMost(MAX_POS)
        val idx = floor(p).toInt().coerceIn(0, VocabGrader.TIERS.lastIndex)
        val t = VocabGrader.TIERS[idx]
        val span = t.bandStart + (t.bandEnd - t.bandStart) * (p - idx).coerceIn(0f, 0.99f)
        val width = (t.bandEnd - t.bandStart) * BAND_WIDTH
        val hi = min(span + width, t.bandEnd)
        return doubleArrayOf(span, hi)
    }

    /** 作答：known=认识（上移），semi=半熟（小幅上移），false=不认识（下调）。防倒退锚定在下限。 */
    fun answer(known: Boolean, semi: Boolean = false) {
        if (finished) return
        answered++
        when {
            semi -> { semiCount++; pos += STEP_SEMI }
            known -> { knownCount++; pos += STEP_UP }
            else -> pos -= STEP_DOWN
        }
        clampPos()
        if (answered >= maxQuestions) finish()
    }

    /** 「这一层不认识，降低」：再多下调一档 */
    fun giveUp() {
        if (finished) return
        pos -= STEP_DOWN
        clampPos()
    }

    private fun clampPos() {
        if (floorLevel >= 0) pos = max(pos, floorLevel.toFloat())
        pos = pos.coerceIn(MIN_POS, MAX_POS)
    }

    private fun finish() {
        finished = true
        val p = max(pos, 0f).coerceAtMost(MAX_POS)
        val level = floor(p).toInt().coerceIn(0, VocabGrader.TIERS.lastIndex)
        val sub = ((p - level) * 1000).roundToInt().coerceIn(0, 999) // 三位小数刻度 0.000~0.999，最小进步 0.001
        finalLevel = level
        finalSub = sub
    }
}
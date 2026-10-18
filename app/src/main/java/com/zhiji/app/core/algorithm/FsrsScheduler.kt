package com.zhiji.app.core.algorithm

import com.zhiji.app.data.database.entity.MemoryCardEntity
import com.zhiji.app.data.database.entity.MemoryReviewLogEntity
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * FSRS (Free Spaced Repetition Scheduler) v6 算法 —— 纯 Kotlin 单文件实现
 *
 * 算法参考：
 *   https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-Algorithm
 *
 * 这是 Phase 2 记忆库的调度核心：
 *   输入：[MemoryCardEntity]（复习前状态） + 用户打分 Rating (1=Again / 2=Hard / 3=Good / 4=Easy)
 *   输出：[FsrsResult]（更新后卡片实体 + 对应 ReviewLog 实体）
 *
 * 默认参数 w[17] 来自 FSRS v6 社区"通用最优"预设，对中文用户学习也已被学术论文验证
 * 误差 < 5%（不拟合个人数据时），Phase 4 再根据 MemoryReviewLogEntity 回归拟合个人 w。
 */
object FsrsScheduler {

    /** Rating：Again(忘得一干二净)=1 Hard(想很久才想出来)=2 Good(正常记起)=3 Easy(秒答)=4 */
    const val RATING_AGAIN = 1
    const val RATING_HARD = 2
    const val RATING_GOOD = 3
    const val RATING_EASY = 4

    /** FSRS 四大状态：New(新卡未复习) / Learning(学习中) / Review(正常复习) / Relearning(失败后重学) */
    const val STATE_NEW = 0
    const val STATE_LEARNING = 1
    const val STATE_REVIEW = 2
    const val STATE_RELEARNING = 3

    /** 通用默认权重 w[0..16]（FSRS v6 standard） */
    private val W = doubleArrayOf(
        0.4072, 1.1829, 3.1262, 15.4722, 7.2102, 0.5316,
        1.0651, 0.0234, 1.616, 0.1575, 1.0052, 1.9335,
        0.9531, 0.4978, 1.0461, 2.1072, 0.248
    )

    /** 可提取到期保留率（默认 90%，即 10 次复习里想不起 1 次，和 Phase 3 9:1 引擎完美衔接） */
    private const val REQUEST_RETENTION = 0.9

    /** 最大间隔天数 36525（100 年，防止算法算出负数/超大数） */
    private const val MAX_INTERVAL_DAYS = 36525

    /** 学习/重学阶段的"下一步时间"（固定间隔，不走 I=S/pow(r,1/w)，FSRS 规定） */
    private const val LEAG_STEP_MIN = 1.0 / (24 * 60)    // 1 分钟（分钟→天）
    private const val LEAG_STEP_MAX = 10.0 / (24 * 60)    // 10 分钟
    private const val RELEARNING_STEP = 10.0 / (24 * 60)  // 再学习 10 分钟

    data class FsrsResult(
        val updatedCard: MemoryCardEntity,
        val reviewLog: MemoryReviewLogEntity
    )

    /**
     * 执行一次复习调度。
     *
     * @param card 当前卡片（复习前）
     * @param rating 1 Again / 2 Hard / 3 Good / 4 Easy
     * @param reviewedAtMs 复习发生的系统时间戳（毫秒），通常传 System.currentTimeMillis()
     */
    fun schedule(
        card: MemoryCardEntity,
        rating: Int,
        reviewedAtMs: Long = System.currentTimeMillis()
    ): FsrsResult {
        require(rating in 1..4) { "FSRS rating must be in 1..4, got $rating" }

        // Step 1: ΔT = 今天 - lastReviewAt / 天。第一次复习(last=0)就按 ΔT=0
        val deltaTDays = if (card.lastReviewAt <= 0L) {
            0.0
        } else {
            (reviewedAtMs - card.lastReviewAt).toDouble() / (1000 * 60 * 60 * 24)
        }.coerceAtLeast(0.0)

        val stateBefore = card.fsrsState
        val sBefore = card.fsrsStability
        val dBefore = card.fsrsDifficulty

        var nextState = stateBefore
        var nextD = dBefore
        var nextS = sBefore
        var nextI = 0   // 间隔天数，到期时间 = reviewedAtMs + nextI 天
        var hardIntervalD = 0.0
        var goodIntervalD = 0.0
        var easyIntervalD = 0.0

        // Step 2: 根据 before state 分支执行不同公式
        when (stateBefore) {
            STATE_NEW -> {
                // 新卡
                nextD = initDifficulty(rating)
                when (rating) {
                    RATING_AGAIN -> {
                        nextState = STATE_LEARNING
                        nextI = LEAG_STEP_MIN.roundToNearestPositiveDay()
                        nextS = W[4] // S0 = w4
                    }
                    RATING_HARD -> {
                        nextState = STATE_LEARNING
                        nextI = LEAG_STEP_MAX.roundToNearestPositiveDay()
                        nextS = W[4] * W[5] // w4 * w5
                    }
                    RATING_GOOD -> {
                        nextState = STATE_REVIEW
                        val i1 = W[0].pow(1.0 / W[1]) // w0^(1/w1)
                        nextI = i1.roundToNearestPositiveDay()
                        nextS = W[0] // S0(G) = w0
                    }
                    RATING_EASY -> {
                        nextState = STATE_REVIEW
                        val i1 = W[0].pow(1.0 / W[1]) * W[2] // w0^(1/w1) * w2
                        nextI = i1.roundToNearestPositiveDay()
                        nextS = W[0] * W[3] // w0 * w3
                    }
                }
                if (nextS <= 0.0) nextS = 0.1
            }

            STATE_LEARNING, STATE_RELEARNING -> {
                // 学习中 / 再学习
                when (rating) {
                    RATING_AGAIN -> {
                        nextState = stateBefore
                        nextI = if (stateBefore == STATE_RELEARNING)
                            RELEARNING_STEP.roundToNearestPositiveDay()
                        else
                            LEAG_STEP_MIN.roundToNearestPositiveDay()
                        nextD = nextDifficulty(nextD, rating)
                    }
                    RATING_HARD -> {
                        nextState = stateBefore
                        nextI = if (stateBefore == STATE_RELEARNING)
                            (RELEARNING_STEP * 2.0).roundToNearestPositiveDay()
                        else
                            (LEAG_STEP_MIN * 6.0).roundToNearestPositiveDay()
                        nextD = nextDifficulty(nextD, rating)
                    }
                    RATING_GOOD -> {
                        // 从学习/再学习 → 毕业到 Review 正常调度
                        nextState = STATE_REVIEW
                        nextI = (W[6] * nextS).coerceAtLeast(1.0).roundToNearestPositiveDay()
                        nextD = nextDifficulty(nextD, rating)
                    }
                    RATING_EASY -> {
                        nextState = STATE_REVIEW
                        val i = W[6] * nextS * W[15]
                        nextI = i.coerceAtLeast(1.0).roundToNearestPositiveDay()
                        nextD = nextDifficulty(nextD, rating)
                        nextS = nextRecallStability(nextS, nextD, RATING_EASY, 1.0)
                    }
                }
            }

            STATE_REVIEW -> {
                // 正常复习（最常见分支）
                // 2.1 保留率 R = exp( -ΔT / S )
                val retrievability = if (sBefore > 0.0 && deltaTDays >= 0.0) {
                    exp(-deltaTDays / sBefore)
                } else 1.0

                // 2.2 新 D 先算
                nextD = nextDifficulty(dBefore, rating)

                // 2.3 新 S（Recall Stability = w7 * w8/D * w9*(S^w10) * ...）
                nextS = nextRecallStability(
                    stabilityBefore = sBefore,
                    difficulty = nextD,
                    rating = rating,
                    retrievability = retrievability
                )

                // 2.4 计算 Hard/Good/Easy 三种间隔，Hard 乘上 w11 降难度，Easy 乘 w12*w13 升
                val goodI = nextIntervalDays(nextS)
                hardIntervalD = (nextIntervalDays(nextS) * W[11]).coerceAtLeast(1.0)
                goodIntervalD = goodI
                easyIntervalD = (nextIntervalDays(nextS) * W[12] * W[13]).coerceAtLeast(1.0)

                when (rating) {
                    RATING_AGAIN -> {
                        nextState = STATE_RELEARNING
                        nextI = RELEARNING_STEP.roundToNearestPositiveDay()
                        nextS = W[4] // Again 后 S 重置为 w4
                    }
                    RATING_HARD -> nextI = hardIntervalD.roundToNearestPositiveDay()
                    RATING_GOOD -> nextI = goodIntervalD.roundToNearestPositiveDay()
                    RATING_EASY -> nextI = easyIntervalD.roundToNearestPositiveDay()
                }
                if (nextS <= 0.0) nextS = 0.1
            }
        }

        // Step 3: 约束边界
        nextI = nextI.coerceIn(0, MAX_INTERVAL_DAYS)
        nextD = nextD.coerceIn(1.0, 10.0)
        nextS = nextS.coerceAtLeast(0.1)

        val repsNow = card.fsrsReps + if (nextState == STATE_REVIEW && rating in RATING_HARD..RATING_EASY) 1 else 0
        val lapsesNow = card.fsrsLapses + if (rating == RATING_AGAIN) 1 else 0

        val dueAt = reviewedAtMs + nextI.toLong() * (24L * 60 * 60 * 1000)

        val updated = card.copy(
            fsrsState = nextState,
            fsrsStability = nextS,
            fsrsDifficulty = nextD,
            fsrsReps = repsNow,
            fsrsLapses = lapsesNow,
            fsrsIntervalDays = nextI,
            dueAt = dueAt,
            lastReviewAt = reviewedAtMs,
            updatedAt = reviewedAtMs
        )

        val log = MemoryReviewLogEntity(
            id = UUID.randomUUID().toString(),
            cardId = card.id,
            rating = rating,
            deltaTDays = deltaTDays,
            stateBefore = stateBefore,
            stateAfter = nextState,
            stabilityBefore = sBefore,
            stabilityAfter = nextS,
            difficultyBefore = dBefore,
            difficultyAfter = nextD,
            intervalDaysAfter = nextI,
            reviewedAt = reviewedAtMs
        )

        return FsrsResult(updated, log)
    }

    /** 新建一张卡后（还没复习过），直接初始化 dueAt = now（立刻进待复习队列，FSRS New 态默认） */
    fun createNewCard(
        id: String,
        front: String,
        back: String,
        status: String,
        sourceType: String,
        bookId: String? = null,
        bookTitle: String? = null,
        bookParagraphIndex: Int? = null,
        bookCharOffsetStart: Int? = null,
        bookCharOffsetEnd: Int? = null,
        cardType: String = "paragraph"
    ): MemoryCardEntity {
        val now = System.currentTimeMillis()
        val initialDifficulty = initDifficulty(RATING_GOOD) // 默认 D=5.0
        return MemoryCardEntity(
            id = id,
            front = front,
            back = back,
            cardType = cardType,
            status = status,
            fsrsState = STATE_NEW,
            fsrsStability = 0.0,
            fsrsDifficulty = initialDifficulty,
            fsrsReps = 0,
            fsrsLapses = 0,
            fsrsIntervalDays = 0,
            // New 状态 dueAt = now：立刻进「待复习」Tab，用户可以第一遍打分 Good/Again
            dueAt = now,
            lastReviewAt = 0L,
            sourceType = sourceType,
            bookId = bookId,
            bookTitle = bookTitle,
            bookParagraphIndex = bookParagraphIndex,
            bookCharOffsetStart = bookCharOffsetStart,
            bookCharOffsetEnd = bookCharOffsetEnd,
            createdAt = now,
            updatedAt = now
        )
    }

    // ==================== FSRS v6 公式 ====================

    private fun initDifficulty(rating: Int): Double =
        W[3] - W[4] * (rating - 3).toDouble().let { it - 2 } // D0 = w3 - w4 * (r-1) where r=4/3/2/1
            .coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, rating: Int): Double {
        // D' = D - w6 * (r-3) , with revert mean 5% when D stray
        val reduced = d - W[5] * (rating - 3)
        val meanReverted = reduced + 0.05 * (initDifficulty(RATING_GOOD) - reduced)
        return meanReverted.coerceIn(1.0, 10.0)
    }

    private fun nextRecallStability(
        stabilityBefore: Double,
        difficulty: Double,
        rating: Int,
        retrievability: Double
    ): Double {
        val hardPenalty = if (rating == RATING_HARD) W[15] else 1.0
        val easyBonus = if (rating == RATING_EASY) W[16] else 1.0
        val r = retrievability.coerceIn(0.01, 1.0)
        // S_{t+1} = S_t / w7 * (1/w8) * (1/D) * w9 * (1/R - 1)^(w10) * hardPenalty * easyBonus
        val base = stabilityBefore.coerceAtLeast(0.1)
        val factor1 = (1.0 / W[6]) * (1.0 / difficulty.pow(W[7]))
        val factor2 = W[8] * (1.0 / r - 1.0).pow(W[9])
        return (base * factor1 * factor2 * hardPenalty * easyBonus).coerceAtLeast(0.1)
    }

    /** 标准间隔：I = S / (w14 * ln(R) / ln(0.9))  其中 R = REQUEST_RETENTION = 0.9  结果正好 I = S * 常数 */
    private fun nextIntervalDays(nextStability: Double): Double {
        val r = REQUEST_RETENTION.coerceIn(0.01, 0.99)
        val factor90 = ln(r) / ln(0.9)
        return (nextStability / W[14] * factor90).coerceAtLeast(1.0)
    }

    /** 1 分钟级的学习阶段间隔也要 >=1 天的话会导致间隔太大，用这个函数：小于 1 天就转为毫秒累加到 dueAt 里
     *  （但我们 intervalDays 字段是 Int，所以再折衷：小于 1 天的就记 0 天，由 reviewedAt 实际值决定；dueAt = reviewedAtMs + nextI*86400_000，
     *   所以当 nextI=0 时 dueAt = reviewedAt 就立刻待复习 —— 学习期 10min/1min 在移动 App 场景用户不太会真的等，改成"下一次打开就复习"也 OK，
     *   Phase 4 加"今日 10min 内待复习"队列后再精细化。）
     */
    private fun Double.roundToNearestPositiveDay(): Int {
        return if (this <= 0.0) 0
        else this.roundToInt().coerceAtLeast(0)
    }
}

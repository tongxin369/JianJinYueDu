package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2 - 每次复习打分历史日志（FSRS v6 必需）
 *
 * 作用：
 *   1. 未来支持 FSRS「按历史重新拟合 w 参数」（个性化模型）
 *   2. 记忆库「复习日历 / 统计卡片」数据来源
 *   3. 回溯：一张卡片错在哪一次、选了什么，帮助用户复盘
 */
@Entity(
    tableName = "memory_review_logs",
    indices = [
        Index("cardId"),
        Index("reviewedAt"),
        Index("rating")
    ]
)
data class MemoryReviewLogEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    /** FSRS v6 Rating：Again=1 Hard=2 Good=3 Easy=4（注意从1开始，是算法标准规定） */
    val rating: Int,
    /** 复习这张卡时，用户距离它到期时间的「提前/逾期天数」，FSRS 计算需要 */
    val deltaTDays: Double,
    /** 复习前状态（New/Learning/Review/Relearning = 0/1/2/3） */
    val stateBefore: Int,
    /** 复习后状态（同上） */
    val stateAfter: Int,
    /** 复习前 S（稳定度 天） */
    val stabilityBefore: Double,
    /** 复习后 S */
    val stabilityAfter: Double,
    /** 复习前 D（难度） */
    val difficultyBefore: Double,
    /** 复习后 D */
    val difficultyAfter: Double,
    /** 复习后新间隔（天），下一次到期时间 = reviewedAt + intervalDays */
    val intervalDaysAfter: Int,
    /** 实际复习时间戳（毫秒） */
    val reviewedAt: Long,
    val createdAt: Long = reviewedAt
)

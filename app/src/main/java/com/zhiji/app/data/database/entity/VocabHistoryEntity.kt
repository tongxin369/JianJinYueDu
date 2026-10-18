package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 词汇掌握度测试**历史记录**（每次测试一行，用于成长曲线）。
 *
 * 与 vocab_proficiencies（仅存当前单行）不同，此表保留全部历史，
 * 支持按时间折线展示细分进步度（subScore）与等级的变化。
 * BackupManager 按全库打包，此表随 ZIP 备份自动兜住。
 */
@Entity(
    tableName = "vocab_history",
    indices = [Index("testedAt")]
)
data class VocabHistoryEntity(
    @PrimaryKey val id: String,
    /** CEFR 代号 "A0"~"C2" */
    val cefr: String,
    /** 中文层级名 */
    val levelName: String,
    /** 等级索引 0..6（0=A0 ... 6=C2），-1 = 预备级以下 */
    val level: Int,
    /** 细分进步度 0..99 */
    val subScore: Int,
    /** 估算认知词汇量 */
    val estVocabSize: Int,
    /** 本次测试题数（已判单词数） */
    val testedTotal: Int,
    /** 其中认识的数量 */
    val testedKnown: Int,
    /** 其中不认识的量数 */
    val testedUnknown: Int,
    /** 逐层结果串，形如 "A1:2;B2:3" */
    val bandResults: String,
    val testedAt: Long
)
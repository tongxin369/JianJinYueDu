package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 词汇掌握度测试结果（单行，全局唯一记录当前掌握水平）。
 *
 * 词汇测试每次完成后覆盖写一行（id 固定 "single"），用于记忆库仪表盘展示当前 CEFR 水平。
 * BackupManager 按全库打包，此表随 ZIP 备份自动兜住。
 */
@Entity(tableName = "vocab_proficiencies")
data class VocabProficiencyEntity(
    @PrimaryKey val id: String = "single",
    /** CEFR 代号："A1"~"C2"，未达 A1 记 "A0" */
    val cefr: String,
    /** 中文层级名（入门/基础/初级/中级/高级/精通/入门以下） */
    val levelName: String,
    /** 细分进步度 0..999：在同一 CEFR 等级内的量化进度（三位小数刻度，最小进步 0.001，如 B2.035） */
    val subScore: Int,
    /** 估算认知词汇量 */
    val estVocabSize: Int,
    /** 本次测试题数（已判单词数） */
    val testedTotal: Int,
    /** 其中认识的数量 */
    val testedKnown: Int,
    /** 其中不认识的量数 */
    val testedUnknown: Int,
    /** 逐层结果串，格式 "A1:2;B1:3;..."（层级:认识数，冒号分号拼），供展示/排查 */
    val bandResults: String,
    val testedAt: Long,
    /** 词库掌握进度：当前等级排名区间内，用户已知单词占该区间总词数的比例 0..1（随记忆库卡片变化实时刷新） */
    val masteryRatio: Float = 0f,
    /** 当前等级排名区间内词典收录的单字词总数（分母） */
    val windowTotal: Int = 0,
    /** 当前等级排名区间内，用户已掌握（known）的单词数（分子） */
    val windowKnown: Int = 0,
    /** 当前等级排名区间内，用户标记为「不熟」(semi)的单词数 */
    val windowSemi: Int = 0
)
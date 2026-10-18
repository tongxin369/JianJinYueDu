package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhiji.app.data.database.entity.VocabHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabHistoryDao {

    /** 全部历史，按时间升序（折线图直接按序描点） */
    @Query("SELECT * FROM vocab_history ORDER BY testedAt ASC")
    fun flowAll(): Flow<List<VocabHistoryEntity>>

    /** 最近 n 条，按时间升序返回（用于仪表盘缩略曲线） */
    @Query("SELECT * FROM vocab_history ORDER BY testedAt DESC LIMIT :n")
    suspend fun recent(n: Int): List<VocabHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VocabHistoryEntity)

    /** 更新最近一条历史记录的等级细分/估算词汇量（测试落档采用"自适应档位+词库掌握比例"时，用比例细分数回写，成长曲线与仪表盘一致） */
    @Query("UPDATE vocab_history SET subScore = :subScore, estVocabSize = :estVocabSize WHERE id = (SELECT id FROM vocab_history ORDER BY testedAt DESC LIMIT 1)")
    suspend fun patchLastEstimate(subScore: Int, estVocabSize: Int)
}
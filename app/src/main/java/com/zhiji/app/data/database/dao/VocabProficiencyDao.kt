package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhiji.app.data.database.entity.VocabProficiencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabProficiencyDao {

    /** 当前掌握度（单行），无记录返回空 */
    @Query("SELECT * FROM vocab_proficiencies LIMIT 1")
    fun flow(): Flow<VocabProficiencyEntity?>

    @Query("SELECT * FROM vocab_proficiencies LIMIT 1")
    suspend fun current(): VocabProficiencyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VocabProficiencyEntity)

    @Query("DELETE FROM vocab_proficiencies")
    suspend fun clear()
}
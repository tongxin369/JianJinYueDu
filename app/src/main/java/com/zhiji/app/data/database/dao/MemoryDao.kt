package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.zhiji.app.data.database.entity.MemoryCardEntity
import com.zhiji.app.data.database.entity.MemoryReviewLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ==================== MemoryCardEntity ====================

    /** 待复习：dueAt <= 当前UTC毫秒时间（SQLite 内部计算，等价 Java System.currentTimeMillis()）
     *  - Room Flow 每次重跑 SQL 都会重新算 julianday，避免默认参数只在类加载时算一次导致列表空
     *  - 与 countDueCards/countDue 统计口径完全一致
     *  - 排序按导入时间从新到旧（最新建的卡在最上面），与其他卡片列表口径一致 */
    @Query("SELECT * FROM memory_cards WHERE dueAt <= CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER) ORDER BY createdAt DESC")
    fun getDueCards(): Flow<List<MemoryCardEntity>>

    /** 已掌握：status = known 且 fsrsState == 2 Review 且 fsrsIntervalDays >= 21（记忆库 Tab2） */
    @Query("SELECT * FROM memory_cards WHERE status = 'known' AND fsrsState = 2 AND fsrsIntervalDays >= 21 ORDER BY lastReviewAt DESC")
    fun getMasteredCards(): Flow<List<MemoryCardEntity>>

    /** 生词本：status = unknown（记忆库 Tab3，9:1 引擎从这里挑 10% 新内容） */
    @Query("SELECT * FROM memory_cards WHERE status = 'unknown' ORDER BY createdAt DESC")
    fun getUnknownCards(): Flow<List<MemoryCardEntity>>

    /** 全部卡片（按导入时间倒序，用于"总卡数"弹层） */
    @Query("SELECT * FROM memory_cards ORDER BY createdAt DESC")
    fun getAllCards(): Flow<List<MemoryCardEntity>>

    /** 总数统计卡片（记忆库顶栏概览），同口径 julianday 跟列表完全一致 */
    @Query("SELECT COUNT(*) FROM memory_cards WHERE dueAt <= CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER)")
    suspend fun countDueCards(): Int

    @Query("SELECT COUNT(*) FROM memory_cards WHERE status = 'known' AND fsrsState = 2 AND fsrsIntervalDays >= 21")
    suspend fun countMasteredCards(): Int

    @Query("SELECT COUNT(*) FROM memory_cards WHERE status = 'unknown'")
    suspend fun countUnknownCards(): Int

    @Query("SELECT COUNT(*) FROM memory_cards WHERE status = 'semi'")
    suspend fun countSemiCards(): Int

    @Query("SELECT * FROM memory_cards WHERE status = 'semi' ORDER BY createdAt DESC")
    fun getSemiCards(): Flow<List<MemoryCardEntity>>

    @Query("SELECT COUNT(*) FROM memory_cards")
    suspend fun countAllCards(): Int

    @Query("SELECT * FROM memory_cards WHERE id = :id")
    suspend fun getCardById(id: String): MemoryCardEntity?

    /** 查找某本书某段同一文字是否已经建了卡（防重复） */
    @Query("SELECT * FROM memory_cards WHERE sourceType='reader' AND bookId = :bookId AND bookParagraphIndex = :paragraphIndex AND front = :front LIMIT 1")
    suspend fun findReaderCard(bookId: String, paragraphIndex: Int, front: String): MemoryCardEntity?

    /** Phase 3 难度分析：按 status 取所有卡片正面文字（known=已掌握词 / unknown=生词） */
    @Query("SELECT front FROM memory_cards WHERE status = :status")
    suspend fun getFrontsByStatus(status: String): List<String>

    /** 词汇测试：取所有卡片正面（小写建模成已测词集，测试抽样时排除，避免重复建卡） */
    @Query("SELECT DISTINCT LOWER(TRIM(front)) FROM memory_cards")
    suspend fun getAllFrontsLower(): List<String>

    /** 词汇测试：按正面文字（忽略大小写/首尾空格）找卡，存在则更新状态而非重复插卡 */
    @Query("SELECT * FROM memory_cards WHERE LOWER(TRIM(front)) = LOWER(:front) LIMIT 1")
    suspend fun findCardByFront(front: String): MemoryCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: MemoryCardEntity)

    @Update
    suspend fun updateCard(card: MemoryCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCards(cards: List<MemoryCardEntity>)

    @Query("DELETE FROM memory_cards WHERE id = :id")
    suspend fun deleteCard(id: String)

    // ==================== MemoryReviewLogEntity ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: MemoryReviewLogEntity)

    @Query("SELECT * FROM memory_review_logs WHERE cardId = :cardId ORDER BY reviewedAt DESC LIMIT 50")
    suspend fun getLogsByCard(cardId: String): List<MemoryReviewLogEntity>

    /** 某日期区间内的复习次数（日历热力图） */
    @Query("SELECT COUNT(*) FROM memory_review_logs WHERE reviewedAt BETWEEN :startMs AND :endMs")
    suspend fun countReviewsBetween(startMs: Long, endMs: Long): Int

    /** 事务：更新卡调度结果 + 插入一条复习日志（保证要么都成功要么都失败，避免调度落盘、日志丢了） */
    @Transaction
    suspend fun applyReviewResult(
        updatedCard: MemoryCardEntity,
        log: MemoryReviewLogEntity
    ) {
        updateCard(updatedCard)
        insertLog(log)
    }
}

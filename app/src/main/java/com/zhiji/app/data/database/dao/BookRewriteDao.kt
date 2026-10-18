package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhiji.app.data.database.entity.BookRewriteEntity

/**
 * Phase 3 - 中英混排改写持久化缓存 DAO。
 * 每段一行（双模式文本 + 记忆库版本快照 + 替换明细），供全篇后台改写与 600 字实时改写共用。
 */
@Dao
interface BookRewriteDao {

    /** 取整本书全部改写缓存（后台全篇改写 / 失效清理用） */
    @Query("SELECT * FROM book_rewrites WHERE bookId = :bookId")
    suspend fun getByBook(bookId: String): List<BookRewriteEntity>

    /** 取某段区间的改写缓存（600 字一页的读取路径） */
    @Query("SELECT * FROM book_rewrites WHERE bookId = :bookId AND paragraphIndex BETWEEN :startIdx AND :endIdx")
    suspend fun getRange(bookId: String, startIdx: Int, endIdx: Int): List<BookRewriteEntity>

    /** 批量写入/覆盖（REPLACE：记忆库变化后重新生成时覆盖旧缓存） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BookRewriteEntity>)

    /** 书删除时清理该书改写缓存 */
    @Query("DELETE FROM book_rewrites WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    /** 统计某书已缓存段数（判断后台改写是否完成） */
    @Query("SELECT COUNT(*) FROM book_rewrites WHERE bookId = :bookId")
    suspend fun countByBook(bookId: String): Int
}

package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhiji.app.data.database.entity.BookPageCacheEntity

/**
 * 翻页式「整屏分页」结果缓存 DAO（book_pages 表）。
 * 每本书一行，用排版指纹判断缓存是否仍然可用。
 */
@Dao
interface BookPageCacheDao {

    /** 取某本书的分页缓存（无则 null） */
    @Query("SELECT * FROM book_pages WHERE bookId = :bookId LIMIT 1")
    suspend fun get(bookId: String): BookPageCacheEntity?

    /** 写入/覆盖分页缓存 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BookPageCacheEntity)

    /** 书删除时清理分页缓存 */
    @Query("DELETE FROM book_pages WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

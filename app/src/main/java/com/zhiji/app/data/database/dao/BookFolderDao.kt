package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhiji.app.data.database.entity.BookFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookFolderDao {

    /** 某板块下全部文件夹（按名称排序；用于构建分类树） */
    @Query("SELECT * FROM book_folders WHERE board = :board ORDER BY name COLLATE NOCASE ASC")
    fun getFoldersByBoard(board: String): Flow<List<BookFolderEntity>>

    @Query("SELECT * FROM book_folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: String): BookFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: BookFolderEntity)

    @Query("UPDATE book_folders SET name = :name, updatedAt = :ts WHERE id = :id")
    suspend fun rename(id: String, name: String, ts: Long)

    @Query("UPDATE book_folders SET parentId = :parentId, updatedAt = :ts WHERE id = :id")
    suspend fun updateParent(id: String, parentId: String?, ts: Long)

    @Query("DELETE FROM book_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

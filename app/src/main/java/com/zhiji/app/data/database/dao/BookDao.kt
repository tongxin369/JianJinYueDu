package com.zhiji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zhiji.app.data.database.entity.BookAnnotationEntity
import com.zhiji.app.data.database.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    /** v39 按板块过滤书架：smart_reader / multi_format（书架数据完全独立） */
    @Query("SELECT * FROM books WHERE board = :board ORDER BY lastReadAt DESC, createdAt DESC")
    fun getBooksByBoard(board: String): Flow<List<BookEntity>>

    /**
     * 按板块 + 文件夹过滤书架（书籍分类）：
     * folderId = null 表示根目录，SQLite `IS` 可正确匹配 NULL。
     */
    @Query(
        "SELECT * FROM books WHERE board = :board AND folderId IS :folderId " +
            "ORDER BY lastReadAt DESC, createdAt DESC"
    )
    fun getBooksByBoardAndFolder(board: String, folderId: String?): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE board = :board AND folderId = :folderId")
    suspend fun getBooksInFolderOnce(board: String, folderId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookByIdFlow(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE books SET readOffset = :offset, lastReadAt = :ts, updatedAt = :ts WHERE id = :id")
    suspend fun updateProgress(id: String, offset: Int, ts: Long)

    @Query("UPDATE books SET totalChars = :totalChars, updatedAt = :ts WHERE id = :id")
    suspend fun updateTotalChars(id: String, totalChars: Int, ts: Long = System.currentTimeMillis())

    /** 书籍分类：重命名书籍标题 */
    @Query("UPDATE books SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, ts: Long)

    /** 书籍分类：把书籍移动到指定文件夹（folderId = null 表示根目录） */
    @Query("UPDATE books SET folderId = :folderId, updatedAt = :ts WHERE id = :id")
    suspend fun updateFolder(id: String, folderId: String?, ts: Long)

    /** 书籍分类：设置是否显示在书架 */
    @Query("UPDATE books SET shownOnShelf = :shown, updatedAt = :ts WHERE id = :id")
    suspend fun updateShownOnShelf(id: String, shown: Boolean, ts: Long)

    // ---------- Book Annotations (注释/下划线) ----------

    @Query("SELECT * FROM book_annotations WHERE bookId = :bookId ORDER BY paragraphIndex ASC, startInParagraph ASC")
    fun getAnnotationsByBook(bookId: String): Flow<List<BookAnnotationEntity>>

    @Query("SELECT * FROM book_annotations WHERE id = :id LIMIT 1")
    suspend fun getAnnotationById(id: String): BookAnnotationEntity?

    /** 按段号 + 段内字符坐标：命中 (start <= charPos < end) 区间的注释（用于点击下划线弹窗） */
    @Query(
        """
        SELECT * FROM book_annotations
        WHERE bookId = :bookId AND paragraphIndex = :paragraphIndex
          AND startInParagraph <= :charPos AND endInParagraph > :charPos
        LIMIT 1
        """
    )
    suspend fun findAnnotationAtPosition(
        bookId: String,
        paragraphIndex: Int,
        charPos: Int
    ): BookAnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(annotation: BookAnnotationEntity)

    @Query("DELETE FROM book_annotations WHERE id = :id")
    suspend fun deleteAnnotationById(id: String)

    @Query("DELETE FROM book_annotations WHERE bookId = :bookId")
    suspend fun deleteAnnotationsByBook(bookId: String)
}

package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书架分类文件夹（书籍分类树）。
 *
 * 与 BookEntity.folderId 组成「文件夹 - 书籍」树形结构：
 *   - parentId = null 表示根目录下的顶层文件夹
 *   - 书籍通过 folderId 归属到某个文件夹，folderId = null 表示位于根目录
 *
 * board 与书架板块对齐（smart_reader / multi_format），保证分类树与书架数据同域。
 */
@Entity(tableName = "book_folders")
data class BookFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 父文件夹 id；null = 根目录 */
    val parentId: String? = null,
    /** 所属板块：smart_reader / multi_format */
    val board: String = "smart_reader",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

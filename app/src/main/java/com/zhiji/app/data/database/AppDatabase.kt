package com.zhiji.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhiji.app.data.database.dao.BookDao
import com.zhiji.app.data.database.dao.BookFolderDao
import com.zhiji.app.data.database.dao.BookPageCacheDao
import com.zhiji.app.data.database.dao.BookRewriteDao
import com.zhiji.app.data.database.dao.MemoryDao
import com.zhiji.app.data.database.dao.VocabHistoryDao
import com.zhiji.app.data.database.dao.VocabProficiencyDao
import com.zhiji.app.data.database.entity.BookAnnotationEntity
import com.zhiji.app.data.database.entity.BookEntity
import com.zhiji.app.data.database.entity.BookFolderEntity
import com.zhiji.app.data.database.entity.BookPageCacheEntity
import com.zhiji.app.data.database.entity.BookRewriteEntity
import com.zhiji.app.data.database.entity.MemoryCardEntity
import com.zhiji.app.data.database.entity.MemoryReviewLogEntity
import com.zhiji.app.data.database.entity.VocabHistoryEntity
import com.zhiji.app.data.database.entity.VocabProficiencyEntity

/**
 * 「渐进阅读」独立数据库（仅智能阅读器 + 记忆库相关表）。
 *
 * 作为独立 App，本库从 version = 1 全新起步，不再承载旧「智集」的历史迁移链。
 * 表集合：
 *   - books / book_annotations：书架与书籍注释
 *   - book_rewrites / book_pages：中英混排改写缓存 + 翻页分页缓存
 *   - memory_cards / memory_review_logs：记忆库卡片与复习历史
 *   - vocab_proficiencies / vocab_history：词汇掌握度结果与成长曲线
 */
@Database(
    entities = [
        BookEntity::class,
        BookFolderEntity::class,
        BookRewriteEntity::class,
        BookPageCacheEntity::class,
        MemoryCardEntity::class,
        MemoryReviewLogEntity::class,
        BookAnnotationEntity::class,
        VocabProficiencyEntity::class,
        VocabHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    /** 书架分类文件夹 DAO（book_folders 表） */
    abstract fun bookFolderDao(): BookFolderDao
    /** Phase 3 中英混排改写缓存 DAO（book_rewrites 表） */
    abstract fun bookRewriteDao(): BookRewriteDao
    /** 翻页式整屏分页结果缓存 DAO（book_pages 表，大文件免重复分页） */
    abstract fun bookPageCacheDao(): BookPageCacheDao
    /** Phase 2 记忆库：卡片 + 复习历史 */
    abstract fun memoryDao(): MemoryDao
    /** 词汇掌握度测试：当前 CEFR 水平结果（单行） */
    abstract fun vocabProficiencyDao(): VocabProficiencyDao
    /** 词汇掌握度测试：历史记录（成长曲线） */
    abstract fun vocabHistoryDao(): VocabHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2：新增书籍分类文件夹表 book_folders + 书籍 folderId 字段。
         * 用增量迁移保留用户已有数据（书籍/记忆库），不整库重建。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN folderId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_folders` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `parentId` TEXT,
                        `board` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3：新增 books.shownOnShelf（是否显示在书架，默认 1 = 显示） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN shownOnShelf INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zhiji.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

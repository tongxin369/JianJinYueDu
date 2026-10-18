package com.zhiji.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zhiji.app.data.database.dao.BookDao
import com.zhiji.app.data.database.dao.BookPageCacheDao
import com.zhiji.app.data.database.dao.BookRewriteDao
import com.zhiji.app.data.database.dao.MemoryDao
import com.zhiji.app.data.database.dao.VocabHistoryDao
import com.zhiji.app.data.database.dao.VocabProficiencyDao
import com.zhiji.app.data.database.entity.BookAnnotationEntity
import com.zhiji.app.data.database.entity.BookEntity
import com.zhiji.app.data.database.entity.BookPageCacheEntity
import com.zhiji.app.data.database.entity.BookRewriteEntity
import com.zhiji.app.data.database.entity.MemoryCardEntity
import com.zhiji.app.data.database.entity.MemoryReviewLogEntity
import com.zhiji.app.data.database.entity.VocabHistoryEntity
import com.zhiji.app.data.database.entity.VocabProficiencyEntity

/**
 * 「阅见」独立数据库（仅智能阅读器 + 记忆库相关表）。
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
        BookRewriteEntity::class,
        BookPageCacheEntity::class,
        MemoryCardEntity::class,
        MemoryReviewLogEntity::class,
        BookAnnotationEntity::class,
        VocabProficiencyEntity::class,
        VocabHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zhiji.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

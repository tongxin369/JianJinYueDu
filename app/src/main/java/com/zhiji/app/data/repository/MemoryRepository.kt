package com.zhiji.app.data.repository

import com.zhiji.app.core.algorithm.FsrsScheduler
import com.zhiji.app.core.algorithm.FsrsScheduler.FsrsResult
import com.zhiji.app.data.database.dao.MemoryDao
import com.zhiji.app.data.database.entity.MemoryCardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Phase 2 记忆库仓库：
 *   MemoryDao（底层 CRUD） + FsrsScheduler（算法层）组合对外暴露简洁 API。
 *
 * UI 层（记忆库三张 Tab / 阅读器双标记 Sheet）应只依赖本 Repository，
 * 不要直接调 Dao 或 Scheduler，保持依赖单向。
 */
class MemoryRepository(
    private val dao: MemoryDao
) {
    /** 卡片数据变化回调（建卡/删卡/改状态后触发）：由宿主接入"词库掌握度重算"等联动 */
    var onCardsChanged: (() -> Unit)? = null

    // ---- 记忆库版本号：卡片变化即递增，供 Phase 3 改写缓存失效判断 ----
    private val versionCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val _versionFlow = MutableStateFlow(0L)
    /** 当前记忆库版本（全篇改写缓存按此快照生成，不一致即视为过期） */
    val versionFlow: StateFlow<Long> = _versionFlow.asStateFlow()
    fun currentVersion(): Long = _versionFlow.value

    /** 卡片数据变化统一出口：版本递增 + 通知宿主 */
    private fun notifyCardsChanged() {
        _versionFlow.value = versionCounter.incrementAndGet()
        onCardsChanged?.invoke()
    }

    val dueCards: Flow<List<MemoryCardEntity>> = dao.getDueCards()
    val masteredCards: Flow<List<MemoryCardEntity>> = dao.getMasteredCards()
    val unknownCards: Flow<List<MemoryCardEntity>> = dao.getUnknownCards()
    val semiCards: Flow<List<MemoryCardEntity>> = dao.getSemiCards()
    val allCards: Flow<List<MemoryCardEntity>> = dao.getAllCards()

    // ---- 统计（记忆库顶栏展示） ----
    suspend fun countDue(): Int = dao.countDueCards()
    suspend fun countMastered(): Int = dao.countMasteredCards()
    suspend fun countUnknown(): Int = dao.countUnknownCards()
    suspend fun countSemi(): Int = dao.countSemiCards()
    suspend fun countAll(): Int = dao.countAllCards()
    // ---- 阅读器双标记入口（建卡，无 UI） ----
    /**
     * 从阅读器长按选文字创建卡片（「会的/不会的」Sheet 调用）。
     * 同一段同一文字已建过卡则跳过不重复插入，返回原有实体。
     *
     * @return 新建或已存在的 MemoryCardEntity ；插入失败返回 null。
     */
    /**
     * 从阅读器长按选文字创建卡片（「会的/不熟/不会的」调用）。
     * 防重策略：
     *  - 先检查**同一文字（忽略大小写/空格）是否已存在任何卡片**，若有 → 返回原卡片（提示已存在）
     *  - 再检查同本书 + 同段 + 同文字是否已建卡，若有 → 返回原卡片
     *  - 都没有 → 新建卡片
     *
     * @return 新建或已存在的 MemoryCardEntity；插入失败返回 null
     */
    suspend fun createCardFromReader(
        bookId: String,
        bookTitle: String,
        paragraphIndex: Int,
        charOffsetStart: Int,
        charOffsetEnd: Int,
        selectedText: String,
        /** "known"=✅会的；"semi"=⚠️不熟；"unknown"=❓不会的 */
        status: String
    ): MemoryCardEntity? = withContext(Dispatchers.IO) {
        if (selectedText.isBlank()) return@withContext null
        val trimmed = selectedText.trim()

        // 先按文字全局去重（优先级更高：同一单词/短语不管哪本书选的，只建一张卡）
        val existingByFront = runCatching { dao.findCardByFront(trimmed) }.getOrNull()
        if (existingByFront != null) return@withContext existingByFront

        // 再按阅读器来源去重（兼容旧逻辑）
        val existingByReader = runCatching {
            dao.findReaderCard(bookId, paragraphIndex, trimmed)
        }.getOrNull()
        if (existingByReader != null) return@withContext existingByReader

        val card = FsrsScheduler.createNewCard(
            id = UUID.randomUUID().toString(),
            front = trimmed,
            back = "",
            status = status,
            sourceType = "reader",
            bookId = bookId,
            bookTitle = bookTitle,
            bookParagraphIndex = paragraphIndex,
            bookCharOffsetStart = charOffsetStart,
            bookCharOffsetEnd = charOffsetEnd,
            cardType = if (trimmed.codePointCount(0, trimmed.length) <= 8) "word" else "paragraph"
        )
        runCatching { dao.insertCard(card) }.getOrElse { return@withContext null }
        notifyCardsChanged()
        card
    }

    /** 按卡片正面文字查已存在的卡片（记忆库去重用） */
    suspend fun findCardByFront(front: String): MemoryCardEntity? = withContext(Dispatchers.IO) {
        runCatching { dao.findCardByFront(front.trim()) }.getOrNull()
    }

    /**
     * 阅读界面「不会 / 不熟 / 熟悉」三状态选择：把某个英文词的掌握状态写回记忆库。
     *  - 已有该词卡片 → 直接改 status（并通知记忆库版本变化，改写缓存随之按新难度重算）
     *  - 尚无该词卡片 → 新建一张该状态的卡片（来源 manual）
     * @param front  单词（改写后正文里出现的英文词）
     * @param status "known"=熟悉 / "semi"=不熟 / "unknown"=不会
     * @return 写入后的卡片；失败返回 null
     */
    suspend fun upsertWordStatus(front: String, status: String): MemoryCardEntity? = withContext(Dispatchers.IO) {
        val trimmed = front.trim()
        if (trimmed.isEmpty()) return@withContext null
        val safeStatus = if (status in setOf("known", "semi", "unknown")) status else "unknown"
        val existing = runCatching { dao.findCardByFront(trimmed) }.getOrNull()
        if (existing != null) {
            if (existing.status == safeStatus) return@withContext existing
            val updated = existing.copy(status = safeStatus, updatedAt = System.currentTimeMillis())
            runCatching { dao.updateCard(updated) }.getOrElse { return@withContext null }
            notifyCardsChanged()
            return@withContext updated
        }
        val card = FsrsScheduler.createNewCard(
            id = UUID.randomUUID().toString(),
            front = trimmed,
            back = "",
            status = safeStatus,
            sourceType = "manual",
            cardType = if (trimmed.codePointCount(0, trimmed.length) <= 8) "word" else "paragraph"
        )
        runCatching { dao.insertCard(card) }.getOrElse { return@withContext null }
        notifyCardsChanged()
        card
    }

    suspend fun insertManualCard(front: String, back: String, status: String): MemoryCardEntity = withContext(Dispatchers.IO) {
        val card = FsrsScheduler.createNewCard(
            id = UUID.randomUUID().toString(),
            front = front,
            back = back,
            status = status,
            sourceType = "manual"
        )
        dao.insertCard(card)
        notifyCardsChanged()
        card
    }

    suspend fun updateCard(card: MemoryCardEntity) {
        dao.updateCard(card)
        notifyCardsChanged()
    }
    suspend fun getCard(id: String): MemoryCardEntity? = dao.getCardById(id)
    suspend fun deleteCard(id: String) {
        dao.deleteCard(id)
        notifyCardsChanged()
    }

    // ---- 复习调度（记忆库界面 "Again/Hard/Good/Easy" 按钮调用） ----
    /**
     * 打分并更新卡片 + 写复习日志（事务化，要么都落盘要么都不落）。
     * 返回 FsrsResult 供 UI 展示"间隔 N 天后再见"等文案。
     */
    suspend fun applyReview(cardId: String, @androidx.annotation.IntRange(from = 1, to = 4) rating: Int): FsrsResult? {
        val card = dao.getCardById(cardId) ?: return null
        val result = FsrsScheduler.schedule(card, rating)
        dao.applyReviewResult(result.updatedCard, result.reviewLog)
        return result
    }

    // ---- Phase 3 中英混排改写词集（8:1:1 引擎使用） ----
    /** 「会的」词集合（status='known' 卡片正面，小写）——改写主力：遇到即全改 */
    suspend fun getKnownWords(): Set<String> = withContext(Dispatchers.IO) {
        dao.getFrontsByStatus("known")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()
    }

    /** 「不熟的」词集合（status='semi' 卡片正面，小写）——8:1:1 中的 1 成渐进挑战 */
    suspend fun getSemiWords(): Set<String> = withContext(Dispatchers.IO) {
        dao.getFrontsByStatus("semi")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()
    }

    /** 「不会的」词集合（status='unknown' 卡片正面，小写）——8:1:1 中的 1 成新内容挑战 */
    suspend fun getUnknownWords(): Set<String> = withContext(Dispatchers.IO) {
        dao.getFrontsByStatus("unknown")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()
    }

    /** 记忆库中已存在的全部词（小写集合）——改写「按等级注入未测试新词」时排除已入卡词 */
    suspend fun getAllFronts(): Set<String> = withContext(Dispatchers.IO) {
        dao.getAllFrontsLower()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()
    }
}

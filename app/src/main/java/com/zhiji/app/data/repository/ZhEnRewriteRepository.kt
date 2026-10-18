package com.zhiji.app.data.repository

import android.util.Log
import com.google.gson.Gson
import com.zhiji.app.core.algorithm.VocabGrader
import com.zhiji.app.core.algorithm.ZhEnRewriter
import com.zhiji.app.data.database.dao.BookPageCacheDao
import com.zhiji.app.data.database.dao.BookRewriteDao
import com.zhiji.app.data.database.dao.VocabProficiencyDao
import com.zhiji.app.data.database.entity.BookPageCacheEntity
import com.zhiji.app.data.database.entity.BookRewriteEntity
import com.zhiji.app.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 3 中英混排改写仓库：
 *  - 中文词 → 英文候选 反向索引（来自 ECDICT）懒加载
 *  - 结合记忆库 known/semi/unknown 词集做 8:1:1 难易控制
 *  - 「不会的」第二来源：按用户当前 CEFR 等级词频区间注入未加入记忆库的新词（每 100 字 ≤3%）
 *  - 「一页 ≈ 600 字」分页改写：给定起始段，累计字符数到目标即为一页
 *  - 持久化缓存：每段双模式文本（替换式/对照式）+ 记忆库版本快照 + 替换明细落库，
 *    书打开后后台全篇生成 → 切换页/切换模式秒开；记忆库变化（版本递增）后自动失效重写。
 */
class ZhEnRewriteRepository(
    private val ecdict: EcdictRepository,
    private val memoryRepository: MemoryRepository,
    private val rewriteDao: BookRewriteDao,
    private val pageCacheDao: BookPageCacheDao,
    private val vocabProficiencyDao: VocabProficiencyDao,
    private val settings: SettingsDataStore
) {

    /** 每页目标字符数（「自动改写一页」的单位） */
    companion object {
        const val PAGE_TARGET_CHARS = 600
        /** 注入候选池大小：取当前等级词频区间内词频最靠前的一批未测试词 */
        const val INJECT_POOL_LIMIT = 400
        /** 全篇后台改写：每批落库的段落数（控制事务粒度与内存占用） */
        private const val BATCH_SIZE = 50
        /** 全篇后台改写的段落数上限：超过则跳过（大文件按页懒改写，避免打开时卡住） */
        const val FULL_REWRITE_MAX_PARAGRAPHS = 2000
        /**
         * 改写引擎版本：算法或产物格式变化时递增（如本轮新增英文词间空格、输出位置偏移），
         * 会体现在缓存版本里 → 旧持久化改写缓存自动作废重写，无需清库。
         */
        private const val ENGINE_VERSION = 3L
        private const val TAG = "ZhEnRewrite"
    }

    private val gson = Gson()

    // 懒加载缓存（进程级）
    private var indexReady = false
    private var zhWords: List<String> = emptyList()
    private var zhToEn: Map<String, List<String>> = emptyMap()

    /** 确保反向索引可用（首次调用构建，幂等） */
    private suspend fun ensureIndex() = withContext(Dispatchers.IO) {
        if (indexReady) return@withContext
        runCatching { ecdict.ensureImported() }
        if (!ecdict.isReady()) return@withContext
        zhToEn = ecdict.zhEnIndex()
        zhWords = ecdict.zhWordsByLengthDesc()
        indexReady = true
        Log.i(TAG, "ensureIndex ready → 中文词 ${zhWords.size} 个")
    }

    /**
     * 当前改写缓存版本：把「记忆库版本 / CEFR 等级索引 / 难度微调三值 / 引擎版本」位域打包成一个 Long。
     * 任一分量变化都会改变版本 → 持久化改写缓存自动失效重写。
     * 位域布局（各段取值均远小于位宽，组合为单射）：mem(29bit) | level(3) | semi(5) | unknown(4) | inject(4) | engine(3)
     */
    suspend fun currentMemoryVersion(): Long = withContext(Dispatchers.IO) {
        val mem = memoryRepository.currentVersion()
        val level = runCatching {
            vocabProficiencyDao.current()?.let { VocabGrader.levelFromCode(it.cefr) }
        }.getOrNull() ?: VocabGrader.START_INDEX
        val t = settings.getRewriteTuning()
        ((mem and 0x1FFFFFFFL) shl 19) or
            ((level.toLong() and 0x7L) shl 16) or
            ((t.semiPer100.toLong() and 0x1FL) shl 11) or
            ((t.unknownPer100.toLong() and 0xFL) shl 7) or
            ((t.injectPer100.toLong() and 0xFL) shl 3) or
            (ENGINE_VERSION and 0x7L)
    }

    /**
     * 一次改写所需的全部上下文：反向索引 + 记忆库三分类 + 注入池 + 难度档位 + 缓存版本。
     * 一本书 / 一页只构建一次，逐段复用它 → 避免在大文件里对每段重复查 DB（原实现每段 4+ 次查询）。
     * @return null 表示词典反向索引不可用
     */
    data class RewriteContext(
        val version: Long,
        /** 用户微调的难度三值（每 100 字窗口内「不熟 / 不会 / 新增词」允许出现个数） */
        val tuning: ZhEnRewriter.Density.Tuning,
        val known: Set<String>,
        val semi: Set<String>,
        val unknown: Set<String>,
        /** 注入新词池：按词频升序（越常用越靠前），改写时优先挑词频最高的候选 */
        val injectWords: List<String>
    )

    /** 构建改写上下文（索引不可用时返回 null） */
    suspend fun buildContext(): RewriteContext? = withContext(Dispatchers.IO) {
        ensureIndex()
        if (!indexReady || zhWords.isEmpty()) return@withContext null
        val version = currentMemoryVersion()
        RewriteContext(
            version = version,
            tuning = settings.getRewriteTuning(),
            known = memoryRepository.getKnownWords(),
            semi = memoryRepository.getSemiWords(),
            unknown = memoryRepository.getUnknownWords(),
            injectWords = currentInjectWords(version)
        )
    }

    // ---- 注入新词池（「不会的」改写的新词来源）----
    // 进程内缓存：缓存版本变化（记忆库/等级/难度/引擎任一变化）时重建
    private var injectCacheVersion: Long = -1
    private var injectCacheWords: List<String> = emptyList()

    /**
     * 构建/复用注入词池：当前等级词频区间内的单字英文词，**按词频升序**（越常用越靠前）。
     * 新增词依据两条标准：
     *  ① 按用户当前评定的 CEFR 等级取词频区间（[VocabGrader.rankRangeForTier]）；
     *  ② 按词频排序（词频 = 重要度）从区间内最常用的词开始依序加入。
     * 不再按记忆库标签（会的/不熟/不会）过滤：候选命中记忆库标签词时已由 pickByCategory 优先处理，
     * 注入只负责在候选里没有标签词时补足新词；且保持顺序让「越常用越先出现」真正生效。
     */
    private suspend fun currentInjectWords(version: Long): List<String> = withContext(Dispatchers.IO) {
        if (injectCacheVersion == version && injectCacheWords.isNotEmpty()) return@withContext injectCacheWords
        runCatching {
            val prof = vocabProficiencyDao.current()
            if (prof == null) {
                injectCacheVersion = version
                injectCacheWords = emptyList()
                return@runCatching emptyList<String>()
            }
            val level = VocabGrader.levelFromCode(prof.cefr)
            val rankRange = VocabGrader.rankRangeForTier(level)
            // fetchWordsByRankWindow 已按词频排名升序返回；lowercase + distinct 保持词频顺序
            val pool = ecdict.fetchWordsByRankWindow(rankRange.first, rankRange.last, INJECT_POOL_LIMIT)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
            injectCacheVersion = version
            injectCacheWords = pool
            Log.i(TAG, "注入池就绪：等级=${prof.cefr}($level) 排名${rankRange.first}~${rankRange.last} 词频升序 ${pool.size} 个")
            pool
        }.getOrDefault(emptyList())
    }

    /**
     * 计算一页的段落范围：从 startIdx 起累计字符数，达到 targetChars（默认600）为止。
     * 至少包含 1 段；若 startIdx 越界返回空区间。
     */
    fun pageRange(paragraphs: List<String>, startIdx: Int, targetChars: Int = PAGE_TARGET_CHARS): IntRange {
        if (paragraphs.isEmpty()) return IntRange.EMPTY
        val start = startIdx.coerceIn(0, paragraphs.size - 1)
        var acc = 0
        var end = start
        for (i in start until paragraphs.size) {
            acc += paragraphs[i].length
            end = i
            if (acc >= targetChars) break
        }
        return start..end
    }

    /** 序列化一段的替换明细（统计弹窗用） */
    private fun wordsToJson(words: List<ZhEnRewriter.Replacement>): String =
        if (words.isEmpty()) "" else gson.toJson(words)

    @Suppress("UNCHECKED_CAST")
    private fun jsonToWords(json: String): List<ZhEnRewriter.Replacement> {
        if (json.isBlank()) return emptyList()
        return runCatching { gson.fromJson(json, Array<ZhEnRewriter.Replacement>::class.java).toList() }
            .getOrDefault(emptyList())
    }

    /**
     * 改写一段中文文本为中英混排，一次计算两种模式：
     *  - replace  替换式：中文词 → 英文
     *  - annotate 对照式：中文词 → 中文(英文)
     * @param ctx 由 [buildContext] 构建的上下文（记忆库词集/注入池/难度档位复用，不再逐段查库）
     * @return null 表示索引不可用或文本为空
     */
    private suspend fun computeBoth(
        text: String,
        ctx: RewriteContext
    ): ZhEnRewriter.RewriteResult? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (!indexReady || zhWords.isEmpty()) return@withContext null
        ZhEnRewriter().rewriteParagraph(
            text = text,
            zhWords = zhWords,
            zhToEn = zhToEn,
            known = ctx.known,
            semi = ctx.semi,
            unknown = ctx.unknown,
            semiRatio = ctx.tuning.semiRatio,
            unknownRatio = ctx.tuning.unknownRatio,
            injectWords = ctx.injectWords,
            injectRatio = ctx.tuning.injectRatio
        )
    }

    /**
     * 全篇后台改写：逐段计算双模式文本并落库（无 UI 阻塞，配合批量写入）。
     * 段落数超过 [FULL_REWRITE_MAX_PARAGRAPHS] 的大文件直接跳过（返回 0），
     * 改为翻到哪页就改哪页（[loadPage] 即时计算并回写），避免打开大书时长时间占用 CPU。
     * @return 成功生成的段数
     */
    suspend fun rewriteBook(bookId: String, paragraphs: List<String>): Int = withContext(Dispatchers.IO) {
        if (paragraphs.size > FULL_REWRITE_MAX_PARAGRAPHS) {
            Log.i(TAG, "rewriteBook book=$bookId 跳过全篇改写（${paragraphs.size} 段 > 上限 $FULL_REWRITE_MAX_PARAGRAPHS，改按页懒改写）")
            return@withContext 0
        }
        val ctx = buildContext() ?: return@withContext 0
        var done = 0
        val batch = ArrayList<BookRewriteEntity>(BATCH_SIZE)
        paragraphs.forEachIndexed { idx, para ->
            if (para.isBlank()) return@forEachIndexed
            val r = computeBoth(para, ctx) ?: return@forEachIndexed
            if (r.replacedCount > 0) {
                batch += BookRewriteEntity(
                    bookId = bookId,
                    paragraphIndex = idx,
                    replace = r.rewritten,
                    annotate = r.annotated,
                    memoryVersion = ctx.version,
                    wordsJson = wordsToJson(r.replacements)
                )
                done++
            }
            if (batch.size >= BATCH_SIZE) {
                rewriteDao.upsertAll(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) rewriteDao.upsertAll(batch)
        Log.i(TAG, "rewriteBook book=$bookId 完成 $done 段（version=${ctx.version} ${ctx.tuning.compactLabel}）")
        done
    }

    /** 一页改写的聚合结果：改写文本 + 全页统计（替换数 / 会的 / 不熟的 / 不会的）+ 单词明细。 */
    data class PageRewrite(
        val paragraphs: Map<Int, String>,
        val replaced: Int,
        val known: Int,
        val semi: Int,
        val unknown: Int,
        /** 本页全部替换明细（zh/en/category），供统计按钮点开查看具体单词 */
        val words: List<ZhEnRewriter.Replacement>,
        /**
         * 本页「全部」替换明细（不去重）——供点击英文词命中用。
         * 去重后的 [words] 只保留每个英文词首次出现的偏移，同页重复出现的同一英文词会点不中，
         * 因此单击命中必须用这份全量明细。
         */
        val allWords: List<ZhEnRewriter.Replacement>
    )

    /**
     * 组装一页：优先读持久化缓存（秒开），缺/过期段落即时计算并回写。
     * 缓存失效条件：memoryVersion 与当前版本不一致（记忆库卡片变化、等级变化、难度档位变化、引擎升级）。
     * 未产生替换（全无中文可替换词）的段保留原文，避免空改。
     */
    suspend fun loadPage(
        bookId: String,
        paragraphs: List<String>,
        range: IntRange,
        mode: String
    ): PageRewrite? = withContext(Dispatchers.IO) {
        if (range.isEmpty()) return@withContext null
        val ctx = buildContext() ?: return@withContext null
        val cached = rewriteDao.getRange(bookId, range.first, range.last)
            .associateBy { it.paragraphIndex }
        val resultMap = HashMap<Int, String>()
        val words = mutableListOf<ZhEnRewriter.Replacement>()
        val toUpsert = mutableListOf<BookRewriteEntity>()
        range.forEach { idx ->
            val para = paragraphs.getOrNull(idx) ?: return@forEach
            if (para.isBlank()) return@forEach
            val row = cached[idx]
            if (row != null && row.memoryVersion == ctx.version) {
                // 命中缓存：直接取当前模式文本 + 反序列化替换明细（回填段索引供点词命中）
                resultMap[idx] = if (mode == "annotate") row.annotate else row.replace
                words += jsonToWords(row.wordsJson).map { it.copy(paragraphIndex = idx) }
            } else {
                // 缓存缺失/过期：即时计算并回写
                val r = computeBoth(para, ctx) ?: return@forEach
                if (r.replacedCount > 0) {
                    resultMap[idx] = if (mode == "annotate") r.annotated else r.rewritten
                    words += r.replacements.map { it.copy(paragraphIndex = idx) }
                    toUpsert += BookRewriteEntity(
                        bookId = bookId,
                        paragraphIndex = idx,
                        replace = r.rewritten,
                        annotate = r.annotated,
                        memoryVersion = ctx.version,
                        wordsJson = wordsToJson(r.replacements)
                    )
                }
            }
        }
        if (toUpsert.isNotEmpty()) rewriteDao.upsertAll(toUpsert)
        // 统计去重：同一张记忆卡片（英文词）在一页内重复出现只统计一次，重复统计无意义
        val seen = HashSet<String>()
        val uniqueWords = words.filter { seen.add(it.en.lowercase()) }
        val replaced = uniqueWords.size
        val known = uniqueWords.count { it.category == "known" }
        val semi = uniqueWords.count { it.category == "semi" }
        val unknown = uniqueWords.count { it.category == "unknown" }
        Log.i(TAG, "loadPage [$range] → 改写 ${resultMap.size} 段 / 替换 $replaced（会 $known · 不熟 $semi · 不会 $unknown）")
        PageRewrite(resultMap, replaced, known, semi, unknown, uniqueWords, words)
    }

    /** 整篇文章按 600 字一页可分多少页（用于「第 N / M 页」指示） */
    fun pageCount(paragraphs: List<String>, targetChars: Int = PAGE_TARGET_CHARS): Int {
        if (paragraphs.isEmpty()) return 0
        var count = 0
        var i = 0
        while (i < paragraphs.size) {
            count++
            i = pageRange(paragraphs, i, targetChars).last + 1
        }
        return count
    }

    /** 起始段落在第几页（1 起） */
    fun pageIndexOf(paragraphs: List<String>, startIdx: Int, targetChars: Int = PAGE_TARGET_CHARS): Int {
        if (paragraphs.isEmpty()) return 1
        var page = 1
        var i = 0
        while (i < paragraphs.size) {
            val r = pageRange(paragraphs, i, targetChars)
            if (startIdx in r) return page
            page++
            i = r.last + 1
        }
        return page
    }

    /** 删除某本书的全部改写缓存（删书时调用，避免残留数据占空间） */
    suspend fun deleteBookRewrites(bookId: String) = withContext(Dispatchers.IO) {
        runCatching { rewriteDao.deleteByBook(bookId) }
        runCatching { pageCacheDao.deleteByBook(bookId) }
    }

    // ============================================================
    // 翻页式「整屏分页」结果持久化：大文件分页后保存住，再次打开免重新分页
    // ============================================================

    /**
     * 读取分页缓存：仅当排版指纹与当前一致时命中（换字体/换机型宽度/书内容变化 → 失效重算）。
     * @return 命中返回分页区间表；未命中/无缓存返回 null
     */
    suspend fun loadCachedPages(bookId: String, signature: String): List<IntRange>? = withContext(Dispatchers.IO) {
        val row = runCatching { pageCacheDao.get(bookId) }.getOrNull() ?: return@withContext null
        if (row.signature != signature) return@withContext null
        runCatching { decodePages(row.pagesJson) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 读取分页缓存（宽松命中）：仅比对排版指纹的尾部三段（可用宽度:高度:字体缩放），
     * 忽略「段落数:总字数」。用于「快速首屏」阶段——此时只加载了开头若干段落，
     * 段落数/总字数与全文不一致 → 严格指纹必然不匹配；宽松命中让「已分页过的书」
     * 在全文到齐前就能拿回整部分页表 → 首屏立即渲染、且无需重新测量分页。
     */
    suspend fun loadCachedPagesLoose(bookId: String, layoutKey: String): List<IntRange>? = withContext(Dispatchers.IO) {
        if (layoutKey.isBlank()) return@withContext null
        val row = runCatching { pageCacheDao.get(bookId) }.getOrNull() ?: return@withContext null
        if (layoutKeyOfSignature(row.signature) != layoutKey) return@withContext null
        runCatching { decodePages(row.pagesJson) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 严格指纹的排版段 = 末三段（可用宽度:高度:字体缩放）；指纹格式 "段落数:总字数:宽:高:字号" */
    private fun layoutKeyOfSignature(signature: String): String {
        val parts = signature.split(':')
        return if (parts.size >= 3) parts.takeLast(3).joinToString(":") else ""
    }

    /** 保存分页结果（带排版指纹，供下次打开命中） */
    suspend fun savePages(bookId: String, signature: String, pages: List<IntRange>) = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext
        runCatching {
            pageCacheDao.upsert(
                BookPageCacheEntity(
                    bookId = bookId,
                    signature = signature,
                    pagesJson = encodePages(pages)
                )
            )
        }
    }

    /** 分页区间编码："first:last,..."（紧凑，避免 gson 开销） */
    private fun encodePages(pages: List<IntRange>): String =
        pages.joinToString(",") { "${it.first}:${it.last}" }

    private fun decodePages(json: String): List<IntRange> {
        if (json.isBlank()) return emptyList()
        return json.split(',')
            .mapNotNull { seg ->
                val p = seg.split(':')
                if (p.size != 2) return@mapNotNull null
                val a = p[0].toIntOrNull() ?: return@mapNotNull null
                val b = p[1].toIntOrNull() ?: return@mapNotNull null
                a..b
            }
    }
}

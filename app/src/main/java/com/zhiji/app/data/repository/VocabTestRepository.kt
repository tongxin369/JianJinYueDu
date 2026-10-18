package com.zhiji.app.data.repository

import com.zhiji.app.core.algorithm.FsrsScheduler
import com.zhiji.app.core.algorithm.VocabGrader
import com.zhiji.app.core.algorithm.VocabTier
import com.zhiji.app.data.database.dao.MemoryDao
import com.zhiji.app.data.database.dao.VocabHistoryDao
import com.zhiji.app.data.database.dao.VocabProficiencyDao
import com.zhiji.app.data.database.entity.MemoryCardEntity
import com.zhiji.app.data.database.entity.VocabHistoryEntity
import com.zhiji.app.data.database.entity.VocabProficiencyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID

/**
 * 词汇掌握度测试仓库：自适应诊断 + 实时写入记忆库 + 等级/历史持久化。
 *
 * 一条测试 = 一轮自适应阶梯（从推荐/自选等级起步，逐级抽 5 词自评认识/不认识），
 * 每题回答的瞬间就 `known/unknown` 写入记忆库（sourceType=test, cardType=word）；
 * 结束再持久化当前等级（单行）与一条历史记录（成长曲线用）。
 *
 * 分级数据源：ECDICT 按词频排序 → rowid 位置映射可测等级（A0~C2，见 [VocabGrader]）。
 */
class VocabTestRepository(
    private val ecdict: EcdictRepository,
    private val memoryDao: MemoryDao,
    private val proficiencyDao: VocabProficiencyDao,
    private val historyDao: VocabHistoryDao
) {

    /** 当前掌握度汇总（单行 Flow，仪盘表实时反映） */
    val proficiency: Flow<VocabProficiencyEntity?> = proficiencyDao.flow()

    /** 历史记录（时间升序，成长曲线数据源） */
    val history: Flow<List<VocabHistoryEntity>> = historyDao.flowAll()

    /** 最近 n 条历史（时间升序） */
    suspend fun recentHistory(n: Int): List<VocabHistoryEntity> = historyDao.recent(n)

    /** 最新掌握度快照（结果页/仪表盘取实时值） */
    suspend fun currentSnapshot(): VocabProficiencyEntity? = proficiencyDao.current()

    /**
     * 按"词库掌握比例"刷新等级评定并**返回最新等级实体**：
     *  - 等级档位(CEFR 层)沿用传入/现存实体，不变；
     *  - 细分数按 当前等级词频排名区间内 (已知词×1 + 半熟词×0.5) / 区间总词数 的比例映射为三位小数(0.000~0.999)。
     *  - 无现存记录但有已掌握词 → 取已知词最靠前的等级作为起点定位。
     *
     * 记忆库卡片任何变化（建卡/删卡/改状态）都应触发本方法，让等级随平时积累实时在仪表盘动起来
     * ——即使不抽卡测试，只要你知道/半熟了许多词，当前等级的进度就会往前挪。
     *
     * @return 重算后持久化的最新等级实体；词典未就绪或暂无任何已知/半熟词时返回 null。
     */
    suspend fun refreshGradeFromMastery(): VocabProficiencyEntity? = withContext(Dispatchers.IO) {
        if (!ecdict.isReady()) return@withContext null
        val knownFronts = runCatching { memoryDao.getFrontsByStatus("known") }.getOrNull() ?: return@withContext null
        val semiFronts = runCatching { memoryDao.getFrontsByStatus("semi") }.getOrNull() ?: emptyList()
        if (knownFronts.isEmpty() && semiFronts.isEmpty()) return@withContext null
        val knownRanks = ecdict.findRanks(knownFronts)
        val semiRanks = ecdict.findRanks(semiFronts)

        var current = proficiencyDao.current()
        if (current == null) {
            val seedRank = (knownRanks.values + semiRanks.values).minOrNull() ?: return@withContext null
            val seedTier = VocabGrader.tierForRank(seedRank)
            current = VocabProficiencyEntity(
                id = "single",
                cefr = VocabGrader.tierCodeFor(seedTier),
                levelName = VocabGrader.tierLabelFor(seedTier),
                subScore = 0,
                estVocabSize = VocabGrader.refinedVocabFor(seedTier, 0),
                testedTotal = 0,
                testedKnown = 0,
                testedUnknown = 0,
                bandResults = "",
                testedAt = System.currentTimeMillis()
            )
        }

        val tier = VocabGrader.levelFromCode(current.cefr)
        val win = VocabGrader.rankRangeForTier(tier)
        val knownCount = knownRanks.count { it.value in win }
        val semiCount = semiRanks.count { it.value in win }
        // 半熟计 0.5 个已掌握：会但不完全会 → 折半计入进度
        val weightedKnown = knownCount + 0.5 * semiCount
        val totalIn = runCatching { ecdict.countWordsInRankRange(win.first, win.last) }.getOrDefault(0)
        val ratio = if (weightedKnown > 0 && totalIn > 0) (weightedKnown / totalIn).toFloat().coerceIn(0f, 0.999f) else 0f
        val sub = (ratio * 999).roundToInt().coerceIn(0, 999)

        val updated = current.copy(
            cefr = VocabGrader.tierCodeFor(tier),
            levelName = VocabGrader.tierLabelFor(tier),
            subScore = sub,
            estVocabSize = VocabGrader.refinedVocabFor(tier, sub),
            masteryRatio = ratio,
            windowTotal = totalIn,
            windowKnown = knownCount,
            windowSemi = semiCount
        )
        runCatching { proficiencyDao.upsert(updated) }
        updated
    }

    /**
     * 推荐起始等级：记住上次等级，回到**比上次高一级**的位置做能力边界探索
     * （循序渐进、不会跳太多），无记录则用默认起点 B1。
     */
    suspend fun recommendedStartLevel(): Int {
        val cur = proficiencyDao.current() ?: return VocabGrader.START_INDEX
        val lastLevel = VocabGrader.levelFromCode(cur.cefr)
        // 高一级做边界，封顶到 C2
        return (lastLevel + 1).coerceIn(0, VocabGrader.TIERS.lastIndex)
    }

    /** 词典就绪（首次会后台建库），测试前调用 */
    suspend fun ensureReady() = ecdict.ensureImported()

    fun isReady(): Boolean = ecdict.isReady()

    /** 词典总行数（0 = 词典未就绪），用于换算 CEFR 词频区间 */
    suspend fun dictRowCount(): Int = withContext(Dispatchers.IO) { ecdict.rowCount() }

    /**
     * 起始精确认知位置 + 防倒退锚点：
     *  - 有历史 → (上次等级 + 上次子分/100, 上次等级粗)：从上次精确位置起步，结果不会低于上次等级
     *  - 无历史 → (2.0 = A2.00, 0)：新手从基础层渐进
     */
    suspend fun preciseStartInfo(): Pair<Float, Int> {
        val cur = proficiencyDao.current()
        if (cur == null) return 2.0f to 0
        val lastLevel = VocabGrader.levelFromCode(cur.cefr)
        val lastSub = cur.subScore.coerceIn(0, 999)
        return (lastLevel + lastSub / 1000f) to lastLevel
    }

    /** 抽取 [loFrac, hiFrac] 窄频带内的 count 个词（难度严格匹配当前精确认知）；
     *  rowid 区间过窄时补一个最小跨度，避免兜底轮取空。 */
    suspend fun sampleForBand(
        loFrac: Double,
        hiFrac: Double,
        tierIdx: Int,
        pos: Float,
        totalRows: Long,
        count: Int,
        exclude: Set<String>,
        seed: Int
    ): List<EcdictRepository.WordEntry> = withContext(Dispatchers.IO) {
        val fLo = loFrac.coerceIn(0.0, 1.0)
        val fHi = hiFrac.coerceIn(fLo, 1.0)
        var rowLo = (fLo * totalRows).toLong().coerceIn(1, totalRows)
        var rowHi = (fHi * totalRows).toLong().coerceIn(rowLo, totalRows)
        if (rowHi - rowLo < 800L) rowHi = minOf(rowHi + 800L, totalRows) // 最小跨度兜底
        val lvl = tierIdx.coerceIn(0, VocabGrader.TIERS.lastIndex)
        val rw = VocabGrader.rankWindowFor(pos)
        ecdict.sampleWords(
            rowLo, rowHi, count, exclude, seed,
            VocabGrader.eduRangeForTier(lvl), lvl, rw.first, rw.last
        )
    }

    /** 该 CEFR 层级离线的候选单字词库，按 [rowidRange] 抽样，排除已建卡/已选词 */
    suspend fun sampleForTier(
        tier: VocabTier,
        totalRows: Long,
        count: Int,
        exclude: Set<String>,
        seed: Int
    ): List<EcdictRepository.WordEntry> = withContext(Dispatchers.IO) {
        val range = tier.rowidRange(totalRows)
        if (range.isEmpty()) return@withContext emptyList()
        val tierIdx = VocabGrader.TIERS.indexOf(tier).coerceAtLeast(0)
        // 抽样偏好本 CEFR 层所对应的国内教育等级区间（如 B1 → 高考/四级）；兜底用全量词频窗口
        ecdict.sampleWords(range.first, range.last, count, exclude, seed, VocabGrader.eduRangeForTier(tierIdx), tierIdx, 1, VocabGrader.maxRankForLevel(tierIdx))
    }

    /** 记忆库中已存在的词（小写集合），测试抽样时排除，避免重复建卡 */
    suspend fun existingWordFronts(): Set<String> =
        withContext(Dispatchers.IO) { memoryDao.getAllFrontsLower().toHashSet() }

    /**
     * 记录一条测试作答并实时写入记忆库：
     *   - 该词已存在卡片（忽略大小写）→ 只同步更新状态/释义（不重复建卡）
     *   - 不存在 → 新建一张 word 卡（sourceType=test，释义放背面）
     *  @param status "known"=认识 / "semi"=不熟 / "unknown"=不认识
     *  @return Pair(卡片实体, 是否是新增卡片)；失败返回 (null, false)
     */
    suspend fun recordAnswer(word: EcdictRepository.WordEntry, status: String): Pair<MemoryCardEntity?, Boolean> =
        withContext(Dispatchers.IO) {
            if (word.word.isBlank()) return@withContext Pair(null, false)
            val normalized = if (status == "known" || status == "semi") status else "unknown"
            val existing = runCatching { memoryDao.findCardByFront(word.word) }.getOrNull()
            if (existing != null) {
                // 卡片已存在，只更新状态/释义（如果有变化）
                if (existing.status != normalized || existing.back != (word.translation ?: "")) {
                    val updated = existing.copy(
                        status = normalized,
                        back = word.translation ?: existing.back,
                        updatedAt = System.currentTimeMillis()
                    )
                    runCatching { memoryDao.updateCard(updated) }.onFailure { return@withContext Pair(null, false) }
                }
                return@withContext Pair(existing, false) // 不是新增
            }
            val card = FsrsScheduler.createNewCard(
                id = UUID.randomUUID().toString(),
                front = word.word.trim(),
                back = word.translation ?: "",
                status = normalized,
                sourceType = "test",
                cardType = "word"
            )
            runCatching { memoryDao.insertCard(card) }.getOrElse { return@withContext Pair(null, false) }
            Pair(card, true) // 是新增
        }

    /** 保存本次测试的当前等级（单行）+ 一条历史记录（成长曲线） */
    suspend fun saveTestResult(entity: VocabProficiencyEntity, level: Int) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            proficiencyDao.upsert(entity)
            runCatching {
                historyDao.insert(
                    VocabHistoryEntity(
                        id = UUID.randomUUID().toString(),
                        cefr = entity.cefr,
                        levelName = entity.levelName,
                        level = level,
                        subScore = entity.subScore,
                        estVocabSize = entity.estVocabSize,
                        testedTotal = entity.testedTotal,
                        testedKnown = entity.testedKnown,
                        testedUnknown = entity.testedUnknown,
                        bandResults = entity.bandResults,
                        testedAt = now
                    )
                )
            }
        }
    }

    /** 回写最近一条成长曲线历史为"档位+比例小数"的细分/估算词汇量（保持曲线与仪表盘一致） */
    suspend fun patchLastHistoryEstimate(subScore: Int, estVocabSize: Int) = withContext(Dispatchers.IO) {
        runCatching { historyDao.patchLastEstimate(subScore, estVocabSize) }
    }

    /** 便捷构造汇总实体：level=-1(入门以下)/0..5 对应 CEFR 层级，subScore=0..999 三位小数细分，bandResults 形如 "A1:2;B1:3" */
    fun buildProficiencyEntity(
        level: Int,
        subScore: Int,
        testedTotal: Int,
        testedKnown: Int,
        testedUnknown: Int,
        bandResults: List<Pair<String, Int>>
    ): VocabProficiencyEntity {
        val serialized = bandResults.joinToString(";") { "${it.first}:${it.second}" }
        val now = System.currentTimeMillis()
        return VocabProficiencyEntity(
            id = "single",
            cefr = VocabGrader.tierCodeFor(level),
            levelName = VocabGrader.tierLabelFor(level),
            subScore = subScore,
            estVocabSize = VocabGrader.refinedVocabFor(level, subScore),
            testedTotal = testedTotal,
            testedKnown = testedKnown,
            testedUnknown = testedUnknown,
            bandResults = serialized,
            testedAt = now
        )
    }
}
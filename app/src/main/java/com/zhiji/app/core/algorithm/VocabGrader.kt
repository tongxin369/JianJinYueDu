package com.zhiji.app.core.algorithm

import kotlin.math.floor

/**
 * 词汇掌握度测试——CEFR 难度层级定义（纯逻辑，无 Android 依赖，可单测）。
 *
 * 等级体系采用国际最权威的欧洲共同语言参考标准（CEFR）A1→C2 六级，
 * 由简到难。词本身没有现成的 CEFR 标签（ECDICT 精简库只保留
 * word/phonetic/translation/pos 四字段）、也没有 CEFR 词表资源，
 * 因此用 **ECDICT 词典的词频排序位置近似映射到 CEFR 各级**：
 *
 *  - ECDICT 完整库按词频降序排列，本 App 导入 CSV 时按行序逐条 INSERT，
 *    SQLite 的隐式 `rowid`（第 1 行→1，递增）因此近似等于词频排名：
 *     rowid 越小越常见（越简单），rowid 越大越生僻（越难）。
 *  - 每级用「rowid 占比区间」[bandStart, bandEnd) 划出语料分界，
 *    落到该区间的单词即为该 CEFR 难度层的候选题。
 *  - 比例参考 CEFR 大致词汇量（A1≈500、A2≈1k、B1≈2k、B2≈4k、C1≈8k、C2≈16k），
 *    由于库里混入了大量短语与考试词，按占比而不是绝对行数切分更稳健。
 *
 * 测验方式采用**自适应诊断**：抽词样本逐级自评（认识/不认识），认识数达标则升一级、
 * 否则降一级，线性阶梯快速定位当前真实词汇掌握度等级。
 */
data class VocabTier(
    /** CEFR 代号 "A1"~"C2" */
    val code: String,
    /** 中文层级名 */
    val name: String,
    /** 一句话描述 */
    val desc: String,
    /** 该级别的大致认知词汇量上限（用于展示估算） */
    val approxVocabUpTo: Int,
    /** 词频占比区间起点（rowid/R），含 */
    val bandStart: Double,
    /** 词频占比区间终点（rowid/R），不含 */
    val bandEnd: Double
) {
    /** 该级对应的 rowid 区间（含两端），根据词典总行数换算 */
    fun rowidRange(totalRows: Long): LongRange {
        val lo = (bandStart * totalRows).toLong() + 1
        val hi = (bandEnd * totalRows).toLong()
        return lo.coerceAtLeast(1)..hi.coerceIn(lo.coerceAtLeast(1), totalRows)
    }
}

object VocabGrader {

    /**
     * 可测等级，由简到难共 7 档（index 0..6）：A0 预备 → A1 入门 → … → C2 精通。
     *  - A0 是最基础的「预备级」，用最高频 + 最短的一批词测试，让零基础也能测、也能量化进步；
     *    同时也是「降低」链的终点关卡（不是直接出结果的兜底）。
     *  - band 为词频（rowid/R）占比区间：靠前越高频。
     */
    val TIERS = listOf(
        VocabTier("A0", "预备", "认得最基础的高频词，尚未真正入门", 0, 0.0000, 0.0015),
        VocabTier("A1", "入门", "认得校园与日常生活最基本的高频词", 500, 0.0015, 0.0050),
        VocabTier("A2", "基础", "能认常见话题里出现频率较高的表达", 1000, 0.0050, 0.014),
        VocabTier("B1", "初级", "能认旅行、学习、工作等常见语境词汇", 2000, 0.014, 0.045),
        VocabTier("B2", "中级", "能认大部分实用话题与新闻里的词汇", 4000, 0.045, 0.100),
        VocabTier("C1", "高级", "能认复杂话题与正式文体里的词汇", 8000, 0.100, 0.220),
        VocabTier("C2", "精通", "接近母语者，认大量生僻与学术词汇", 16000, 0.220, 0.450)
    )

    /** 诊断起点：从 B1（index=3）起步自适应上/下调 */
    const val START_INDEX = 3

    /** 每级抽取的测试词数量 */
    const val WORDS_PER_TIER = 5

    /** 每级判定「通过」的最低认识数（5 词中认识 >= 3 视为掌握该级） */
    const val PASS_THRESHOLD = 3

    /** 单级最少有效词数：少于它无法判定，该级按「通过但无法判定」降级处理 */
    const val MIN_JUDGE_WORDS = 3

    /** 测试题面单词的最大长度（过长词往往又冷又难，不作为主测） */
    const val MAX_WORD_LEN = 14

    /** level: -1 = 预备级以下（连 A0 都没通过，几乎为零基础）；0..6 = TIERS[index] */
    fun tierLabelFor(level: Int): String =
        if (level < 0) "预备级以下" else TIERS[level.coerceIn(0, TIERS.lastIndex)].name

    fun tierCodeFor(level: Int): String =
        if (level < 0) "A0" else TIERS[level.coerceIn(0, TIERS.lastIndex)].code

    /** 该等级的基准估算认知词汇量 */
    fun approxVocabFor(level: Int): Int =
        if (level < 0) 0 else TIERS[level.coerceIn(0, TIERS.lastIndex)].approxVocabUpTo

    /** 由 CEFR 代号反查等级索引（"A0"→0 … "C2"→6，未知回退 START_INDEX） */
    fun levelFromCode(code: String?): Int {
        if (code.isNullOrBlank()) return START_INDEX
        val idx = TIERS.indexOfFirst { it.code.equals(code.trim(), ignoreCase = true) }
        return if (idx >= 0) idx else START_INDEX
    }

    // ==================== 细分刻度（量化进步度） ====================

    /** 细分进步度 0..999（三位小数刻度 0.000~0.999，最小进步 0.001）：综合最终等级，越接近下一级分数越高。
     *  @param subScore 由 [VocabTestSession] 换算出的 0..999 子分（见 repository）
     *  对所有等级（含 A0 预备级）都输出细分，如 "B2.035" / "A0.001"（每一次微小的进步都能被看见）。 */
    fun fineLabel(level: Int, subScore: Int): String {
        val code = tierCodeFor(level)
        val s = subScore.coerceIn(0, 999)
        return "$code.${s.toString().padStart(3, '0')}"
    }

    /** 细分后的估算认知词汇量：在所在等级区间内插值（相邻两级估算词汇之间按进步度线性插值）。
     *  A0 预备级从 0 起步向 A1(约500) 插值；subScore 0..999。
     *  改进算法：基础上加100-200词缓冲，取整，并按难度等级继承（高会则低全算）。 */
    fun refinedVocabFor(level: Int, subScore: Int): Int {
        val s = subScore.coerceIn(0, 999)
        if (level < 0) return 0
        val idx = level.coerceIn(0, TIERS.lastIndex)
        if (level >= TIERS.lastIndex) return TIERS[TIERS.lastIndex].approxVocabUpTo // 已到顶 C2
        val upper = TIERS[idx + 1].approxVocabUpTo
        val lower = TIERS[idx].approxVocabUpTo
        val base = lower + (s * (upper - lower)) / 999
        // 加100-200词的缓冲，取整
        val buffer = 150
        val total = base + buffer
        // 按100取整，让数值更美观
        return (total / 100) * 100
    }

    // ==================== 教育等级（国内英语考试对标） ====================

    /** ECDICT 考试标签 token（小写）→ 教育等级 0..7 */
    private val TAG_EDU = mapOf(
        "zk" to 0, "gk" to 1, "cet4" to 2, "cet6" to 3,
        "ky" to 4, "ielts" to 5, "toefl" to 6, "gre" to 7
    )

    /** 教育等级对应中文标签（0..7） */
    val EDU_LABELS = listOf("中考", "高考", "大学四级", "大学六级", "考研", "雅思", "托福", "GRE")

    /** 取标签集合对应的**最高**教育等级；无任何考试标签返回 null（多标签取高者，如 cet4 cet6 ky → 考研4） */
    fun eduOf(tags: Set<String>): Int? =
        tags.mapNotNull { TAG_EDU[it.trim().lowercase()] }.maxOrNull()

    /** 教育等级区间 → 对应的标签 token 集合（用于按标签筛词，如 0..1 → zk,gk） */
    fun eduTokens(range: IntRange): Set<String> =
        TAG_EDU.filterValues { it in range }.keys

    /** 各 CEFR 层对应的**最大词频排名上界**（≈各级累计词汇量，作为该层抽词的最高难度上限）。
     *  词频排名（BNC/COCA，越小越常见）是比等级标签更硬的难度信号；
     *  用它把"跨度过大"的生僻词（如 A0 抽到排名 6301 的 dentist）挡在门外。 */
    private val RANK_CAPS = intArrayOf(800, 1800, 3200, 5500, 9000, 15000, 30000)

    /** 某粗等级（0=A0…6=C2）的最高词频排名 */
    fun maxRankForLevel(level: Int): Int =
        RANK_CAPS[level.coerceIn(0, RANK_CAPS.size - 1)]

    /** 由精确认知位置 pos（0.00=A0.00 … 6.99=C2.99）映射一段**词频排名窗口** [minRank, maxRank]。
     *  每级按 0-99(≈10 层)细分：pos 所在子区间 → 该子区间对应的排名段，抽卡只在该段内取词，
     *  从而实现"A0.00-0.10 就只抽该难度段的高频词"的精准匹配。 */
    fun rankWindowFor(pos: Float): IntRange {
        val p = pos.coerceIn(0f, 6.99f)
        val level = floor(p).toInt().coerceIn(0, RANK_CAPS.size - 1)
        val prevCap = if (level == 0) 1 else RANK_CAPS[level - 1] + 1
        val thisCap = RANK_CAPS[level]
        val span = (thisCap - prevCap).toFloat()
        val frac = (p - level).coerceIn(0f, 0.99f)
        val lo = (prevCap + span * frac).toInt().coerceIn(prevCap, thisCap)
        val hi = (prevCap + span * (frac + 0.10f)).toInt().coerceIn(lo, thisCap)
        return lo..hi
    }

    /** 某 CEFR 层（0=A0…6=C2）对应的**词频排名区间**（含两端），用于"词库掌握进度"统计：
     *  A0=[1,800]、A1=[801,1800]… 与 [rankWindowFor] 同源。 */
    fun rankRangeForTier(tier: Int): IntRange {
        val idx = tier.coerceIn(0, RANK_CAPS.lastIndex)
        val lo = if (idx == 0) 1 else RANK_CAPS[idx - 1] + 1
        return lo..RANK_CAPS[idx]
    }

    /** 由词频排名定位最贴近的 CEFR 层索引（rank 落在哪一层的排名区间内） */
    fun tierForRank(rank: Int): Int {
        for (i in RANK_CAPS.indices) if (rank <= RANK_CAPS[i]) return i
        return RANK_CAPS.lastIndex
    }

    /** 每个 CEFR 可测层（0=A0 … 6=C2）建议贴近的教育等级区间（用于抽样偏好，非硬性） */
    fun eduRangeForTier(tier: Int): IntRange = when (tier) {
        0 -> 0..0   // A0 预备 → 中考难度
        1 -> 0..1   // A1 → 中考/高考
        2 -> 0..1   // A2 → 中考/高考
        3 -> 1..2   // B1 → 高考/四级
        4 -> 2..3   // B2 → 四级/六级
        5 -> 3..5   // C1 → 六级/考研/雅思
        else -> 4..7 // C2 → 考研/雅思/托福/GRE
    }

    /** 教育等级（0..7）对标的 CEFR 层级名，用于结果页展示 */
    fun eduLabelFor(edu: Int?): String =
        if (edu == null) "常见/基础词汇" else EDU_LABELS[edu.coerceIn(0, EDU_LABELS.lastIndex)]

    /** CEFR 层位 → 国内教育对标标签（如 B1 → 高考/四级），结果页展示用 */
    fun eduTierLabel(tier: Int): String {
        val r = eduRangeForTier(tier)
        return if (r.first == r.last) EDU_LABELS[r.first] else "${EDU_LABELS[r.first]}/${EDU_LABELS[r.last]}"
    }

    /** 按估值词汇量 → 国内教育对标标签（如 4500词 → 四级） */
    fun eduLabelByVocab(vocabSize: Int): String {
        return when {
            vocabSize <= 500 -> "小学"
            vocabSize <= 1500 -> "初中"
            vocabSize <= 3500 -> "高中"
            vocabSize <= 4500 -> "大学四级"
            vocabSize <= 6000 -> "大学六级"
            vocabSize <= 8000 -> "考研"
            vocabSize <= 12000 -> "雅思/托福"
            else -> "高阶"
        }
    }

    // ==================== 国内等级标签（A+B 结合） ====================
    // 路线A：词频排名（BNC/COCA，越小越常用）→ 国内学段（适合国内教育市场）
    // 路线B：ECDICT 考试标签（tag）→ 中文考试标签（中高考/四六级/考研/托雅/GRE）

    /**
     * 词频排名 → 国内学段。
     * 参考国内各学段大致词汇量：小学≈500-1000、初中≈1600-2500、高中≈3500、
     * 四级≈4500、六级≈6000、考研≈7000、雅思/托福≈8000+、GRE≈12000+。
     */
    fun schoolStageByRank(rank: Int): String = when {
        rank <= 1000 -> "小学"
        rank <= 2500 -> "初中"
        rank <= 3500 -> "高中"
        rank <= 4500 -> "大学四级"
        rank <= 6000 -> "大学六级"
        rank <= 8000 -> "考研"
        rank <= 12000 -> "雅思/托福"
        else -> "高阶"
    }

    /**
     * 词频排名 → 国内年级（v46 细化，更贴合国内教育市场与循序渐进学习）。
     * 国内无官方公开的"分年级"完整词表（尤其大一~大四），因此用 BNC/COCA 词频排名
     * 按各年级大致词汇量分段近似映射：排名越小越常用 → 年级越低。
     * 参考词汇量：小一~六(100~800)、初一~三(1500~2700)、高一~三(3200~4200)、大一~四(4800~7000)。
     */
    fun gradeByRank(rank: Int): String = when {
        rank <= 100 -> "一年级"
        rank <= 250 -> "二年级"
        rank <= 400 -> "三年级"
        rank <= 600 -> "四年级"
        rank <= 800 -> "五年级"
        rank <= 1200 -> "六年级"
        rank <= 1700 -> "初一"
        rank <= 2200 -> "初二"
        rank <= 2700 -> "初三"
        rank <= 3200 -> "高一"
        rank <= 3700 -> "高二"
        rank <= 4200 -> "高三"
        rank <= 4800 -> "大一"
        rank <= 5500 -> "大二"
        rank <= 6200 -> "大三"
        rank <= 7000 -> "大四"
        else -> "高阶"
    }

    /** ECDICT tag 代号 → 中文考试标签（路线 B；未知返回 null） */
    fun examTagLabel(code: String?): String? = when (code?.trim()?.lowercase()) {
        "zk" -> "中考"
        "gk" -> "高考"
        "cet4" -> "四级"
        "cet6" -> "六级"
        "ky" -> "考研"
        "toefl" -> "托福"
        "ielts" -> "雅思"
        "gre" -> "GRE"
        else -> null
    }
}
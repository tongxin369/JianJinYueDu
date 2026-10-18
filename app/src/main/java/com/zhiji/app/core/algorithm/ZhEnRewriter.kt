package com.zhiji.app.core.algorithm

/**
 * Phase 3 中英混排改写引擎（纯逻辑，无 Android 依赖）。
 *
 * 需求：把中文原文中的部分「中文词」改写为含义匹配的「英文单词」，形成「中英结合」的阅读文本。
 *  - 难易比例（结合记忆库三分类标签，符合循序渐进）：
 *      「会的」(known)   —— 遇到即全改，无预算（一本书里会的词有多少改多少）
 *      「不熟的」(semi)  —— 每 100 字窗口内按用户难度档位限额（默认 5%）
 *      「不会的」(unknown)—— 每 100 字窗口内按用户难度档位限额（默认 2%）
 *  - 难易档位由用户按自身阅读感受自定义（[Density]，默认「轻松」档 = 不会的≤2%、不熟的≤5%），
 *    目的是先让用户顺畅读下去，再按需逐步加大生词密度
 *  - 相邻英文单词之间自动补空格断词（避免「learnEnglish」连成一片无法分辨）
 *  - 只改记忆库中有的标签词：某个中文词的英文候选里没有任何标签词时，保持原文中文不变
 *  - 两种显示模式：
 *      replace  替换式：中文词 → 英文单词（如「我每天 run 到公园」）
 *      annotate 对照式：中文词 → 中文(英文)（如「我每天跑步(run)到公园」）
 *  - 不改变原文语义：中文词→英文 通过 ECDICT 反向索引保证含义匹配，未选中的中文原样保留
 *
 * 中文词 → 英文候选 的映射（zhToEn）由 [com.zhiji.app.data.repository.EcdictRepository] 反向索引提供。
 * 记忆库三分类词集（known/semi/unknown）由 MemoryRepository 提供。
 */
class ZhEnRewriter {

    /**
     * 改写难度（用户按自身阅读感受精细化微调生词密度）。
     * 三个维度口径统一为「每 100 字窗口内最多出现多少个」，可分别微调：
     *  - 不熟的（[Tuning.semiPer100]）
     *  - 不会的（[Tuning.unknownPer100]）
     *  - 新增词（[Tuning.injectPer100]，来自当前 CEFR 等级词频区间的新词）
     * 数值越小越顺畅；读顺了再逐项加难，循序渐进。
     */
    object Density {
        /** 每 100 字窗口内「不熟的」可调范围与默认值 */
        const val SEMI_MIN = 0
        const val SEMI_MAX = 20
        const val SEMI_DEFAULT = 5
        /** 每 100 字窗口内「不会的」可调范围与默认值 */
        const val UNKNOWN_MIN = 0
        const val UNKNOWN_MAX = 10
        const val UNKNOWN_DEFAULT = 2
        /** 每 100 字窗口内「新增词」可调范围与默认值 */
        const val INJECT_MIN = 0
        const val INJECT_MAX = 9
        const val INJECT_DEFAULT = 2
        /** 难度口径窗口：每 [WINDOW_CHARS] 个字符为一个预算窗口（占比 = 个数 / [WINDOW_CHARS]） */
        const val WINDOW_CHARS = 100

        /**
         * 难度微调参数：每 100 字窗口内「不熟 / 不会 / 新增词」各自允许出现的最大个数。
         * 三个数值相互独立（各自独立预算），用户按自身水平逐项微调。
         */
        data class Tuning(val semiPer100: Int, val unknownPer100: Int, val injectPer100: Int) {
            /** 引擎使用的占比（= 个数 / 100 字），与 [rewriteParagraph] 的 ratio 参数同域 */
            val semiRatio: Double get() = semiPer100.toDouble() / WINDOW_CHARS
            val unknownRatio: Double get() = unknownPer100.toDouble() / WINDOW_CHARS
            val injectRatio: Double get() = injectPer100.toDouble() / WINDOW_CHARS
            /** 面板说明文案 */
            val describe: String
                get() = "每 100 字最多出现：不熟 $semiPer100 个 · 不会 $unknownPer100 个 · 新词 $injectPer100 个"
            /** 工具条紧凑标签 */
            val compactLabel: String get() = "难度 $semiPer100/$unknownPer100/$injectPer100"
        }

        /** 规整为合法取值（越界夹取到可调范围） */
        fun tuning(semi: Int, unknown: Int, inject: Int): Tuning = Tuning(
            semi.coerceIn(SEMI_MIN, SEMI_MAX),
            unknown.coerceIn(UNKNOWN_MIN, UNKNOWN_MAX),
            inject.coerceIn(INJECT_MIN, INJECT_MAX)
        )

        /** 默认难度（= 原来「轻松」档）：不熟 5 / 不会 2 / 新词 2 */
        val DEFAULT_TUNING: Tuning = tuning(SEMI_DEFAULT, UNKNOWN_DEFAULT, INJECT_DEFAULT)
    }

    /** 单个可替换中文词的匹配结果 */
    data class WordMatch(
        val zh: String,
        /** 在原文（句内）的起始偏移 */
        val start: Int,
        /** 在原文（句内）的结束偏移（exclusive） */
        val end: Int,
        /** 该中文词对应的英文候选（含义匹配） */
        val candidates: List<String>
    )

    /** 一次实际替换的明细（zh→en + 所属标签类别 + 在两种模式产物中的位置） */
    data class Replacement(
        val zh: String,
        val en: String,
        /** "known"=会的 / "semi"=不熟的 / "unknown"=不会的 */
        val category: String,
        /** 替换式产物中该英文词的起始偏移 */
        val startInReplace: Int = 0,
        /** 替换式产物中该英文词的结束偏移（exclusive） */
        val endInReplace: Int = 0,
        /** 对照式产物中该英文词的起始偏移（括号内） */
        val startInAnnotate: Int = 0,
        /** 对照式产物中该英文词的结束偏移（exclusive） */
        val endInAnnotate: Int = 0,
        /**
         * 该替换所属的段落索引（-1 = 未标记）。
         * 分段改写时由仓库层回填：单击英文词弹原意需要先按段落定位命中范围。
         */
        val paragraphIndex: Int = -1
    )

    data class RewriteResult(
        /** 替换式文本：中文词 → 英文单词 */
        val rewritten: String,
        /** 对照式文本：中文词 → 中文(英文) */
        val annotated: String,
        /** 实际替换的中文词数量 */
        val replacedCount: Int,
        /** 其中使用了「会的」词的数量 */
        val knownCount: Int,
        /** 其中使用了「不熟的」词的数量 */
        val semiCount: Int,
        /** 其中使用了「不会的」词的数量 */
        val unknownCount: Int,
        /** 每次替换的明细（zh/en/category） */
        val replacements: List<Replacement>
    )

    /**
     * 对一段中文文本做中英混排改写，一次计算两种显示模式：
     *  - replace  替换式：中文词 → 英文（如「我每天 run 到公园」）
     *  - annotate 对照式：中文词 → 中文(英文)（如「我每天跑步(run)到公园」）
     *
     * @param zhWords 中文词表（按长度降序排列，用于正向最大匹配）
     * @param zhToEn  中文词 → 英文候选列表（含义匹配，来自 ECDICT 反向索引）
     * @param known   记忆库「会的」英文词（小写集合）—— 遇到即全改
     * @param semi    记忆库「不熟的」英文词（小写集合）—— 每窗口预算内可改
     * @param unknown 记忆库「不会的」英文词（小写集合）—— 每窗口预算内可改
     * @param windowChars 预算窗口字数（默认 100 字）
     * @param semiRatio 每个窗口内「不熟的」最大占比（默认 0.05 = 每 100 字最多 5 个；0 = 不出现）
     * @param unknownRatio 每个窗口内「不会的」最大占比（默认 0.02 = 每 100 字最多 2 个；0 = 不出现）
     * @param injectWords 「不会的」改写的新词来源：按用户当前 CEFR 等级词频区间选出的单词，
     *                    **按词频升序**（越常用越靠前）。中文词的英文候选里命中注入池时，
     *                    优先选词频最高（排名最靠前）的一个改写（计入「不会的」统计）。
     * @param injectRatio 每个窗口内注入词的最大占比（默认 0.02 = 每 100 字最多 2 个，独立预算）
     */
    fun rewriteParagraph(
        text: String,
        zhWords: List<String>,
        zhToEn: Map<String, List<String>>,
        known: Set<String>,
        semi: Set<String>,
        unknown: Set<String>,
        windowChars: Int = Density.WINDOW_CHARS,
        semiRatio: Double = 0.05,
        unknownRatio: Double = 0.02,
        injectWords: List<String> = emptyList(),
        injectRatio: Double = 0.02
    ): RewriteResult {
        if (text.isBlank()) {
            return RewriteResult(text, text, 0, 0, 0, 0, emptyList())
        }
        val wordSet = zhWords.toHashSet()
        // 限额允许为 0（用户微调到 0 = 该类词完全不出现）
        val semiLimit = (windowChars * semiRatio).toInt().coerceAtLeast(0)
        val unknownLimit = (windowChars * unknownRatio).toInt().coerceAtLeast(0)
        val injectWindowLimit = (windowChars * injectRatio).toInt().coerceAtLeast(0)
        // 注入池词频序：word(小写) → 在升序池中的下标（越小越常用）
        val injectRank: Map<String, Int> = if (injectWords.isEmpty()) emptyMap()
        else injectWords.mapIndexed { i, w -> w.lowercase() to i }.toMap()
        // 每 100 字窗口内已用的 semi / unknown / 注入词数量（按替换点在全文中的绝对位置分桶）
        val semiUsedPerWindow = HashMap<Int, Int>()
        val unknownUsedPerWindow = HashMap<Int, Int>()
        val injectUsedPerWindow = HashMap<Int, Int>()
        val replacements = mutableListOf<Replacement>()

        // 按句子处理，保留分隔符；每句携带其在全文中的起始偏移（用于窗口预算）
        val sentences = splitSentences(text)
        val outReplace = StringBuilder()
        val outAnnotate = StringBuilder()
        sentences.forEach { (startOffset, s) ->
            val seg = s.segment
            // 句内 FMM 找可替换词位
            val matches = findMatches(seg, wordSet, zhToEn)
            var cursor = 0
            matches.forEach { m ->
                val zhText = seg.substring(m.start, m.end)
                var picked = pickByCategory(m.candidates, known, semi, unknown)
                if (picked == null && injectWindowLimit > 0 && injectRank.isNotEmpty()) {
                    // 「不会的」第二来源：候选里没有记忆库标签词 → 按词频顺序注入当前等级区间的新词
                    // （优先选词频最高、排名最靠前的候选，词频=重要度）
                    val inj = m.candidates
                        .mapNotNull { c -> injectRank[c.lowercase()]?.let { r -> c to r } }
                        .minByOrNull { it.second }?.first
                    if (inj != null) {
                        val bucket = (startOffset + m.start) / windowChars
                        if (injectUsedPerWindow.getOrDefault(bucket, 0) < injectWindowLimit) {
                            injectUsedPerWindow[bucket] = injectUsedPerWindow.getOrDefault(bucket, 0) + 1
                            picked = inj to "unknown"
                        }
                    }
                }
                picked ?: return@forEach  // 候选里没有任何标签词/注入词 → 保持中文原文不改
                val (en, category) = picked
                // 窗口预算：semi / unknown 各自每 windowChars 字最多对应限额个
                val bucket = (startOffset + m.start) / windowChars
                if (category == "semi") {
                    if (semiUsedPerWindow.getOrDefault(bucket, 0) >= semiLimit) return@forEach
                    semiUsedPerWindow[bucket] = semiUsedPerWindow.getOrDefault(bucket, 0) + 1
                } else if (category == "unknown") {
                    if (unknownUsedPerWindow.getOrDefault(bucket, 0) >= unknownLimit) return@forEach
                    unknownUsedPerWindow[bucket] = unknownUsedPerWindow.getOrDefault(bucket, 0) + 1
                }
                // ---- 替换式：英文词两侧按需补空格，避免相邻英文单词连成一片无法断词 ----
                appendChunk(outReplace, seg, cursor, m.start)
                val prevOut = outReplace.lastOrNull()
                val nextSeg = seg.getOrNull(m.end)
                if (prevOut != null && isWordChar(prevOut) && isWordChar(en.first())) outReplace.append(' ')
                val repStart = outReplace.length
                outReplace.append(en)
                val repEnd = outReplace.length
                if (nextSeg != null && isWordChar(nextSeg) && isWordChar(en.last())) outReplace.append(' ')
                // ---- 对照式：中文(英文) ----
                appendChunk(outAnnotate, seg, cursor, m.start)
                outAnnotate.append(zhText).append('(')
                val annStart = outAnnotate.length
                outAnnotate.append(en)
                val annEnd = outAnnotate.length
                outAnnotate.append(')')
                cursor = m.end
                replacements.add(
                    Replacement(
                        zh = zhText,
                        en = en,
                        category = category,
                        startInReplace = repStart,
                        endInReplace = repEnd,
                        startInAnnotate = annStart,
                        endInAnnotate = annEnd
                    )
                )
            }
            appendChunk(outReplace, seg, cursor, seg.length)
            appendChunk(outAnnotate, seg, cursor, seg.length)
        }

        val knownCount = replacements.count { it.category == "known" }
        val semiCount = replacements.count { it.category == "semi" }
        val unknownCount = replacements.count { it.category == "unknown" }
        return RewriteResult(
            rewritten = outReplace.toString(),
            annotated = outAnnotate.toString(),
            replacedCount = replacements.size,
            knownCount = knownCount,
            semiCount = semiCount,
            unknownCount = unknownCount,
            replacements = replacements
        )
    }

    // ---------- 内部工具 ----------

    private class Sentence(val segment: String)

    /** 英文单词字符（字母/数字）：用于判断两处输出之间是否需要补空格断词 */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() && c.code < 128

    /**
     * 追加原文片段到输出：若紧跟在一个英文单词后面（trailing 空格已补）则跳过重复空格，
     * 保证「中文 run 到」这类混排不会出现连续两个空格。
     */
    private fun appendChunk(sb: StringBuilder, seg: String, start: Int, end: Int) {
        if (start >= end) return
        if (sb.isNotEmpty() && sb.last() == ' ' && seg[start] == ' ') {
            sb.append(seg, start + 1, end)
        } else {
            sb.append(seg, start, end)
        }
    }

    /** 按中文/英文句子分隔符切句，保留分隔符在句中；返回 (句在全文中的起始偏移, 句) */
    private fun splitSentences(text: String): List<Pair<Int, Sentence>> {
        val result = mutableListOf<Pair<Int, Sentence>>()
        val sb = StringBuilder()
        var offset = 0
        text.forEach { c ->
            sb.append(c)
            if (c in "。！？；!?;") {
                result.add(offset to Sentence(sb.toString()))
                offset += sb.length
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) result.add(offset to Sentence(sb.toString()))
        return result
    }

    /**
     * 句内正向最大匹配（FMM）：从每个位置起，尝试最长 8 字 → 2 字的中文词，
     * 命中「中文词表且有英文候选」即作为可替换词位。
     * 返回按出现顺序排列的匹配（相互不重叠）。
     */
    private fun findMatches(
        text: String,
        wordSet: Set<String>,
        zhToEn: Map<String, List<String>>
    ): List<WordMatch> {
        val matches = mutableListOf<WordMatch>()
        var i = 0
        val n = text.length
        while (i < n) {
            var matched = false
            // 最长优先：8字 → 2字（与反向索引提取的 2~8 字范围一致）
            val maxLen = minOf(8, n - i)
            for (len in maxLen downTo 2) {
                val candidate = text.substring(i, i + len)
                if (candidate in wordSet) {
                    val enList = zhToEn[candidate]
                    if (!enList.isNullOrEmpty()) {
                        matches.add(WordMatch(candidate, i, i + len, enList))
                        i += len
                        matched = true
                        break
                    }
                }
            }
            if (!matched) i++
        }
        return matches
    }

    /**
     * 按记忆库三分类标签选择英文：
     *  - 「会的」(known) 优先，命中即返回（无预算，全改）
     *  - 其次「不熟的」(semi)
     *  - 再次「不会的」(unknown)
     *  - 都不命中 → null（该词不改，保持原文中文）
     */
    private fun pickByCategory(
        candidates: List<String>,
        known: Set<String>,
        semi: Set<String>,
        unknown: Set<String>
    ): Pair<String, String>? {
        val k = candidates.firstOrNull { it.lowercase() in known }
        if (k != null) return k to "known"
        val s = candidates.firstOrNull { it.lowercase() in semi }
        if (s != null) return s to "semi"
        val u = candidates.firstOrNull { it.lowercase() in unknown }
        if (u != null) return u to "unknown"
        return null
    }
}

package com.zhiji.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.zhiji.app.core.algorithm.VocabGrader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 离线英汉词典（ECDICT 精简版）。
 *
 * 数据流：
 *  - assets/dict/ecdict_mini.csv 打包（精简版：高频词 + 考试词，仅保留 word/phonetic/translation/pos 字段）
 *  - 首次使用时后台协程把 CSV 导入 SQLite（filesDir/dict/ecdict.db），建 word 索引
 *  - 之后查询走 SQLite（毫秒级），未命中时做大小写 / 去引号 / 短语取末词等兜底
 *
 * 首用导入一次性，幂等；导入期间查询返回 null（UI 可提示"词典初始化中"）。
 */
class EcdictRepository(private val context: Context) {

    /** 词典查询结果（v45 增加 tag/bnc/frq，用于国内等级标签） */
    data class DictEntry(
        val word: String,
        val phonetic: String?,
        val translation: String?,
        val pos: String?,
        /** 考试标签原始值（空格/斜杠分隔：zk gk cet4 cet6 ky toefl ielts gre） */
        val tag: String? = null,
        /** BNC 英国国家语料库词频排名（越小越常用） */
        val bnc: Int? = null,
        /** frq 当代语料库词频排名（越小越常用） */
        val frq: Int? = null
    ) {
        /** 国内等级标签（A+B 结合）：年级（词频→一年级~大四/高阶）+ 考试标签 */
        fun gradeLabels(): List<String> {
            val list = mutableListOf<String>()
            val rank = listOfNotNull(bnc, frq).minOrNull()
            if (rank != null) {
                list.add(VocabGrader.gradeByRank(rank))
            }
            tag?.split(" ", "/", "，", ",")?.forEach { t ->
                VocabGrader.examTagLabel(t)?.let { if (it !in list) list.add(it) }
            }
            return list
        }

        /** 原始词频数值（v47）：如 "BNC #235 / COCA #241"；无词频返回 null */
        fun freqText(): String? {
            val parts = mutableListOf<String>()
            bnc?.let { parts.add("BNC #$it") }
            frq?.let { parts.add("COCA #$it") }
            return parts.joinToString(" / ").ifBlank { null }
        }
    }

    private companion object {
        const val TAG = "Ecdict"
        const val ASSET_CSV = "dict/ecdict_mini.csv"
        // v45 词库 v4：增加 tag/bnc/frq（国内等级标签 A+B），需重建库
        const val DB_NAME = "ecdict_v4.db"
        const val TABLE = "dict"
        val ZH_WORD_REGEX = Regex("[\u4e00-\u9fa5]{2,}")
    }

    private val dbFile: File
        get() = File(context.filesDir, "dict/$DB_NAME")

    @Volatile
    private var ready = false

    /** 词典是否已导入就绪 */
    fun isReady(): Boolean = ready

    /** 幂等导入：assets 精简 CSV → SQLite（后台 IO）。仅首次真正执行。 */
    suspend fun ensureImported() = withContext(Dispatchers.IO) {
        if (dbFile.exists() && dbFile.length() > 0) {
            ready = true
            return@withContext
        }
        try {
            dbFile.parentFile?.mkdirs()
            // 先写临时文件，成功后再替换，避免中断产生半成品库
            val tmpFile = File(dbFile.parentFile, "$DB_NAME.tmp")
            tmpFile.delete()
            val db = SQLiteDatabase.openOrCreateDatabase(tmpFile, null)
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS $TABLE (" +
                        "word TEXT PRIMARY KEY, " +
                        "phonetic TEXT, " +
                        "translation TEXT, " +
                        "pos TEXT, " +
                        "tag TEXT, " +
                        "bnc TEXT, " +
                        "frq TEXT" +
                        ")"
                )
                db.beginTransaction()
                val stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO $TABLE (word, phonetic, translation, pos, tag, bnc, frq) VALUES (?,?,?,?,?,?,?)"
                )
                var count = 0
                context.assets.open(ASSET_CSV).bufferedReader(Charsets.UTF_8).use { reader ->
                    // 跳过表头
                    reader.readLine()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val fields = parseCsvLine(line)
                        if (fields.size < 2) continue
                        val word = fields[0].trim()
                        if (word.isEmpty()) continue
                        stmt.bindString(1, word)
                        stmt.bindString(2, fields.getOrNull(1) ?: "")
                        // 精简 CSV：word,phonetic,translation,pos,tag,bnc,frq
                        stmt.bindString(3, fields.getOrNull(2) ?: "")
                        stmt.bindString(4, fields.getOrNull(3) ?: "")
                        stmt.bindString(5, fields.getOrNull(4) ?: "")
                        stmt.bindString(6, fields.getOrNull(5) ?: "")
                        stmt.bindString(7, fields.getOrNull(6) ?: "")
                        stmt.executeInsert()
                        count++
                        if (count % 2000 == 0) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                        }
                    }
                }
                db.setTransactionSuccessful()
                db.endTransaction()
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_word ON $TABLE(word)")
                Log.i(TAG, "imported $count entries")
            } finally {
                db.close()
            }
            if (tmpFile.length() > 0 && tmpFile.renameTo(dbFile)) {
                ready = true
            } else {
                // rename 失败（跨目录/占用）则直接使用 tmp 路径
                if (tmpFile.exists()) {
                    tmpFile.copyTo(dbFile, overwrite = true)
                    tmpFile.delete()
                    ready = true
                }
            }
            // v43/v44/v45 清理历史版本旧库，避免残留占空间
            if (ready) {
                runCatching {
                    File(dbFile.parentFile, "ecdict.db").delete()
                    File(dbFile.parentFile, "ecdict.db-journal").delete()
                    File(dbFile.parentFile, "ecdict_v2.db").delete()
                    File(dbFile.parentFile, "ecdict_v2.db-journal").delete()
                    File(dbFile.parentFile, "ecdict_v3.db").delete()
                    File(dbFile.parentFile, "ecdict_v3.db-journal").delete()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ensureImported failed", t)
            ready = false
        }
    }

    /**
     * 查询单词释义。未命中时依次尝试：
     *  1. 原词精确
     *  2. 小写
     *  3. 去掉首尾引号/空格
     *  4. 若为短语（含空格）且未命中 → 取最后一个词
     */
    fun lookup(raw: String): DictEntry? {
        if (!ready || raw.isBlank()) return null
        val candidates = buildList {
            add(raw.trim())
            add(raw.trim().lowercase())
            val cleaned = raw.trim().trim('"', '\'', ' ', '\u00a0')
            if (cleaned != raw.trim()) add(cleaned)
            add(cleaned.lowercase())
        }.distinct()

        candidates.forEach { c ->
            lookupExact(c)?.let { return it }
        }

        // 短语兜底：取最后一个单词
        val last = raw.trim().substringAfterLast(' ').trim().lowercase()
        if (last.isNotEmpty() && last != raw.trim().lowercase()) {
            lookupExact(last)?.let { return it }
        }
        return null
    }

    private fun lookupExact(word: String): DictEntry? {
        if (!ready) return null
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery(
                    "SELECT word, phonetic, translation, pos, tag, bnc, frq FROM $TABLE WHERE word = ? LIMIT 1",
                    arrayOf(word)
                ).use { c ->
                    if (c.moveToFirst()) {
                        DictEntry(
                            word = c.getString(0),
                            phonetic = c.getString(1).ifBlank { null },
                            // ECDICT 原始数据用字面 \r\n / \n 表示换行，转成真实换行便于展示
                            translation = c.getString(2)
                                .replace("\\r\\n", "\n")
                                .replace("\\n", "\n")
                                .ifBlank { null },
                            pos = c.getString(3).ifBlank { null },
                            tag = c.getString(4).ifBlank { null },
                            bnc = c.getString(5).trim().toIntOrNull(),
                            frq = c.getString(6).trim().toIntOrNull()
                        )
                    } else null
                }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "lookup failed for '$word'", t)
            null
        }
    }

    /**
     * 批量判断哪些词已被词典收录（Phase 3 难度分析用）。
     * 单次打开 DB，按块 IN 查询，避免逐词开关数据库连接（万级单词也能秒级完成）。
     * @return 命中词的集合（统一小写），用于「词典收录 = 常见/已掌握」判定
     */
    fun findExistingWords(words: Collection<String>): Set<String> {
        if (!ready || words.isEmpty()) return emptySet()
        val unique = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        if (unique.isEmpty()) return emptySet()
        val result = HashSet<String>()
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val chunk = 800
                unique.chunked(chunk).forEach { batch ->
                    val placeholders = batch.joinToString(",") { "?" }
                    db.rawQuery(
                        "SELECT word FROM $TABLE WHERE word IN ($placeholders)",
                        batch.toTypedArray()
                    ).use { c ->
                        while (c.moveToNext()) {
                            c.getString(0)?.lowercase()?.let { result.add(it) }
                        }
                    }
                }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findExistingWords failed", t)
        }
        return result
    }

    // ============================================================
    // Phase 3 中英混排：中文词 → 英文候选 反向索引
    // ============================================================

    @Volatile
    private var zhEnCache: Map<String, List<String>>? = null
    private val zhEnLock = Any()

    /**
     * 「中文词 → 英文候选」反向索引（进程内懒缓存，线程安全）。
     * 来源：遍历 ECDICT 每个词条，从中文释义(translation)中提取 2~8 字中文词，
     *   反向建立 中文词 → [英文单词] 映射，保证「改写的中文与其英文含义匹配」。
     * 仅供中英混排改写引擎使用；首次构建约几百 ms，后续复用缓存。
     */
    fun zhEnIndex(): Map<String, List<String>> {
        zhEnCache?.let { return it }
        synchronized(zhEnLock) {
            zhEnCache?.let { return it }
            val map = buildZhEnIndex()
            zhEnCache = map
            return map
        }
    }

    /** 中文词表，按词长降序（供正向最大匹配 FMM 使用） */
    fun zhWordsByLengthDesc(): List<String> {
        val keys = zhEnIndex().keys
        return keys.sortedByDescending { it.length }
    }

    private fun buildZhEnIndex(): Map<String, List<String>> {
        if (!ready) return emptyMap()
        val result = HashMap<String, MutableList<String>>()
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT word, translation FROM $TABLE", null).use { c ->
                    while (c.moveToNext()) {
                        val en = c.getString(0) ?: continue
                        val tr = c.getString(1) ?: continue
                        val zhWords = extractZhWords(tr)
                        for (zh in zhWords) {
                            result.getOrPut(zh) { mutableListOf() }.add(en)
                        }
                    }
                }
            } finally {
                db.close()
            }
            Log.i(TAG, "zhEnIndex built → ${result.size} 个中文词")
        } catch (t: Throwable) {
            Log.e(TAG, "buildZhEnIndex failed", t)
        }
        return result
    }

    /** 从英文释义中提取 2~8 字中文词（过滤虚词符号与超长短语） */
    private fun extractZhWords(translation: String): List<String> {
        if (translation.isBlank()) return emptyList()
        return ZH_WORD_REGEX.findAll(translation)
            .map { it.value }
            .filter { it.length in 2..8 }
            .distinct()
            .toList()
    }

    /** 测试候选词样本（单字词，含音标/中文释义/考试标签/词频排名） */
    data class WordEntry(
        val word: String,
        val phonetic: String?,
        val translation: String?,
        /** 考试标签原始值（空格/斜杠分隔：zk gk cet4 cet6 ky toefl ielts gre） */
        val tag: String? = null,
        /** BNC 英国国家语料库词频排名（越小越常用） */
        val bnc: Int? = null,
        /** frq 当代语料库词频排名（越小越常用） */
        val frq: Int? = null
    )

    /** 词典总行数（用于把 CEFR 词频占比区间换算成 rowid 区间） */
    fun rowCount(): Int {
        if (!ready) return 0
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "rowCount failed", t)
            0
        }
    }

    /**
     * 统计词频排名落在 [minRank, maxRank] 内的**单字英文词**总数（BNC/COCA 任一达标即可）。
     * 用于"词库掌握进度"——当前等级排名区间内的总词数分母。
     * 与 [fetchByRankWindow] 同口径（单字词、排名区间过滤），保证分子/分母可比。 */
    fun countWordsInRankRange(minRank: Int, maxRank: Int): Int {
        if (!ready || maxRank <= 0 || minRank > maxRank) return 0
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery(
                    "SELECT COUNT(*) FROM $TABLE " +
                        "WHERE instr(word, ' ') = 0 AND word GLOB '[A-Za-z]*[A-Za-z]' " +
                        "AND ((CAST(frq AS INTEGER) BETWEEN ? AND ?) OR (CAST(bnc AS INTEGER) BETWEEN ? AND ?))",
                    arrayOf(minRank.toString(), maxRank.toString(), minRank.toString(), maxRank.toString())
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "countWordsInRankRange failed", t)
            0
        }
    }

    /**
     * 批量查询单词的词频排名：返回 lower(word) → min(BNC, COCA) 排名（越小越常用）。
     * 未收录 / 无词频排名的词不出现。用于"词库掌握进度"——统计用户已知卡片里
     * 有多少落在当前等级的排名区间内（分子）。
     * 单次开库 + 按块 IN 查询，避免逐词开关数据库连接。 */
    fun findRanks(words: Collection<String>): Map<String, Int> {
        if (!ready || words.isEmpty()) return emptyMap()
        val unique = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        if (unique.isEmpty()) return emptyMap()
        val result = HashMap<String, Int>()
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val chunk = 800
                unique.chunked(chunk).forEach { batch ->
                    val placeholders = batch.joinToString(",") { "?" }
                    db.rawQuery(
                        "SELECT word, bnc, frq FROM $TABLE WHERE word IN ($placeholders)",
                        batch.toTypedArray()
                    ).use { c ->
                        while (c.moveToNext()) {
                            val w = c.getString(0)?.lowercase() ?: continue
                            val bnc = c.getString(1)?.trim()?.toIntOrNull()
                            val frq = c.getString(2)?.trim()?.toIntOrNull()
                            val rank = listOfNotNull(bnc, frq).minOrNull() ?: continue
                            result[w] = minOf(result[w] ?: Int.MAX_VALUE, rank)
                        }
                    }
                }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findRanks failed", t)
        }
        return result
    }

    /**
     * 从指定 rowid 区间抽样 `count` 个**单字英文词**（词汇测试用）。
     * 库里行按词频降序插入，rowid 越小越常见，因此区间越靠前词越简单。
     *
     * @param rowLo / rowHi 含两端 rowid 区间
     * @param count 期望抽取数量（可能因区间内单字词不足而少于该值）
     * @param exclude 排除词集合（已建卡的生词/已选过的词，均小写），避免重复测试
     * @param seed 确定性伪随机种子，同 seed 同选区，便于复现与换卷
     */
    fun sampleWords(
        rowLo: Long,
        rowHi: Long,
        count: Int,
        exclude: Set<String>,
        seed: Int,
        preferEdu: IntRange,
        level: Int,
        rankLo: Int,
        rankHi: Int
    ): List<WordEntry> {
        if (!ready || rowHi < rowLo || count <= 0 || preferEdu.first > preferEdu.last) return emptyList()
        val localExclude = exclude.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        val chosen = HashSet<String>()
        val out = mutableListOf<WordEntry>()
        val baseW = (rowHi - rowLo + 1).coerceAtLeast(1L)

        // 轮0：**词频排名**是最可信的难度信号——取当前所选"细分难度区间(词频排名窗口)"内的单字词，
        //       既能精准匹配 A0.00-0.10 这类子区间，又杜绝跨度过大的生僻词（如 dentist 排名 6301）。
        val topPool = fetchByRankWindow(rankLo, rankHi, 600)
            .filter { difficultyRank(it) in rankLo..rankHi }
            .filter { it.word.trim().lowercase() !in chosen }
            .filter { isSingleEnglishWord(it.word, VocabGrader.MAX_WORD_LEN, false) }
            .filter { !isProbablyName(it.word.trim(), VocabGrader.eduOf(parseExamTags(it.tag)) != null) }
            .filter { it.word.trim().lowercase() !in localExclude }
            .sortedBy { it.word.trim().length } // 短词优先（更简单）
        if (topPool.isNotEmpty()) {
            val step = (topPool.size / (count + 1)).coerceAtLeast(1)
            var idx = (seed % topPool.size).toInt()
            var guard = 0
            while (out.size < count && idx < topPool.size && guard < count * 3) {
                guard++
                val e = topPool[idx]
                val lw = e.word.trim().lowercase()
                if (lw !in chosen) {
                    chosen.add(lw)
                    out.add(WordEntry(e.word, e.phonetic, e.translation, tag = e.tag, bnc = e.bnc, frq = e.frq))
                }
                idx += step
            }
        }

        // 轮1：按「教育等级标签」补充常考/重点词（中考/高考/四级/…），同样以词频排名封顶防跨度过大
        val eduTokens = VocabGrader.eduTokens(preferEdu).map { it.lowercase() }.toSet()
        if (eduTokens.isNotEmpty() && out.size < count) {
            val tagged = fetchByEdu(eduTokens, 400)
            val cands = tagged
                .filter { it.word.trim().lowercase() !in chosen }
                .filter { isSingleEnglishWord(it.word, VocabGrader.MAX_WORD_LEN, false) }
                .filter { difficultyRank(it) in rankLo..rankHi } // 词频排名窗口封顶，精准匹配所选细分区间
                .filter { !isProbablyName(it.word.trim(), VocabGrader.eduOf(parseExamTags(it.tag)) != null) }
                .filter { it.word.trim().lowercase() !in localExclude }
                .sortedBy { it.word.trim().length } // 短词优先（更简单）
            if (cands.isNotEmpty()) {
                val step = (cands.size / (count + 1)).coerceAtLeast(1)
                var idx = (seed % cands.size).toInt()
                var guard = 0
                while (out.size < count && idx < cands.size && guard < count * 3) {
                    guard++
                    val w = cands[idx].word.trim()
                    val lw = w.lowercase()
                    if (lw !in chosen) {
                        chosen.add(lw)
                        out.add(
                            WordEntry(
                                cands[idx].word, cands[idx].phonetic, cands[idx].translation,
                                tag = cands[idx].tag, bnc = cands[idx].bnc, frq = cands[idx].frq
                            )
                        )
                    }
                    idx += step
                }
            }
        }

        // 轮2：标签筛不足时，回退到频带（词频）取基础/兜底词，并拒绝专名。
        val rounds = listOf(
            Pair(VocabGrader.MAX_WORD_LEN, false),
            Pair(22, true)
        )
        for ((maxLen, lenient) in rounds) {
            if (out.size >= count) break
            var batch = emptyList<DictEntry>()
            var hi = rowHi
            for (exp in 0..8) {
                batch = fetchEntries(rowLo, hi, 400)
                if (batch.isNotEmpty()) break
                hi = rowHi + baseW * (1L shl exp)
                if (hi < rowLo) break
            }
            val ranked = batch
                .filter { it.word.trim().lowercase() !in chosen }
                .filter { isSingleEnglishWord(it.word, maxLen, lenient) }
                .map { e ->
                    val w = e.word.trim()
                    val tags = parseExamTags(e.tag)
                    val hasTag = VocabGrader.eduOf(tags) != null
                    val score = if (isProbablyName(w, hasTag)) Int.MAX_VALUE
                    else {
                        val edu = VocabGrader.eduOf(tags)
                        when {
                            edu != null && edu in preferEdu -> 0
                            edu != null -> 1
                            (e.bnc ?: 0) > 0 || (e.frq ?: 0) > 0 -> 2
                            else -> 3
                        }
                    }
                    Triple(w, score, w.length)
                }
                .filter { it.second != Int.MAX_VALUE }
                .filter { if (lenient) true else it.first.lowercase() !in localExclude }
                .sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
                .toList()
            if (ranked.isEmpty()) continue
            val need = count - out.size
            val step = (ranked.size / (need + 1)).coerceAtLeast(1)
            var idx = (seed % ranked.size).toInt()
            var guard = 0
            while (out.size < count && idx < ranked.size && guard < need * 3) {
                guard++
                val (w, _, _) = ranked[idx]
                val lw = w.lowercase()
                if (lw !in chosen) {
                    chosen.add(lw)
                    val full = batch.firstOrNull { it.word.trim().equals(w, ignoreCase = true) }
                    out.add(
                        full?.let { WordEntry(it.word, it.phonetic, it.translation, tag = it.tag, bnc = it.bnc, frq = it.frq) }
                            ?: WordEntry(w, null, null)
                    )
                }
                idx += step
            }
        }
        return out
    }

    /** 按考试标签 token 集合筛选单字英文词（如 zk/gk → 中考/高考词）；含有效词频排名者优先（更常见） */
    private fun fetchByEdu(tokens: Set<String>, limit: Int): List<DictEntry> {
        if (!ready || tokens.isEmpty()) return emptyList()
        val clause = tokens.joinToString(" OR ") { "(' ' || tag || ' ') LIKE '% $it %'" }
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val list = mutableListOf<DictEntry>()
                db.rawQuery(
                    "SELECT word, phonetic, translation, pos, tag, bnc, frq FROM $TABLE " +
                        "WHERE instr(word, ' ') = 0 AND word GLOB '[A-Za-z]*[A-Za-z]' AND ( $clause ) " +
                        "ORDER BY (CASE WHEN frq > 0 THEN 0 WHEN bnc > 0 THEN 0 ELSE 1 END), rowid ASC LIMIT ?",
                    arrayOf(limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        list.add(
                            DictEntry(
                                word = c.getString(0),
                                phonetic = c.getString(1).ifBlank { null },
                                translation = c.getString(2).ifBlank { null },
                                pos = c.getString(3).ifBlank { null },
                                tag = c.getString(4).ifBlank { null },
                                bnc = c.getString(5).toIntOrNull(),
                                frq = c.getString(6).toIntOrNull()
                            )
                        )
                    }
                }
                list
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchByEdu failed", t)
            emptyList()
        }
    }

    /** 取 [lo, hi] 内按 rowid 升序的前 limit 个单字英文词（含标签/词频） */
    private fun fetchEntries(lo: Long, hi: Long, limit: Int): List<DictEntry> {
        if (!ready) return emptyList()
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val list = mutableListOf<DictEntry>()
                db.rawQuery(
                    "SELECT word, phonetic, translation, pos, tag, bnc, frq FROM $TABLE WHERE rowid BETWEEN ? AND ? AND word GLOB '[A-Za-z]*[A-Za-z]' AND instr(word, ' ') = 0 ORDER BY rowid LIMIT ?",
                    arrayOf(lo.toString(), hi.toString(), limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        list.add(
                            DictEntry(
                                word = c.getString(0),
                                phonetic = c.getString(1).ifBlank { null },
                                translation = c.getString(2).ifBlank { null },
                                pos = c.getString(3).ifBlank { null },
                                tag = c.getString(4).ifBlank { null },
                                bnc = c.getString(5).toIntOrNull(),
                                frq = c.getString(6).toIntOrNull()
                            )
                        )
                    }
                }
                list
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchEntries failed", t)
            emptyList()
        }
    }

    /** 解析考试标签集合（空格/斜杠/顿号/逗号分隔，统一小写） */
    private fun parseExamTags(tag: String?): Set<String> =
        tag?.split(" ", "/", "，", ",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** 无明显考试标签、且首字母大写的词，多为专名/人名/地名（如 Abbas/Aachen），不作为主测词 */
    private fun isProbablyName(w: String, hasExamTag: Boolean): Boolean {
        if (hasExamTag) return false
        val c = w.firstOrNull() ?: return false
        return c.isLetter() && c.isUpperCase()
    }

    /** 取 BNC/COCA 词频排名中较小的一个（越常见越小）；两者皆无 → 视为很难/不作为主测 */
    private fun difficultyRank(e: DictEntry): Int =
        listOfNotNull(e.bnc, e.frq).minOrNull() ?: Int.MAX_VALUE

    /** 取词频排名落在 [minRank, maxRank] 内的单字英文词（BNC/COCA 任一达标即可），按排名升序 */
    private fun fetchByRankWindow(minRank: Int, maxRank: Int, limit: Int): List<DictEntry> {
        if (!ready || maxRank <= 0 || minRank > maxRank) return emptyList()
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val out = mutableListOf<DictEntry>()
                db.rawQuery(
                    "SELECT word, phonetic, translation, pos, tag, bnc, frq FROM $TABLE " +
                        "WHERE instr(word, ' ') = 0 AND word GLOB '[A-Za-z]*[A-Za-z]' " +
                        "AND ((CAST(frq AS INTEGER) BETWEEN ? AND ?) OR (CAST(bnc AS INTEGER) BETWEEN ? AND ?)) " +
                        "ORDER BY COALESCE(NULLIF(CAST(frq AS INTEGER),0), NULLIF(CAST(bnc AS INTEGER),0), 999999) ASC, rowid ASC LIMIT ?",
                    arrayOf(minRank.toString(), maxRank.toString(), minRank.toString(), maxRank.toString(), limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            DictEntry(
                                word = c.getString(0),
                                phonetic = c.getString(1).ifBlank { null },
                                translation = c.getString(2).ifBlank { null },
                                pos = c.getString(3).ifBlank { null },
                                tag = c.getString(4).ifBlank { null },
                                bnc = c.getString(5).toIntOrNull(),
                                frq = c.getString(6).toIntOrNull()
                            )
                        )
                    }
                }
                out
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchByRankWindow failed", t)
            emptyList()
        }
    }

    /**
     * 取词频排名落在 [minRank, maxRank] 内的单字英文词（BNC/COCA 任一达标即可，按排名升序）。
     * 供中英混排改写引擎「按用户当前 CEFR 等级的词频区间注入未测试新词」使用。
     * @return 单词列表（原始大小写，按词频从常用到生僻）
     */
    fun fetchWordsByRankWindow(minRank: Int, maxRank: Int, limit: Int): List<String> {
        if (!ready || maxRank <= 0 || minRank > maxRank || limit <= 0) return emptyList()
        return fetchByRankWindow(minRank, maxRank, limit).map { it.word }
    }

    /** 取 [lo, hi] 区间内按 rowid 升序的前 limit 个词（仅单字英文词） */
    private fun fetchBulk(lo: Long, hi: Long, limit: Int): List<String> {
        if (!ready) return emptyList()
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val list = mutableListOf<String>()
                db.rawQuery(
                    "SELECT word FROM $TABLE WHERE rowid BETWEEN ? AND ? AND word GLOB '[A-Za-z]*[A-Za-z]' AND instr(word, ' ') = 0 ORDER BY rowid LIMIT ?",
                    arrayOf(lo.toString(), hi.toString(), limit.toString())
                ).use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { list.add(it) }
                }
                list
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchBulk failed", t)
            emptyList()
        }
    }

    /** 是否适合作为主测词：单个英文词、长度适中、并排除人名/专名/品牌/缩写。
     *  - 排除含空格短语、数字；限制长度（过长词冷僻且难，不作为主测题面）
     *  - 默认排除"首字符之后出现大写字母"的词（如 McDonald / iPhone / NASA / GMT），
     *    专名/人名/缩写不常见不常考，符合"高频常见为主、专名低频为辅"；
     *    兜底轮（lenient=true）时放宽，确保小型频带也能抽到词。 */
    private fun isSingleEnglishWord(raw: String, maxLen: Int = VocabGrader.MAX_WORD_LEN, lenient: Boolean = false): Boolean {
        val w = raw.trim()
        if (w.isEmpty() || w.length > maxLen) return false
        if (w.any { c -> !(c.isLetter() || c == '-' || c == '\'' || c == '’') }) return false
        if (w.none { it.isLetter() }) return false
        if (lenient) return true
        // 专名/缩写过滤：首字母后出现大写字母即视为专名
        var firstLetterSeen = false
        for (ch in w) {
            if (!ch.isLetter()) continue
            if (!firstLetterSeen) { firstLetterSeen = true; continue }
            if (ch.isUpperCase()) return false
        }
        return true
    }

    /** 简易 CSV 行解析（支持双引号包裹字段 + 转义双引号） */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < n && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }
}

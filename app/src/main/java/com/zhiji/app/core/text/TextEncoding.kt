package com.zhiji.app.core.text

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * TXT 文本「全编码」探测与容错解码工具。
 *
 * 背景：TXT 文件没有统一的编码声明，中文 TXT 常见 UTF-8 / UTF-8 BOM / UTF-16(LE/BE) /
 * GBK(GB18030) / Big5。旧实现一律按 UTF-8 解码，遇到 GBK 等编码就会产生大量
 * U+FFFD 替换符（界面上显示为一堆「？」图标），即「乱码」。
 *
 * 探测顺序（越前面越可靠，命中即返回）：
 *  1) BOM：UTF-8 / UTF-16LE / UTF-16BE
 *  2) NUL 字节启发式：无 BOM 的 UTF-16（ASCII 文本高字节为 0，按奇偶位置分布判定 LE/BE）
 *  3) 严格 UTF-8 解码成功 → UTF-8
 *  4) 候选打分：GB18030 与 Big5 各自严格解码后按字符「可信度」评分，取高者
 *  5) 全部失败 → GB18030 容错解码兜底（REPLACE，绝不抛异常）
 *
 * 所有读取接口都做了流式处理（只按需读取，不把整本书一次性载入内存），
 * 并支持 [maxChars] 只读前缀，用于大文件「快速首屏」。
 */
object TextEncoding {

    val UTF_8: Charset = StandardCharsets.UTF_8
    val UTF_16LE: Charset = StandardCharsets.UTF_16LE
    val UTF_16BE: Charset = StandardCharsets.UTF_16BE
    val GB18030: Charset = Charset.forName("GB18030")
    val BIG5: Charset = Charset.forName("Big5")

    /** 探测采样字节数：64KB 足够区分常见编码，且开销可控 */
    private const val SAMPLE_BYTES = 64 * 1024

    /** 判定「乱码」需要的最少替换符个数（1~2 个可能只是原文确有异常字符） */
    const val GARBLED_MIN_REPLACEMENTS = 3

    private const val REPLACEMENT = '\uFFFD'

    // ============================================================
    // 公开读取接口
    // ============================================================

    /**
     * 读取全部文本（编码感知）。整篇读入内存，适合小文件缓存。
     */
    fun readText(input: InputStream): String = readText(input, Int.MAX_VALUE)

    /**
     * 读取前 [maxChars] 个字符（编码感知，流式）。
     * 用于大文件快速首屏：只解码前缀，不把整本书载入内存。
     */
    fun readText(input: InputStream, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val reader = reader(input)
        val sb = StringBuilder(minOf(maxChars, 64 * 1024))
        val buf = CharArray(8192)
        try {
            while (sb.length < maxChars) {
                val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
        } finally {
            runCatching { reader.close() }
        }
        if (sb.isNotEmpty() && sb[0] == '\uFEFF') sb.deleteCharAt(0)
        return sb.toString()
    }

    /**
     * 打开「编码感知」的字符流：自动探测编码后从流头开始解码。
     * 调用方负责关闭返回的 Reader。
     */
    fun reader(input: InputStream): Reader {
        val buffered = if (input is BufferedInputStream) input
        else BufferedInputStream(input, 128 * 1024)
        val sample = ByteArray(SAMPLE_BYTES)
        var len = 0
        runCatching { buffered.mark(SAMPLE_BYTES + 1) }
        while (len < SAMPLE_BYTES) {
            val n = buffered.read(sample, len, SAMPLE_BYTES - len)
            if (n <= 0) break
            len += n
        }
        runCatching { buffered.reset() }
        val charset = detect(sample, len)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return BufferedReader(InputStreamReader(buffered, decoder), 64 * 1024)
    }

    /** 文本是否疑似乱码：替换符（U+FFFD）达到阈值即判定为乱码 */
    fun looksGarbled(text: String): Boolean {
        if (text.isEmpty()) return false
        var count = 0
        for (c in text) {
            if (c == REPLACEMENT) {
                count++
                if (count >= GARBLED_MIN_REPLACEMENTS) return true
            }
        }
        return false
    }

    // ============================================================
    // 编码探测
    // ============================================================

    private fun detect(bytes: ByteArray, len: Int): Charset {
        if (len <= 0) return UTF_8
        // ① BOM
        if (len >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return UTF_8
        if (len >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return UTF_16LE
        if (len >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return UTF_16BE
        // ② NUL 启发式：无 BOM 的 UTF-16（ASCII 文本高字节恒为 0）
        if (len >= 4) {
            val check = minOf(len, 4096)
            var evenNulls = 0
            var oddNulls = 0
            for (i in 0 until check) {
                if (bytes[i] == 0.toByte()) {
                    if (i % 2 == 0) evenNulls++ else oddNulls++
                }
            }
            val half = check / 2
            if (oddNulls > half * 0.4 && evenNulls < half * 0.1) return UTF_16LE
            if (evenNulls > half * 0.4 && oddNulls < half * 0.1) return UTF_16BE
        }
        // ③ 严格 UTF-8
        if (decodeStrict(bytes, len, UTF_8) != null) return UTF_8
        // ④ 候选打分：GB18030 vs Big5
        val gbText = decodeStrict(bytes, len, GB18030)
        val bigText = decodeStrict(bytes, len, BIG5)
        if (gbText == null && bigText == null) return GB18030 // ⑤ 兜底
        val gbScore = gbText?.let { score(it) } ?: -1.0
        val bigScore = bigText?.let { score(it) } ?: -1.0
        return if (gbScore >= bigScore) GB18030 else BIG5
    }

    /** 严格解码：出现非法/无法映射字符即返回 null（用 REPORT 模式探测） */
    private fun decodeStrict(bytes: ByteArray, len: Int, charset: Charset): String? = runCatching {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes, 0, len)).toString()
    }.getOrNull()

    /**
     * 字符「可信度」评分：落在常见区间的比例越高越可信。
     * 惩罚：替换符 / 私用区(PUA) / 控制字符（错误解码往往会落进这些区间）。
     */
    private fun score(text: String): Double {
        if (text.isEmpty()) return 0.0
        var good = 0
        for (c in text) {
            val code = c.code
            when {
                c.isWhitespace() -> good++
                code in 0x20..0x7E -> good++          // ASCII 可打印
                code in 0x4E00..0x9FFF -> good++       // CJK 统一表意
                code in 0x3000..0x303F -> good++       // CJK 标点
                code in 0xFF00..0xFFEF -> good++       // 全角字符
                code in 0x2000..0x206F -> good++       // 通用标点
                code == 0xFFFD.toInt() -> { /* 替换符：坏 */ }
                code in 0xE000..0xF8FF -> { /* 私用区：坏 */ }
                code < 0x20 -> { /* 控制字符：坏 */ }
                else -> { /* 其他：不计分 */ }
            }
        }
        return good.toDouble() / text.length
    }
}

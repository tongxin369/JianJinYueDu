package com.zhiji.app.ui.feature.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 基于 sherpa-onnx 的纯离线中文/英文朗读引擎。
 *
 * - 不联网、不依赖系统 TTS 引擎、无任何语音包下载
 * - 流水线预合成：按句拆文本，播放第 N 句的同时后台预合成第 N+1 句，
 *   实现句间无缝连读（不逐句停顿）
 * - 模型文件内置在 assets/vits-melo-tts-zh_en 下
 */
class OfflineTtsSpeaker(context: Context) {

    private companion object {
        const val MODEL_DIR = "vits-melo-tts-zh_en"
        // 每段最大字符数：超过则二次切短，保证流水线预合成追得上播放、无句间停顿
        const val MAX_SEG_CHARS = 14
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var playing = false

    // 递增以区分不同的朗读请求，避免旧协程与新一轮朗读并发写 AudioTrack
    @Volatile
    private var generation = 0

    /** 后台预加载模型并预创建 AudioTrack（进入界面即调用，降低首次朗读延迟）。 */
    fun preload() {
        scope.launch {
            try {
                val engine = ensureInitialized()
                ensureTrack(engine.sampleRate())
            } catch (_: Exception) {
                // 预加载失败则下次 speak 时再重试
            }
        }
    }

    /** 朗读一段文字；失败时通过 [onError] 在主线程回调提示。 */
    fun speak(
        text: String,
        onError: (String) -> Unit,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
        speed: Float = 1.0f
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // 让上一轮朗读协程退出（generation 已失效）
        val gen = ++generation
        playing = true

        scope.launch {
            val engine = try {
                ensureInitialized()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                postOnMain { onError("语音引擎加载失败，请重启应用重试") }
                playing = false
                return@launch
            }

            try {
                val sampleRate = engine.sampleRate()
                val t = ensureTrack(sampleRate)
                // 清空上一次未播完的音频，立即开始本次
                t.pause()
                t.flush()
                t.play()

                postOnMain(onStarted)

                val sentences = splitSentences(trimmed)
                val queue = LinkedBlockingQueue<Seg>(8)
                val producerDone = AtomicBoolean(false)

                // 生产者：逐段合成，放入队列（与播放并行，实现预合成）
                scope.launch {
                    try {
                        for (s in sentences) {
                            if (!playing || generation != gen) break
                            val audio = engine.generate(s, 0, speed)
                            if (audio.samples.isNotEmpty()) {
                                queue.put(Seg(audio.samples, isSentenceEnd(s)))
                            }
                        }
                    } catch (_: Exception) {
                        // 单段合成异常则跳过，继续后续
                    } finally {
                        producerDone.set(true)
                    }
                }

                // 分级静音：句末停顿约 260ms，子句/短切停顿约 90ms
                val sentenceSilence = FloatArray(sampleRate * 26 / 100)
                val clauseSilence = FloatArray(sampleRate * 9 / 100)

                // 消费者：从队列取音频，连续写入 AudioTrack，按句末/子句插入不同静音
                var totalFrames = 0L
                var firstSegment = true
                while (playing && generation == gen) {
                    val seg = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (seg == null) {
                        if (producerDone.get()) break
                        continue
                    }
                    if (!firstSegment) {
                        val silence = if (seg.isSentenceEnd) sentenceSilence else clauseSilence
                        val m = t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                        if (m > 0) totalFrames += m
                    }
                    val n = t.write(seg.samples, 0, seg.samples.size, AudioTrack.WRITE_BLOCKING)
                    if (n > 0) totalFrames += n
                    firstSegment = false
                }

                // 合成/播放结束（未被 stop 打断）时，等缓冲播完再通知
                if (playing && generation == gen && totalFrames > 0) {
                    awaitPlaybackDone(t, totalFrames)
                    postOnMain(onFinished)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                postOnMain { onError("语音合成失败") }
            }
        }
    }

    /** 停止当前朗读。 */
    fun stop() {
        playing = false
        generation++
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    fun release() {
        stop()
        scope.cancel()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { tts?.release() }
        tts = null
    }

    @Synchronized
    private fun ensureInitialized(): OfflineTts {
        tts?.let { return it }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$MODEL_DIR/model.onnx",
                    lexicon = "$MODEL_DIR/lexicon.txt",
                    tokens = "$MODEL_DIR/tokens.txt",
                ),
                numThreads = 8,
                debug = false,
                provider = "cpu",
            ),
            // 中文文本归一化规则（数字/日期/电话号码/多音字），缺失会导致朗读含糊不清
            ruleFsts = listOf(
                "$MODEL_DIR/date.fst",
                "$MODEL_DIR/number.fst",
                "$MODEL_DIR/phone.fst",
                "$MODEL_DIR/new_heteronym.fst",
            ).joinToString(","),
        )
        val engine = OfflineTts(assetManager = appContext.assets, config = config)
        tts = engine
        return engine
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let { if (it.sampleRate == sampleRate) return it }
        runCatching { track?.release() }
        val bufLen = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val t = AudioTrack(
            attr, format, bufLen, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = t
        return t
    }

    /**
     * 按标点将文本拆成片段，并对超长片段二次切分。
     * 关键：让每段都足够短（<= MAX_SEG_CHARS），保证每段合成耗时 < 前一段播放耗时，
     * 从而生产者流水线始终追得上播放，消除句间固定停顿。
     */
    private fun splitSentences(text: String): List<String> {
        val raw = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' ||
                ch == '.' || ch == '!' || ch == '?' || ch == ';' ||
                ch == '，' || ch == '、' || ch == ','
            ) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) raw.add(s)
                sb.clear()
            }
        }
        val s = sb.toString().trim()
        if (s.isNotEmpty()) raw.add(s)

        // 对超长段再按字符切短，保证每段都小
        val result = mutableListOf<String>()
        for (seg in raw) {
            if (seg.length <= MAX_SEG_CHARS) {
                result.add(seg)
            } else {
                var start = 0
                while (start < seg.length) {
                    val end = (start + MAX_SEG_CHARS).coerceAtMost(seg.length)
                    result.add(seg.substring(start, end))
                    start = end
                }
            }
        }
        return result
    }

    /** 判断一段文本是否以句末标点结尾（决定插入较长停顿）。 */
    private fun isSentenceEnd(s: String): Boolean = s.isNotEmpty() &&
        (s.last() == '。' || s.last() == '！' || s.last() == '？' || s.last() == '；' ||
            s.last() == '.' || s.last() == '!' || s.last() == '?' || s.last() == ';')

    /** 一段已合成的音频 + 是否句末（用于插入分级停顿）。 */
    private class Seg(val samples: FloatArray, val isSentenceEnd: Boolean)

    /** 等待 AudioTrack 将已写入的音频全部播完。 */
    private suspend fun awaitPlaybackDone(t: AudioTrack, totalFrames: Long) {
        while (playing && totalFrames > 0) {
            val played = t.playbackHeadPosition.toLong()
            if (played >= totalFrames) break
            delay(100)
        }
    }

    private fun postOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
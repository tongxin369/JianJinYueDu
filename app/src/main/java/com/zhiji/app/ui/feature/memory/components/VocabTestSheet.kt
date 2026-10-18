package com.zhiji.app.ui.feature.memory.components

import android.graphics.Typeface
import android.text.SpannableString
import android.text.method.ArrowKeyMovementMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiji.app.ZhiJiApp
import com.zhiji.app.core.algorithm.VocabGrader
import com.zhiji.app.core.algorithm.VocabTestSession
import com.zhiji.app.core.tts.TtsModelStore
import com.zhiji.app.ui.feature.ai.TtsDownloadDialog
import com.zhiji.app.data.database.entity.VocabHistoryEntity
import com.zhiji.app.data.database.entity.VocabProficiencyEntity
import com.zhiji.app.data.repository.EcdictRepository
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 词汇测试弹层的运行阶段（顶层声明，避免局部接口非法） */
private sealed interface VocabPhase {
    object Loading : VocabPhase
    object NotReady : VocabPhase
    data class Ask(val tier: Int, val idx: Int, val word: EcdictRepository.WordEntry) : VocabPhase
    data class Reveal(
        val tier: Int,
        val idx: Int,
        val word: EcdictRepository.WordEntry,
        /** 作答结果："known"=认识 / "semi"=不熟 / "unknown"=不认识 */
        val status: String,
        /** 卡片是否是新增的（false=已存在记忆库） */
        val isNewCard: Boolean
    ) : VocabPhase
    data class Done(val entity: VocabProficiencyEntity) : VocabPhase
}

/**
 * 词汇掌握度测试（自适应诊断）底部弹层。
 *
 * 流程：从 B1 起步 → 每级 5 词自评"认识/不认识" → 达标升/不达标降 → 定位 CEFR 上限。
 * 每题作答瞬间写入记忆库（known/unknown，sourceType=test），结束持久化 CEFR 汇总。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabTestSheet(
    onDismiss: () -> Unit,
    onFinished: () -> Unit
) {
    val repo = remember { ZhiJiApp.instance.vocabTestRepository }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<VocabPhase>(VocabPhase.Loading) }
    var runId by remember { mutableIntStateOf(0) }
    // 用户自选精确认知起点（如 0.15=A0.15）；null=按上次精确认知/默认自动
    var userStartPos by remember { mutableStateOf<Float?>(null) }
    var session by remember { mutableStateOf<VocabTestSession?>(null) }
    var seed by remember { mutableIntStateOf((System.currentTimeMillis() % 100000L).toInt()) }
    var showLevelPicker by remember { mutableStateOf(false) }
    var wordsPerTier by remember { mutableIntStateOf(VocabGrader.WORDS_PER_TIER) }
    val history by repo.history.collectAsStateWithLifecycle(initialValue = emptyList())

    // 前向引用：抽下一题词的函数（Kotlin 局部闭包，先声明后赋值）
    lateinit var loadNextWord: () -> Unit

    /** 抽下一题：会话结束→归档并进结果页；否则按当前精确认知位置的**窄频带**抽 1 题 */
    loadNextWord = Next@{
        val s = session ?: return@Next
        if (s.finished) {
            val total = s.answered
            val known = s.knownCount
            val semi = s.semiCount
            val unknown = total - known - semi
            val bands = listOf(VocabGrader.tierCodeFor(s.finalLevel) to s.knownCount)
            // 自适应引擎测出的等级档位（CEFR 粗档）
            val adaptiveEntity = repo.buildProficiencyEntity(s.finalLevel, s.finalSub, total, known, unknown, bands)
            // 顺序执行（绝不可并行）：先落自适应档位 → 再按"词库掌握比例"精化细分数 → 回写历史 →
            // 结果页与「记忆库词汇掌握度」卡片展示同一个"档位+比例小数"的等级，二者必然一致且平滑
            scope.launch(Dispatchers.IO) {
                runCatching { repo.saveTestResult(adaptiveEntity, s.finalLevel) }
                val stable = runCatching { repo.refreshGradeFromMastery() }.getOrNull() ?: adaptiveEntity
                runCatching { repo.patchLastHistoryEstimate(stable.subScore, stable.estVocabSize) }
                withContext(Dispatchers.Main) {
                    phase = VocabPhase.Done(stable)
                    onFinished()
                }
            }
        } else {
            val qIdx = s.answered
            scope.launch {
                try {
                    phase = VocabPhase.Loading
                    Log.i("VocabTest", "Q${qIdx + 1}/${wordsPerTier} pos=${"%.2f".format(s.pos)}")
                    val totalRows = runCatching { repo.dictRowCount() }.getOrDefault(0)
                    if (totalRows <= 0) { phase = VocabPhase.NotReady; return@launch }
                    val exclude = runCatching { repo.existingWordFronts() }.getOrDefault(emptySet())
                    val band = s.currentBandFrac()
                    val tierIdx = s.currentTier()
                    val wordSeed = (seed * 31 + qIdx * 17) % 100000
                    var words = runCatching {
                        repo.sampleForBand(band[0], band[1], tierIdx, s.pos, totalRows.toLong(), 1, exclude, wordSeed)
                    }.getOrDefault(emptyList())
                    // 窄带取空（该难度无单字词）→ 整层兜底一题；仍空则视为该难度已掌握并推进，避免卡死
                    if (words.isEmpty() && !s.finished) {
                        words = runCatching {
                            repo.sampleForTier(VocabGrader.TIERS[tierIdx], totalRows.toLong(), 1, exclude, wordSeed)
                        }.getOrDefault(emptyList())
                        if (words.isEmpty()) { s.answer(true) }
                    }
                    Log.i("VocabTest", "……band=${"%.4f".format(band[0])}~${"%.4f".format(band[1])} got=${words.size}")
                    when {
                        words.isNotEmpty() && !s.finished ->
                            phase = VocabPhase.Ask(tierIdx, qIdx, words[0])
                        s.finished ->
                            loadNextWord() // 兜底推进触发了结束 → 直接归档
                        else ->
                            phase = VocabPhase.NotReady
                    }
                } catch (t: Throwable) {
                    Log.e("VocabTest", "loadNextWord crashed", t)
                    phase = VocabPhase.NotReady
                }
            }
        }
    }

    LaunchedEffect(runId) {
        withContext(Dispatchers.IO) { runCatching { repo.ensureReady() } }
        if (!repo.isReady()) {
            phase = VocabPhase.NotReady
        } else {
            // 起始精确认知位置（0.00=A0.00 … 6.99=C2.99）+ 防倒退锚点：
            //   - 用户自选细分区间 → 从该子区间起步；
            //   - 未选 → 上次精确认知等级；无历史则默认 A2。
            val (startPos, floor) =
                if (userStartPos != null) userStartPos!! to floor(userStartPos!!).toInt()
                else runCatching { repo.preciseStartInfo() }.getOrDefault(2.0f to 0)
            val s = VocabTestSession(startPos = startPos, floorLevel = floor, maxQuestions = wordsPerTier)
            session = s
            loadNextWord()
        }
    }

    // 用户自选细分难度区间：level=A0..C2 粗档，sub=0..9（即 0.00-0.09 … 0.90-0.99）
    fun pickStart(level: Int, sub: Int) {
        val pos = (level.coerceIn(0, VocabGrader.TIERS.lastIndex) + (sub.coerceIn(0, 9)) / 10f)
        userStartPos = pos
        seed = (seed * 31 + 17) % 100000
        phase = VocabPhase.Loading
        runId++
        showLevelPicker = false
    }

    Dialog(
        onDismissRequest = onDismiss, // 返回键关闭
        properties = DialogProperties(
            // 返回键可关闭；点击外部仍不允许（固定弹窗）；内容用了 fillMaxWidth/fillMaxHeight → 必须关闭平台默认宽度
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            // 有界最大高度，绝不能用会变无穷的 fillMaxHeight(0.9f)
            modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp).padding(horizontal = 20.dp)
        ) {
            // 外层必须 fillMaxSize（match-parent、有界），否则 wrap-content 的 Column 会用无穷高度测子级 → 里层 scroll 闪退
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp)
            ) {
                Text(
                    text = "词汇掌握度测试",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "自适应诊断 · CEFR A0~C2，逐级抽词自评；点左上角等级按钮可自选起点",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(12.dp))
                // 可配置测试题量（题数越多判定越精准）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "测试题量",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    listOf(5, 10, 20, 30).forEach { n ->
                        FilterChip(
                            selected = wordsPerTier == n,
                            onClick = { wordsPerTier = n },
                            label = { Text("$n") },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.size(10.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.size(12.dp))
                // 可滚动内容区：weight(1f) 在有界(fillMaxSize)父级下有限，绝不能再 heightIn(wrap) 或嵌套 scroll
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                ) {

            when (val p = phase) {
                is VocabPhase.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text("正在从词典抽词…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }

                is VocabPhase.NotReady -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("词典尚未就绪，无法抽题。", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.size(14.dp))
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) { runCatching { repo.ensureReady() } }
                        runId++
                    }) { Text("重试") }
                    Spacer(modifier = Modifier.size(6.dp))
                    TextButton(onClick = onDismiss) { Text("取消") }
                }

                is VocabPhase.Ask -> AskView(
                    tier = p.tier,
                    idx = p.idx,
                    total = wordsPerTier,
                    word = p.word,
                    onPickLevel = { showLevelPicker = true },
                    onKnown = {
                        var isNew = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { repo.recordAnswer(p.word, "known") }
                            isNew = result.getOrNull()?.second ?: true
                        }
                        session?.answer(true) // 掌握 → 认知位置上移一小步（抽更难词）
                        phase = VocabPhase.Reveal(p.tier, p.idx, p.word, status = "known", isNewCard = isNew)
                    },
                    onSemi = {
                        var isNew = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { repo.recordAnswer(p.word, "semi") }
                            isNew = result.getOrNull()?.second ?: true
                        }
                        session?.answer(true, semi = true) // 半熟 → 小幅上移（会但不完全会）
                        phase = VocabPhase.Reveal(p.tier, p.idx, p.word, status = "semi", isNewCard = isNew)
                    },
                    onUnknown = {
                        var isNew = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { repo.recordAnswer(p.word, "unknown") }
                            isNew = result.getOrNull()?.second ?: true
                        }
                        session?.answer(false) // 不认识 → 位置下调较大步（抽更贴合当前水平的词）
                        phase = VocabPhase.Reveal(p.tier, p.idx, p.word, status = "unknown", isNewCard = isNew)
                    },
                    onGiveUpTier = {
                        // 「这一层不认识，降低」：记不认识，并额外下调精确认知位置
                        var isNew = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { repo.recordAnswer(p.word, "unknown") }
                            isNew = result.getOrNull()?.second ?: true
                        }
                        session?.answer(false)
                        session?.giveUp()
                        phase = VocabPhase.Reveal(p.tier, p.idx, p.word, status = "unknown", isNewCard = isNew)
                    }
                )

                is VocabPhase.Reveal -> RevealView(
                    tier = p.tier,
                    idx = p.idx,
                    total = wordsPerTier,
                    word = p.word,
                    status = p.status,
                    isNewCard = p.isNewCard,
                    isLastInTier = p.idx + 1 >= wordsPerTier,
                    onNext = {
                        phase = VocabPhase.Loading
                        loadNextWord() // 下一题按调整后的精确认知位置窄带抽词
                    }
                )

                is VocabPhase.Done -> DoneView(
                    entity = p.entity,
                    bandResults = p.entity.bandResults,
                    history = history,
                    onRetest = {
                        seed = (seed * 31 + 17) % 100000
                        phase = VocabPhase.Loading
                        runId++ // 触发 LaunchedEffect(runId) 重建会话并重新抽词
                    },
                    onDone = onDismiss
                )
            }

            // ===== 自选起始等级弹窗 =====
            if (showLevelPicker) {
                LevelPickerDialog(
                    onPick = { level, sub -> pickStart(level, sub) },
                    onDismiss = { showLevelPicker = false }
                )
            }
                }
            }
        }
    }
}

/** 起始等级选择器：先选大档 A0~C2，再在所选档内选 10 个细分区间（0.00-0.09 … 0.90-0.99）。
 *  选择细分区间后，抽卡会集中在该词频排名区间内，更精准地匹配难度。 */
@Composable
private fun LevelPickerDialog(
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var pickTier by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (pickTier == null) "选择起始测试等级（先选大档）"
                else "在 ${VocabGrader.TIERS[pickTier!!].code} 内选择细分区间"
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (pickTier == null) {
                    VocabGrader.TIERS.forEachIndexed { i, t ->
                        TextButton(onClick = { pickTier = i }, modifier = Modifier.fillMaxWidth()) {
                            Text("${t.code} ${t.name}  ▸")
                        }
                    }
                } else {
                    TextButton(onClick = { pickTier = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "← 返回选择大档",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val tier = pickTier!!
                    val tierCode = VocabGrader.TIERS[tier].code
                    // 三位小数细分（0.001 刻度）：sub=0..9 → 0.000-0.099 … 0.900-0.999
                    for (sub in 0..9) {
                        val lo = (sub * 100).toString().padStart(3, '0')
                        val hi = (sub * 100 + 99).toString().padStart(3, '0')
                        TextButton(
                            onClick = { onPick(tier, sub) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$tierCode.$lo ~ $tierCode.$hi")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 可选择文本组件，支持CAB菜单 */
@Composable
private fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium
) {
    val context = LocalContext.current
    val primaryArgb = androidx.compose.material3.MaterialTheme.colorScheme.primary.toArgb()
    val onSurfaceArgb = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.toArgb()
    val highlightArgb = androidx.core.graphics.ColorUtils.setAlphaComponent(primaryArgb, (255 * 0.25f).toInt())
    
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val tv = TextView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextColor(onSurfaceArgb)
                highlightColor = highlightArgb
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
                typeface = if (style.fontWeight == FontWeight.Bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                movementMethod = ArrowKeyMovementMethod.getInstance()
                
                customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        menu?.add(Menu.NONE, 1, Menu.NONE, "🔍 翻译")
                            ?.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        return true
                    }
                    
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        return when (item?.itemId) {
                            1 -> {
                                val start = selectionStart.coerceAtLeast(0)
                                val end = selectionEnd.coerceAtLeast(start)
                                if (start < end) {
                                    val selectedText = text.substring(start, end)
                                    Toast.makeText(context, "选中: $selectedText", Toast.LENGTH_SHORT).show()
                                }
                                mode?.finish()
                                true
                            }
                            else -> false
                        }
                    }
                    
                    override fun onDestroyActionMode(mode: ActionMode?) {}
                }
                setTextIsSelectable(true)
            }
            tv
        },
        update = { tv ->
            tv.text = SpannableString(text)
        }
    )
}

/** 答题视图：展示单词 + 音标，三个判断按钮（认识/不熟/不认识）+ 顶部"整层放弃"按钮 */
@Composable
private fun AskView(
    tier: Int,
    idx: Int,
    total: Int,
    word: EcdictRepository.WordEntry,
    onKnown: () -> Unit,
    onSemi: () -> Unit,
    onUnknown: () -> Unit,
    onGiveUpTier: () -> Unit,
    onPickLevel: () -> Unit
) {
    val ctx = LocalContext.current
    val speaker = remember { (ctx.applicationContext as ZhiJiApp).ttsSpeaker }
    var ttsDownloadVisible by remember { mutableStateOf(false) }
    val t = VocabGrader.TIERS[tier]
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左上角等级徽标可点击 → 自选起始等级（锚点也是能力探索入口）
            TextButton(onClick = onPickLevel) {
                Text(
                    text = "【${t.code} ${t.name}】$idx/$total ▾",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onGiveUpTier) { Text("这一层不认识，降低") }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SelectableText(
                    text = word.word,
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = if (word.phonetic.isNullOrBlank()) "" else "/${word.phonetic}/",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(10.dp))
                // 内置 TTS 朗读本词
                OutlinedButton(
                    onClick = {
                        if (!TtsModelStore.isReady(ctx)) {
                            ttsDownloadVisible = true
                        } else {
                            speaker.speak(text = word.word, onError = { msg ->
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            })
                        }
                    },
                    modifier = Modifier.height(40.dp)
                ) { Text("🔊 朗读", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onUnknown,
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("❓ 不认识", fontWeight = FontWeight.Medium) }
            Button(
                onClick = onSemi,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF9A825))
            ) { Text("⚠️ 不熟", fontWeight = FontWeight.Medium) }
            Button(
                onClick = onKnown,
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("✅ 认识", fontWeight = FontWeight.Medium) }
        }
        Spacer(modifier = Modifier.size(16.dp))
    }
    TtsDownloadDialog(
        visible = ttsDownloadVisible,
        onDismiss = { ttsDownloadVisible = false },
        onReady = {
            ttsDownloadVisible = false
            speaker.speak(text = word.word, onError = { msg ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            })
        }
    )
}

/** 作答确认视图：展示释义（学习机会），跳到下一题/下一层级 */
@Composable
private fun RevealView(
    tier: Int,
    idx: Int,
    total: Int,
    word: EcdictRepository.WordEntry,
    status: String,
    isNewCard: Boolean,
    isLastInTier: Boolean,
    onNext: () -> Unit
) {
    val ctx = LocalContext.current
    val speaker = remember { (ctx.applicationContext as ZhiJiApp).ttsSpeaker }
    var ttsDownloadVisible by remember { mutableStateOf(false) }
    val t = VocabGrader.TIERS[tier]
    val known = status == "known"
    val semi = status == "semi"
    val saveText = if (!isNewCard) {
        "📋 卡片已存在记忆库中"
    } else if (known) {
        "✅ 已记入「会的」"
    } else if (semi) {
        "⚠️ 已记入「不熟」"
    } else {
        "❓ 已记入「生词本」"
    }
    val tint = if (!isNewCard) {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    } else if (known) {
        Color(0xFF2E7D32)
    } else if (semi) {
        Color(0xFF8D6E00)
    } else {
        Color(0xFFEF6C00)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "【${t.code} ${t.name}】第 ${idx + 1}/$total 题",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (!isNewCard) Icons.Filled.Info else if (known) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                tint = tint
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = saveText,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SelectableText(
                    text = word.word,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.size(6.dp))
                SelectableText(
                    text = if (word.translation.isNullOrBlank()) "（词典未收录释义）" else word.translation,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                // === 等级标签 + BNC/COCA 词频排名数值（让用户知晓单词难度）===
                wordTagSummary(word)?.let { summary ->
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = summary,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                // 翻译界面也接入内置 TTS 朗读
                OutlinedButton(
                    onClick = {
                        if (!TtsModelStore.isReady(ctx)) {
                            ttsDownloadVisible = true
                        } else {
                            speaker.speak(text = word.word, onError = { msg ->
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            })
                        }
                    },
                    modifier = Modifier.height(40.dp)
                ) { Text("🔊 朗读", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Spacer(modifier = Modifier.size(18.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (isLastInTier) "完成本题组，判定层级" else "下一题")
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
    TtsDownloadDialog(
        visible = ttsDownloadVisible,
        onDismiss = { ttsDownloadVisible = false },
        onReady = {
            ttsDownloadVisible = false
            speaker.speak(text = word.word, onError = { msg ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            })
        }
    )
}

/** 单词难度标签摘要：国内年级/学段 + 考试标签 + BNC/COCA 原始词频排名数值 */
private fun wordTagSummary(w: EcdictRepository.WordEntry): String? {
    val parts = mutableListOf<String>()
    val rank = listOfNotNull(w.bnc, w.frq).minOrNull()
    if (rank != null) parts.add(VocabGrader.gradeByRank(rank))
    w.tag?.split(" ", "/", "，", ",")?.forEach { t ->
        VocabGrader.examTagLabel(t)?.let { if (it !in parts) parts.add(it) }
    }
    val rankText = buildString {
        if (w.bnc != null) append("BNC #${w.bnc}")
        if (w.bnc != null && w.frq != null) append(" · ")
        if (w.frq != null) append("COCA #${w.frq}")
    }
    if (rankText.isNotBlank()) parts.add(rankText)
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** 结果视图：CEFR 等级大字 + 概要 + 层级明细 + 成长曲线 + 重测 */
@Composable
private fun DoneView(
    entity: VocabProficiencyEntity,
    bandResults: String,
    history: List<VocabHistoryEntity>,
    onRetest: () -> Unit,
    onDone: () -> Unit
) {
    // 注意：整个 Done 已在外部可滚动列内，这里不能再套 verticalScroll（否则被测成无穷高度闪退）
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 层级卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.TrackChanges, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "当前词汇掌握等级",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = fineLabel(entity),
                    style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "CEFR ${entity.levelName}",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "细分进步度 ${entity.subScore}/999（0.001 最小刻度 · 每次微小进步都能看见）",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.95f)
                )
                if (entity.windowTotal > 0) {
                    Spacer(modifier = Modifier.size(4.dp))
                    val weightedKnown = entity.windowKnown + 0.5 * (entity.windowSemi ?: 0)
                    Text(
                        text = "当前等级词库掌握：已掌握 ${String.format("%.1f", weightedKnown)}/${entity.windowTotal} 词（已会${entity.windowKnown}+不熟${entity.windowSemi ?: 0}×0.5 · ${(entity.masteryRatio * 100).roundToInt()}%）",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.95f)
                    )
                }
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "国内教育对标：${VocabGrader.eduLabelByVocab(entity.estVocabSize)} 难度",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.95f)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = if (entity.estVocabSize > 0) "估计认知词汇量约 ${entity.estVocabSize} 词" else "刚起步入阶 · 认知词汇少于 500 词",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
        Spacer(modifier = Modifier.size(16.dp))

        // 概要
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniStat("作答", "${entity.testedTotal}", Modifier.weight(1f))
            MiniStat("认识", "${entity.testedKnown}", Modifier.weight(1f), Color(0xFF2E7D32))
            MiniStat("不熟", "${(entity.testedTotal - entity.testedKnown - entity.testedUnknown).coerceAtLeast(0)}", Modifier.weight(1f), Color(0xFF8D6E00))
            MiniStat("生词", "${entity.testedUnknown}", Modifier.weight(1f), Color(0xFFEF6C00))
        }
        Spacer(modifier = Modifier.size(14.dp))

        // 层级明细
        if (bandResults.isNotBlank()) {
            Text(
                text = "各层级认识数",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.size(6.dp))
            val pairs = bandResults.split(";").mapNotNull { s ->
                val p = s.split(":")
                if (p.size == 2) p[0] to p[1].toIntOrNull() else null
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pairs.forEach { (code, k) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(code, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                            Text("$k/${VocabGrader.WORDS_PER_TIER}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.size(18.dp))

        // ===== 成长曲线（细分进步度历史） =====
        Text(
            text = "成长曲线（各次细分进步度 0~99）",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.size(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            GrowthChart(history = history, modifier = Modifier.fillMaxWidth().height(120.dp).padding(12.dp))
        }
        Spacer(modifier = Modifier.size(14.dp))

        // 说明 + 按钮
        Text(
            text = "认识的词已写入记忆库「会的」，不认识的已写入「生词本」，后续阅读会按新水平调整难度。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(14.dp))
        ElevatedButton(
            onClick = onRetest,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Icon(Icons.Filled.Replay, contentDescription = null); Spacer(Modifier.size(6.dp)); Text("重新测试") }
        Spacer(modifier = Modifier.size(10.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("完成") }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary) {
    Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tint)
            Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 细分等级展示，如 "B2.035" / "A0.001"（三位小数刻度），由汇总实体计算（A0 也要细分，零基础也是进度） */
private fun fineLabel(entity: VocabProficiencyEntity): String {
    return "${entity.cefr}.${entity.subScore.coerceIn(0, 999).toString().padStart(3, '0')}"
}

/**
 * 细分进步度成长曲线（Canvas 手绘折线）：
 * X 为历史次数（时间先后），Y 为 subScore(0~999 三位小数刻度)。无记录时显示占位提示。
 */
@Composable
fun GrowthChart(history: List<VocabHistoryEntity>, modifier: Modifier = Modifier) {
    val values = history.takeLast(14).map { it.subScore.coerceIn(0, 999) }
    if (values.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无历史，完成一次测试后这里会画出成长曲线",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val lineColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val pointColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val gridColor = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = 8.dp.toPx()
        val chartW = w - pad * 2
        val chartH = h - pad * 2
        // 网格：0/ 50/ 99 三条横线
        listOf(0f, 0.5f, 1f).forEach { frac ->
            val y = pad + chartH * (1f - frac)
            drawLine(gridColor, Offset(pad, y), Offset(pad + chartW, y), strokeWidth = 1.dp.toPx())
        }
        if (values.size == 1) {
            val y = pad + chartH * (1f - values[0] / 999f)
            drawCircle(pointColor, radius = 4.dp.toPx(), center = Offset(pad + chartW / 2, y))
            return@Canvas
        }
        val step = chartW / (values.size - 1).toFloat()
        val pts = values.mapIndexed { i, v ->
            Offset(pad + step * i, pad + chartH * (1f - v / 999f))
        }
        pts.zipWithNext().forEach { (a, b) ->
            drawLine(lineColor, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        pts.forEach { drawCircle(pointColor, radius = 3.5.dp.toPx(), center = it) }
    }
}
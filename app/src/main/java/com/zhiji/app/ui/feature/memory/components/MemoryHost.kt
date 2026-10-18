package com.zhiji.app.ui.feature.memory.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiji.app.ZhiJiApp
import com.zhiji.app.core.algorithm.FsrsScheduler
import com.zhiji.app.core.algorithm.VocabGrader
import com.zhiji.app.data.database.entity.MemoryCardEntity
import com.zhiji.app.data.database.entity.VocabHistoryEntity
import com.zhiji.app.data.database.entity.VocabProficiencyEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆库卡片列表弹层类型（由顶部统计卡触发） */
private enum class CardListFilter(val title: String) {
    TOTAL("全部卡片"),
    DUE("待复习"),
    MASTERED("已掌握"),
    UNKNOWN("生词本")
}

/** 「待复习」列表的细分类目标签（status=null 表示全部） */
private data class CardSubTab(val label: String, val status: String?, val count: Int = 0)

/** 格式化卡片导入时间：yyyy-MM-dd HH:mm */
private fun formatCreateTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * Phase 2 记忆库宿主：
 *   TopAppBar + 4 统计卡(总/待/掌握/生词) + TabRow(待复习/已掌握/生词本)
 *   待复习 Tab 点击卡片弹复习弹窗，4 打分按钮 (Again/Hard/Good/Easy) → applyReview 事务写 DB
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { ZhiJiApp.instance.memoryRepository }
    val vocabRepo = remember(context) { ZhiJiApp.instance.vocabTestRepository }
    val scope = rememberCoroutineScope()

    var listFilter by remember { mutableStateOf<CardListFilter?>(null) }
    var reviewTarget by remember { mutableStateOf<MemoryCardEntity?>(null) }
    var showVocabTest by remember { mutableStateOf(false) }
    var showProficiencyDetail by remember { mutableStateOf(false) }
    // 「待复习」列表内细分：null=全部；"known"=会的 / "semi"=不熟 / "unknown"=生词
    var dueSub by remember { mutableStateOf<String?>(null) }

    val proficiency by vocabRepo.proficiency.collectAsStateWithLifecycle(initialValue = null)
    val prof = proficiency // 本地持久委派避开 smart-case 限制
    val history by vocabRepo.history.collectAsStateWithLifecycle(initialValue = emptyList())

    val dueCards by repository.dueCards.collectAsStateWithLifecycle(initialValue = emptyList())
    val masteredCards by repository.masteredCards.collectAsStateWithLifecycle(initialValue = emptyList())
    val unknownCards by repository.unknownCards.collectAsStateWithLifecycle(initialValue = emptyList())
    val allCards by repository.allCards.collectAsStateWithLifecycle(initialValue = emptyList())

    var dueCount by remember { mutableIntStateOf(0) }
    var masteredCount by remember { mutableIntStateOf(0) }
    var unknownCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                dueCount = runCatching { repository.countDue() }.getOrDefault(0)
                masteredCount = runCatching { repository.countMastered() }.getOrDefault(0)
                unknownCount = runCatching { repository.countUnknown() }.getOrDefault(0)
                totalCount = runCatching { repository.countAll() }.getOrDefault(0)
            }
            kotlinx.coroutines.delay(2000L) // 每 2s 静默刷新一次统计（FSRS update 不会触发 Flow 重新算总数）
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.PsychologyAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "记忆库",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 词汇掌握度测试入口卡片（自适应诊断 · CEFR A0~C2）=====
            item {
                Card(
                    onClick = { showProficiencyDetail = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Insights,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "词汇掌握度",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.size(2.dp))
                            Text(
                                text = when {
                                    prof == null -> "尚未测评 · 抽词自评快速定位你的真实词汇等级"
                                    else -> {
                                        val fine = "${prof.cefr}.${prof.subScore.coerceIn(0, 999).toString().padStart(3, '0')}"
                                        val vocab = if (prof.estVocabSize > 0) "约 ${prof.estVocabSize} 词" else "认知词汇不足 500 词"
                                        val weightedKnown = prof.windowKnown + 0.5 * prof.windowSemi
                                        val mastery = if (prof.windowTotal > 0) 
                                            " · 词库掌握 ${String.format("%.1f", weightedKnown)}/${prof.windowTotal}（已会${prof.windowKnown}+不熟${prof.windowSemi}×0.5）" 
                                        else ""
                                        "当前 CEFR $fine ${prof.levelName} · $vocab$mastery"
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { showProficiencyDetail = true },
                                contentPadding = PaddingValues(vertical = 0.dp)
                            ) {
                                Text(
                                    text = "查看当前等级详情 ▸",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Button(onClick = { showVocabTest = true }) {
                            Text(if (proficiency == null) "开始测试" else "重测")
                        }
                    }
                }
            }

            // 成长曲线（细分进步度历史，常驻仪表盘）
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (history.size >= 2) "进步成长曲线（最近 ${history.takeLast(14).size} 次）"
                            else if (history.size == 1) "进步成长曲线（已有 1 次记录）"
                            else "进步成长曲线（细分进步度 0~999）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        GrowthChart(history = history, modifier = Modifier.fillMaxWidth().height(110.dp))
                    }
                }
            }

            // 四张统计卡（2×2），点击弹出对应卡片列表
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard("总卡数", "$totalCount", Modifier.weight(1f), onClick = { listFilter = CardListFilter.TOTAL })
                        StatCard(
                            "待复习",
                            "$dueCount",
                            Modifier.weight(1f),
                            badge = if (dueCount > 0) MaterialTheme.colorScheme.primary else null,
                            onClick = { listFilter = CardListFilter.DUE }
                        )
                    }
                    Spacer(modifier = Modifier.size(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard("已掌握", "$masteredCount", Modifier.weight(1f), onClick = { listFilter = CardListFilter.MASTERED })
                        StatCard("生词本", "$unknownCount", Modifier.weight(1f), onClick = { listFilter = CardListFilter.UNKNOWN })
                    }
                }
            }
        }
    }

    // ===== 卡片列表弹层（点击统计卡弹出，可滑动查看，每卡显示导入日期）=====
    listFilter?.let { filter ->
        val baseCards = when (filter) {
            CardListFilter.TOTAL -> allCards
            CardListFilter.DUE -> dueCards
            CardListFilter.MASTERED -> masteredCards
            CardListFilter.UNKNOWN -> unknownCards
        }
        // 「待复习」细分三类：会的/不熟/生词
        val dueTabs = if (filter == CardListFilter.DUE) listOf(
            CardSubTab("全部", null, dueCards.size),
            CardSubTab("会的", "known", dueCards.count { it.status == "known" }),
            CardSubTab("不熟", "semi", dueCards.count { it.status == "semi" }),
            CardSubTab("生词", "unknown", dueCards.count { it.status == "unknown" })
        ) else emptyList()
        val cards = if (filter == CardListFilter.DUE && dueSub != null)
            baseCards.filter { it.status == dueSub } else baseCards
        CardListDialog(
            title = "${filter.title}（${cards.size}）",
            cards = cards,
            tabs = dueTabs,
            selectedTab = dueSub,
            onSelectTab = { dueSub = it },
            onDismiss = { listFilter = null },
            onDelete = { card ->
                scope.launch { runCatching { repository.deleteCard(card.id) } }
                Toast.makeText(context, "已删除卡片", Toast.LENGTH_SHORT).show()
            },
            onCardClick = { card ->
                // 复习弹层叠在列表之上，列表保持打开（不再关闭）
                reviewTarget = card
            }
        )
    }

    // ===== 词汇掌握度测试弹层 =====
    if (showVocabTest) {
        VocabTestSheet(
            onDismiss = { showVocabTest = false },
            onFinished = { /* CEFR 汇总已保存，proficiency Flow 自动刷新入口卡片 */ }
        )
    }

    // ===== 词汇掌握度详情弹层（点击「词汇掌握度」查看当前等级详细诊断卡片）=====
    if (showProficiencyDetail) {
        ProficiencyDetailDialog(
            prof = prof,
            history = history,
            onRetest = { showProficiencyDetail = false; showVocabTest = true },
            onDismiss = { showProficiencyDetail = false }
        )
    }

    // ===== 复习弹窗（Again/Hard/Good/Easy 四按钮打分） =====
    val target = reviewTarget
    if (target != null) {
        var toastMsg by remember(target.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(toastMsg) {
            val m = toastMsg
            if (m != null) {
                Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
                toastMsg = null
            }
        }
        val front = target.front
        val back = target.back.ifBlank { null }
        var showBack by remember(target.id) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { reviewTarget = null },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Again = 1
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val r = repository.applyReview(target.id, FsrsScheduler.RATING_AGAIN)
                                toastMsg = if (r == null) "打分失败" else "Again：${r.reviewLog.intervalDaysAfter}天后再复习"
                                showBack = false
                                reviewTarget = null
                            }
                        }
                    ) { Text("忘光", color = MaterialTheme.colorScheme.error) }

                    // Hard = 2
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val r = repository.applyReview(target.id, FsrsScheduler.RATING_HARD)
                                toastMsg = if (r == null) "打分失败" else "Hard：${r.reviewLog.intervalDaysAfter}天后再复习"
                                showBack = false
                                reviewTarget = null
                            }
                        }
                    ) { Text("难记") }

                    // Good = 3
                    ElevatedButton(
                        onClick = {
                            scope.launch {
                                val r = repository.applyReview(target.id, FsrsScheduler.RATING_GOOD)
                                toastMsg = if (r == null) "打分失败" else "Good：${r.reviewLog.intervalDaysAfter}天后再复习"
                                showBack = false
                                reviewTarget = null
                            }
                        }
                    ) { Text("记住了") }

                    // Easy = 4
                    Button(
                        onClick = {
                            scope.launch {
                                val r = repository.applyReview(target.id, FsrsScheduler.RATING_EASY)
                                toastMsg = if (r == null) "打分失败" else "Easy：${r.reviewLog.intervalDaysAfter}天后再复习"
                                showBack = false
                                reviewTarget = null
                            }
                        }
                    ) { Text("简单") }
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewTarget = null }) {
                    Text("跳过")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when (target.status) {
                                    "known" -> "✅ 会的"
                                    "semi" -> "⚠️ 不熟"
                                    "unknown" -> "❓ 生词"
                                    else -> target.status
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (target.status) {
                                    "unknown" -> Icons.Filled.HelpCenter
                                    "semi" -> Icons.Filled.Info
                                    else -> Icons.Filled.CheckCircle
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    target.bookTitle?.let {
                        AssistChip(
                            onClick = {},
                            label = { Text(it.take(14), fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { reviewTarget = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 卡片正面（问题）
                    Text(
                        text = "📌 正面（问题）",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = front,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(14.dp),
                            lineHeight = 22.sp
                        )
                    }

                    if (!showBack) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                )
                                .then(Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedButton(onClick = { showBack = true }) {
                                Text("👀 点击显示背面（释义/笔记）")
                            }
                        }
                    } else if (back != null) {
                        Text(
                            text = "💡 背面（释义/笔记）",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Card(
                            onClick = { showBack = false }, // Anki 式：点击释义区域可翻回正面
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(
                                    text = back,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    text = "点击此处可翻回正面",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无释义/笔记（Phase 4 AI 自动生成）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    badge: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(8.dp)
                        .background(
                            badge,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun MemoryCardItem(
    card: MemoryCardEntity,
    showReview: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val front = card.front
    val preview = if (front.length <= 70) front else front.take(70) + "…"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (showReview) Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    )
                else Modifier
            )
            .padding(vertical = 2.dp),
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when (card.status) {
                                "known" -> "✅ 会的"
                                "semi" -> "⚠️ 不熟"
                                "unknown" -> "❓ 生词"
                                else -> card.status
                            },
                            fontSize = 11.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = when (card.status) {
                                "unknown" -> Icons.Filled.HelpCenter
                                "semi" -> Icons.Filled.Info
                                else -> Icons.Filled.CheckCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                val fsrsStateText = when (card.fsrsState) {
                    0 -> "新卡"
                    1 -> "学习中"
                    2 -> "复习"
                    3 -> "重学"
                    else -> ""
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            "$fsrsStateText · ${card.fsrsIntervalDays}天",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                card.bookTitle?.let {
                    Text(
                        text = it.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除此卡片",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = "📥 导入于 ${formatCreateTime(card.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 卡片列表弹层：标题 + 可滑动卡片列表（可点卡片进入复习）；「待复习」支持细分三类 */
@Composable
private fun CardListDialog(
    title: String,
    cards: List<MemoryCardEntity>,
    onDismiss: () -> Unit,
    onDelete: (MemoryCardEntity) -> Unit,
    onCardClick: (MemoryCardEntity) -> Unit,
    tabs: List<CardSubTab> = emptyList(),
    selectedTab: String? = null,
    onSelectTab: (String?) -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                // 「待复习」细分分类栏：会的/不熟/生词
                if (tabs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tabs.forEach { tab ->
                            FilterChip(
                                selected = selectedTab == tab.status,
                                onClick = { onSelectTab(tab.status) },
                                label = {
                                    Text(
                                        if (tab.count > 0) "${tab.label} ${tab.count}" else tab.label,
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
                if (cards.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("列表为空", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(cards, key = { it.id }) { card ->
                            MemoryCardItem(
                                card = card,
                                showReview = false,
                                onClick = { onCardClick(card) },
                                onDelete = { onDelete(card) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 细分等级展示，如 "B2.035" / "A0.001"（三位小数刻度），与测试结果页一致 */
private fun fineLabel(prof: VocabProficiencyEntity): String {
    return "${prof.cefr}.${prof.subScore.coerceIn(0, 999).toString().padStart(3, '0')}"
}

/**
 * 词汇掌握度详情弹层：点击「词汇掌握度」弹出的诊断结果展示卡片。
 * 展示当前所在 CEFR 等级及其详细内容（细分进步度、词库掌握、教育对标、估算词汇量、成长曲线），
 * 与抽卡测试结果页(DoneView)同源同算法，因此两者等级必然一致。
 */
@Composable
private fun ProficiencyDetailDialog(
    prof: VocabProficiencyEntity?,
    history: List<VocabHistoryEntity>,
    onRetest: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 760.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "词汇掌握度详情",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                if (prof == null) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Insights,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("尚未测评，无法展示当前等级。", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("点击「开始测试」抽词自评定位你的真实词汇等级。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.size(16.dp))
                            Button(onClick = onRetest) { Text("开始测试") }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // 等级主卡片
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "当前词汇掌握等级",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = fineLabel(prof),
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Black,
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                                Text(
                                    text = "CEFR ${prof.levelName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = "细分进步度 ${prof.subScore}/999（0.001 最小刻度）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f)
                                )
                                if (prof.windowTotal > 0) {
                                    Spacer(modifier = Modifier.size(6.dp))
                                    val weightedKnown = prof.windowKnown + 0.5 * prof.windowSemi
                                    Text(
                                        text = "当前等级词库掌握：已掌握 ${String.format("%.1f", weightedKnown)}/${prof.windowTotal} 词（已会${prof.windowKnown}+不熟${prof.windowSemi}×0.5 · ${(prof.masteryRatio * 100).roundToInt()}%）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f)
                                    )
                                }
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = "国内教育对标：${VocabGrader.eduLabelByVocab(prof.estVocabSize)} 难度",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = if (prof.estVocabSize > 0) "估计认知词汇量约 ${prof.estVocabSize} 词" else "刚起步入阶 · 认知词汇少于 500 词",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        // 汇总统计
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailStat("作答", "${prof.testedTotal}", Modifier.weight(1f))
                            DetailStat("认识", "${prof.testedKnown}", Modifier.weight(1f), androidx.compose.ui.graphics.Color(0xFF2E7D32))
                            DetailStat("不熟", "${(prof.testedTotal - prof.testedKnown - prof.testedUnknown).coerceAtLeast(0)}", Modifier.weight(1f), androidx.compose.ui.graphics.Color(0xFF8D6E00))
                            DetailStat("生词", "${prof.testedUnknown}", Modifier.weight(1f), androidx.compose.ui.graphics.Color(0xFFEF6C00))
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        // 等级描述
                        if (prof.bandResults.isNotBlank()) {
                            Text(
                                text = "各层级认识数",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            val pairs = prof.bandResults.split(";").mapNotNull { s ->
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
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(code, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            Text("$k/${VocabGrader.WORDS_PER_TIER}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.size(18.dp))
                        // 成长曲线
                        Text(
                            text = "成长曲线（各次细分进步度 0~999）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            GrowthChart(history = history, modifier = Modifier.fillMaxWidth().height(120.dp).padding(12.dp))
                        }
                        Spacer(modifier = Modifier.size(18.dp))
                        ElevatedButton(
                            onClick = onRetest,
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) { Text("重新测试") }
                        Spacer(modifier = Modifier.size(10.dp))
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tint)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

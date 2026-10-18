package com.zhiji.app.ui.feature.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhiji.app.data.repository.EcdictRepository
import com.zhiji.app.ui.feature.reader.ReaderViewModel

/**
 * 阅读器注释编辑窗（三 Tab + 预留接口）：
 *  - Tab 0 AI解释：示范占位页（可点击进入，非Disabled），预留 onRequestAiExplain 接口 Phase 4 接大模型
 *  - Tab 1 词典释义：示范占位页（可点击进入，非Disabled），预留 onRequestDictionary 接口 Phase 2 后段接 ECDICT
 *  - Tab 2 自定义：手填 TextField（默认选中）
 *  - 小窗 / 全屏双形态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAnnotationEditor(
    state: ReaderViewModel.AnnotationEditorState,
    onDismiss: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    // ========== 未来功能预留接口（示范页点击按钮会调用，Phase 实现时对接即可） ==========
    onRequestAiExplain: (selectedText: String) -> Unit = {},
    onRequestDictionary: (selectedText: String) -> Unit = {},
    // v42 离线词典：Tab1「词典释义」真实查询结果
    dictLookup: ReaderViewModel.DictLookupState? = null,
    onLookupDictionary: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 三 Tab：默认选中自定义（唯一可用的实际编辑），但 0/1 都可点击进入示范页
    var tab by remember(state.existingAnnotationId) { mutableIntStateOf(2) }

    if (state.fullscreen) {
        EditorFullscreen(
            state = state,
            tab = tab,
            onTabChanged = { tab = it },
            onDismiss = onDismiss,
            onToggleFullscreen = onToggleFullscreen,
            onNoteChange = onNoteChange,
            onSave = onSave,
            onDelete = onDelete,
            onRequestAiExplain = onRequestAiExplain,
            onRequestDictionary = onRequestDictionary,
            dictLookup = dictLookup,
            onLookupDictionary = onLookupDictionary,
            modifier = modifier
        )
    } else {
        EditorBubble(
            state = state,
            tab = tab,
            onTabChanged = { tab = it },
            onDismiss = onDismiss,
            onToggleFullscreen = onToggleFullscreen,
            onNoteChange = onNoteChange,
            onSave = onSave,
            onDelete = onDelete,
            onRequestAiExplain = onRequestAiExplain,
            onRequestDictionary = onRequestDictionary,
            dictLookup = dictLookup,
            onLookupDictionary = onLookupDictionary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorFullscreen(
    state: ReaderViewModel.AnnotationEditorState,
    tab: Int,
    onTabChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onRequestAiExplain: (String) -> Unit,
    onRequestDictionary: (String) -> Unit,
    // v42 离线词典
    dictLookup: ReaderViewModel.DictLookupState?,
    onLookupDictionary: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (state.existingAnnotationId == null) "新增注释" else "编辑注释",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "返回小窗")
                        }
                        if (state.existingAnnotationId != null) {
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除注释", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.existingAnnotationId != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("删除", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(48.dp)
                    ) { Text("取消") }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                    ) { Text("保存", fontWeight = FontWeight.SemiBold) }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 原文块
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "选中内容（第 ${state.paragraphIndex + 1} 段 · ${state.selectedText.length} 字）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = state.selectedText,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))

                // 三 Tab（全部可点击，0/1 进入示范页，2 编辑）
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { onTabChanged(0) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("AI 解释", fontSize = 13.sp)
                            }
                        }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { onTabChanged(1) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.MenuBook, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("词典释义", fontSize = 13.sp)
                            }
                        }
                    )
                    Tab(
                        selected = tab == 2,
                        onClick = { onTabChanged(2) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Psychology, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("备注", fontSize = 13.sp, fontWeight = if (tab == 2) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    )
                }

                Spacer(Modifier.size(12.dp))

                when (tab) {
                    0 -> AiExplainDemoPage(
                        selectedText = state.selectedText,
                        onClickGenerate = { onRequestAiExplain(state.selectedText) }
                    )
                    1 -> DictionaryResultPage(
                        word = state.selectedText,
                        lookup = dictLookup,
                        compact = false,
                        onLookup = onLookupDictionary
                    )
                    else -> CustomNoteTabFullscreen(
                        state = state,
                        onNoteChange = onNoteChange
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorBubble(
    state: ReaderViewModel.AnnotationEditorState,
    tab: Int,
    onTabChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onRequestAiExplain: (String) -> Unit,
    onRequestDictionary: (String) -> Unit,
    // v42 离线词典
    dictLookup: ReaderViewModel.DictLookupState?,
    onLookupDictionary: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp), clip = true),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                // 顶栏：标题 / 全屏 / 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (state.existingAnnotationId == null) "新增注释" else "编辑注释",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = "全屏编辑")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                // 原文预览
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = state.selectedText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp,
                        maxLines = 3,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(Modifier.size(10.dp))

                // 三 Tab（全部可点击）
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { onTabChanged(0) },
                        text = { Text("AI 解释", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { onTabChanged(1) },
                        text = { Text("词典释义", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = tab == 2,
                        onClick = { onTabChanged(2) },
                        text = {
                            Text(
                                "备注",
                                fontSize = 12.sp,
                                fontWeight = if (tab == 2) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }

                Spacer(Modifier.size(10.dp))

                when (tab) {
                    0 -> AiExplainDemoPage(
                        selectedText = state.selectedText,
                        compact = true,
                        onClickGenerate = { onRequestAiExplain(state.selectedText) }
                    )
                    1 -> DictionaryResultPage(
                        word = state.selectedText,
                        lookup = dictLookup,
                        compact = true,
                        onLookup = onLookupDictionary
                    )
                    else -> CustomNoteTabBubble(
                        state = state,
                        onNoteChange = onNoteChange
                    )
                }

                Spacer(Modifier.size(12.dp))

                // 操作按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.existingAnnotationId != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.height(44.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("删除", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(44.dp)
                    ) { Text("取消", fontSize = 13.sp) }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) { Text("保存", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                }
            }
        }
    }
}

// ============================================================
// Tab 0 示范页：AI 解释（Phase 4 接入大模型的占位+接口预留）
// ============================================================
@Composable
private fun AiExplainDemoPage(
    selectedText: String,
    compact: Boolean = false,
    onClickGenerate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier.height(220.dp) else Modifier.height(360.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compact) 14.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.AutoAwesome, null,
                    modifier = Modifier.size(if (compact) 28.dp else 40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(if (compact) 6.dp else 10.dp))
                Text(
                    text = "AI 自动解释",
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.size(if (compact) 4.dp else 8.dp))
                Text(
                    text = "选中：\"${selectedText.take(24)}${if (selectedText.length > 24) "…" else ""}\"",
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(if (compact) 4.dp else 6.dp))
                Text(
                    text = "Phase 4 上线后支持：翻译整段、背景介绍、语法分析、记忆技巧、例句、文化典故…生成的内容可一键合并到备注保存。",
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = if (compact) 18.sp else 22.sp
                )
            }
            // 预留接口按钮（Phase 4 接入大模型时，把 ReaderViewModel 的 aiRepository 调用接进来即可）
            Button(
                onClick = onClickGenerate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 42.dp else 48.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("🔜 一键 AI 解释（示范）", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

// ============================================================
// Tab 1：词典释义（v42 真实 ECDICT 离线查询）
// ============================================================
@Composable
private fun DictionaryResultPage(
    word: String,
    lookup: ReaderViewModel.DictLookupState?,
    compact: Boolean = false,
    onLookup: (String) -> Unit
) {
    // 选中文字变化时自动查词典（幂等，ViewModel 内已去重）
    LaunchedEffect(word) {
        if (word.isNotBlank()) onLookup(word)
    }
    val effective = if (lookup != null && lookup.word.trim() == word.trim()) lookup else null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier.height(220.dp) else Modifier.height(360.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f))
    ) {
        when {
            effective?.loading == true -> DictionaryStatus(compact, "查询中…", "正在离线词典中查找")
            effective?.entry != null -> DictionaryEntryContent(effective.entry!!, compact)
            effective != null -> DictionaryStatus(
                compact,
                "未收录「${effective.word.take(20)}」",
                "该词不在精简词库中，可换选一个单词查询"
            )
            else -> DictionaryStatus(compact, "选中英文单词查询释义", "打开注释窗 → 词典释义")
        }
    }
}

@Composable
private fun DictionaryStatus(compact: Boolean, title: String, desc: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 14.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.MenuBook, null,
            modifier = Modifier.size(if (compact) 28.dp else 40.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.size(if (compact) 6.dp else 10.dp))
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.size(if (compact) 4.dp else 8.dp))
        Text(
            text = desc,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = if (compact) 18.sp else 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryEntryContent(entry: EcdictRepository.DictEntry, compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 14.dp else 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.word,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!entry.phonetic.isNullOrBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "/ ${entry.phonetic} /",
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        if (!entry.pos.isNullOrBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = entry.pos,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // v45/v46 国内等级标签（A+B）：年级（词频）+ 考试标签（tag），标签多时自动换行
        val grades = entry.gradeLabels()
        if (grades.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                grades.forEach { g ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = g,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        // v47 原始词频数值（BNC/COCA 语料库排名，越小越常用）
        val freq = entry.freqText()
        if (freq != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = freq,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = entry.translation ?: "（暂无中文释义）",
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = if (compact) 18.sp else 24.sp
        )
    }
}

// ============================================================
// Tab 2：自定义注释（编辑 TextField，真正可用）
// ============================================================
@Composable
private fun CustomNoteTabFullscreen(
    state: ReaderViewModel.AnnotationEditorState,
    onNoteChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "我的备注",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(6.dp))
        BasicTextField(
            value = state.note,
            onValueChange = onNoteChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(14.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                if (state.note.isEmpty()) {
                    Text(
                        text = "写下你的注释、释义、思考…（可留空，仅下划线标记）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                innerTextField()
            }
        )
    }
}

@Composable
private fun CustomNoteTabBubble(
    state: ReaderViewModel.AnnotationEditorState,
    onNoteChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        BasicTextField(
            value = state.note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxSize(),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                if (state.note.isEmpty()) {
                    Text(
                        "写下你的注释（可留空）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                innerTextField()
            }
        )
    }
}

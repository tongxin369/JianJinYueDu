package com.zhiji.app.ui.feature.reader.components

import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.HelpCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastSumBy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiji.app.core.algorithm.ZhEnRewriter
import com.zhiji.app.data.database.entity.BookAnnotationEntity
import com.zhiji.app.data.database.entity.BookEntity
import com.zhiji.app.core.tts.TtsModelStore
import com.zhiji.app.ui.feature.ai.OfflineTtsSpeaker
import com.zhiji.app.ui.feature.ai.TtsDownloadDialog
import com.zhiji.app.ui.feature.reader.ReaderViewModel
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文 TextView 排版参数（与 ParagraphItem 中 setTextSize / setLineSpacing 严格一致，测量分页依赖它） */
private const val PARA_TEXT_SP = 16f
private const val PARA_LINE_SP = 26f
/** 页内水平留白（OriginalPagerView padding 10dp × 2 + ParagraphItem padding 4dp × 2） */
private const val PAGER_H_PADDING_DP = 28f
/** 页内垂直留白（OriginalPagerView padding 8dp × 2）+ 底部页数进度 chip 预留 */
private const val PAGER_V_PADDING_DP = 16f
private const val PAGER_BOTTOM_RESERVE_DP = 34f

/**
 * 沉浸式功能栏：正文 TextView 基类。
 * 在 dispatchTouchEvent 层检测「单击」（非长按 / 非拖动 / 无选中）→ 回调 onPlainTap，
 * 用于「点击正文中间区域弹出/收起上下功能栏」。与 resolveTap 同层，100% 不受
 * Compose wrapper / SelectionController 吞事件影响。
 */
internal open class PlainTapDetectTextView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : android.widget.TextView(context, attrs, defStyleAttr) {

    /** 单击正文（非长按/非拖动/无选中/非注释命中）时回调，用于切换功能栏显隐 */
    var onPlainTap: () -> Unit = {}

    /**
     * 单击正文且需按「屏幕坐标分区」处理时回调（rawX = 屏幕绝对 x）。
     * 设置后优先于 [onPlainTap]：翻页式阅读中 左 1/3=上一页、右 1/3=下一页、中间=切换功能栏。
     */
    var onPlainTapAt: ((rawX: Float) -> Unit)? = null

    /**
     * 单击命中某个字符位置时回调：返回 true 表示本次单击已被消费（不再触发 [onPlainTap]）。
     * 用于改写正文「单击英文单词 → 弹出原意卡片」：命中就不要同时切换功能栏。
     * @param charOffset 被点字符在文本中的偏移（词尾外侧一格也可能取到，命中判定需带容差）
     * @param tapX/tapY  触摸点在 View 内的坐标（用于算卡片锚点）
     */
    var onCharTap: ((charOffset: Int, tapX: Float, tapY: Float) -> Boolean)? = null

    /** 子类可在本次 UP 前置 true 抑制单击回调（如命中注释 Span 时不切功能栏） */
    var suppressPlainTap: Boolean = false

    private var lastDownX = 0f
    private var lastDownY = 0f
    private var lastDownTime = 0L
    private var longPressThisGesture = false

    override fun performLongClick(): Boolean {
        longPressThisGesture = true
        return super.performLongClick()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        runCatching {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastDownX = event.x
                    lastDownY = event.y
                    lastDownTime = event.eventTime
                    longPressThisGesture = false
                    suppressPlainTap = false
                }
                MotionEvent.ACTION_UP -> {
                    val suppressed = suppressPlainTap
                    suppressPlainTap = false
                    if (!suppressed) maybeFirePlainTap(event)
                }
                else -> Unit
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun maybeFirePlainTap(event: MotionEvent) {
        if (longPressThisGesture) return
        // 时长 < 长按阈值（长按已被 performLongClick 标记，这里再兜底一次）
        if (event.eventTime - lastDownTime >= ViewConfiguration.getLongPressTimeout().toLong()) return
        // 位移 < 触摸滑动阈值（拖动滚动不算单击）
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        if (hypot((event.x - lastDownX).toDouble(), (event.y - lastDownY).toDouble()) >= slop) return
        // 有真实选中（长按选字后未清除）不算单击
        val s = selectionStart
        val e = selectionEnd
        if (s >= 0 && e >= 0 && s != e) return
        // 先给「字符级单击」机会消费本次点击（改写正文：单击英文单词 → 弹原意卡片，不再切换功能栏）
        val tap = onCharTap
        if (tap != null) {
            val offset = runCatching { getOffsetForPosition(event.x, event.y) }.getOrNull()
            if (offset != null && runCatching { tap(offset, event.x, event.y) }.getOrDefault(false)) return
        }
        val at = onPlainTapAt
        if (at != null) runCatching { at(event.rawX) } else runCatching { onPlainTap() }
    }
}

/**
 * ==============================================================
 * 方案 B 核心：自定义 AnnotationHitTextView（静读天下 Android 同款思路）
 * ==============================================================
 *  业内根因（CodeGenes 公开修复 + 逆向微信读书 Android）：
 *     setTextIsSelectable(true) 后，SelectionController + Compose AndroidView wrapper
 *     会把 setOnTouchListener 的 ACTION_UP/ACTION_CANCEL 「吞掉」（父类拦截为滚动或选字光标更新）
 *     → 哪怕 onTouch 返回 tvResult=true，有时还是收不到 resolveTap 触发，导致连 Toast 都不弹。
 *
 *  解决：在 dispatchTouchEvent 这一层拦截（API 1 就有的最外层入口）：
 *     dispatchTouchEvent 是事件进入 View 后第一个被调用的方法（先于 onInterceptTouchEvent /
 *     onTouchEvent / SelectionController），Compose 再怎么包装 wrapper，最终都要 call
 *     View.dispatchTouchEvent → 这一层 100% 能收到完整的 DOWN/MOVE/UP/CANCEL 事件链。
 *
 *  暴露 3 个回调给 ParagraphItem 设置：
 *    onBeforeDown()        → ACTION_DOWN 第一时间 call（requestDisallowIntercept + 清他段浮条）
 *    onOtherParagraphDown → ACTION_DOWN 且点的段和当前选中段不一致时 call
 *    resolveTap(ev, reason) → UP/CANCEL 解算 Span 命中 + 返回是否消费（true=拦截）
 * ==============================================================
 */
internal class AnnotationHitTextView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : PlainTapDetectTextView(context, attrs, defStyleAttr) {

    /** 外部设置段索引（调试打 Tag） */
    var paragraphIndex: Int = -1

    /** ACTION_DOWN 最开始调用（用于 parent.requestDisallowIntercept + 清旧浮条） */
    var onBeforeDown: () -> Unit = {}
    /** ACTION_UP/CANCEL 最后调用（必须与 onBeforeDown 对称，重置父类滚动拦截权限，避免永久无法滑动） */
    var onAfterUpOrCancel: () -> Unit = {}
    /** ACTION_DOWN 且点的段和当前 selectionParagraphIndex 不一致时调用（用于 dismissSelection） */
    var onOtherParagraphTouched: () -> Unit = {}
    /** UP/CANCEL 解算 Span 命中；返回值：true 表示命中消费了该事件，false 交给系统流程 */
    var resolveTap: (ev: MotionEvent, reason: String) -> Boolean = { _, _ -> false }

    // ════════════════════════════════════════════════════════════════════════
    //  ⚠️ v20：100% 保留系统原生 CheckForLongPress / SelectionController
    //   不做任何自实现长按（不和系统成熟代码对抗）。
    //   仅增加：SpanWatcher 监听 SELECTION_START/END Span 变化，
    //   用于 NoActionBar 主题下 startActionMode 返回 NULL 时的「恢复手柄+手动TYPE_FLOATING启动」单点修复
    // ════════════════════════════════════════════════════════════════════════
    var lastReportedSelStart: Int = -1
    var lastReportedSelEnd: Int = -1

    // v24 正确的节点②调试：重写 performLongClick() → 先 super（让系统默认OnLongClickListener执行，触发SelectionController画手柄）
    //     → 再打Toast确认检测到了，100%不替换系统监听器（v23用setOnLongClickListener犯了低级错误，直接替换了系统默认监听器导致③之后全断）
    // v25 加强版：打 selBefore vs selAfter，直观确认super()调用前后系统SelectionController有没有真的设置selection
    // v36 修复：长按成功后立即释放 requestDisallowInterceptTouchEvent
    //     原因：长按成功后系统 CAB 会拦截后续的触摸事件，导致 ACTION_UP/CANCEL 可能丢失
    //     → onAfterUpOrCancel 不会被调用 → requestDisallowInterceptTouchEvent(true) 永久残留 → 无法滑动
    override fun performLongClick(): Boolean {
        // v41 移除调试 Toast：先 super 执行系统默认长按（触发 SelectionController 画手柄），
        // 100% 不替换系统监听器（v23 教训）
        val result = runCatching { super.performLongClick() }.getOrDefault(false)
        // v36 修复：长按成功后立即释放滑动拦截
        // 防止后续 ACTION_UP/CANCEL 被系统 CAB 消费导致永久无法滑动
        if (result) {
            runCatching {
                (parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                (parent?.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return result
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val actName = when (event.action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "0x${event.action.toString(16)}"
        }
        android.util.Log.e("HitTV-TRACE", "#p$paragraphIndex dispatchTouchEvent $actName x=${event.x.toInt()} y=${event.y.toInt()}")

        var customHit = false
        runCatching {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // v41 移除调试 Toast（节点①）；保留业务钩子
                    onBeforeDown()
                    onOtherParagraphTouched()
                }
                MotionEvent.ACTION_UP -> {
                    val hash = System.identityHashCode(resolveTap)
                    android.util.Log.e("HitTV-TRACE", "#p$paragraphIndex ACTION_UP → resolveTap refHash=$hash")
                    customHit = resolveTap(event, "ACTION_UP")
                    android.util.Log.e("HitTV-TRACE", "#p$paragraphIndex ACTION_UP done resolveTap → returned=$customHit")
                    onAfterUpOrCancel()
                }
                MotionEvent.ACTION_CANCEL -> {
                    customHit = resolveTap(event, "ACTION_CANCEL")
                    onAfterUpOrCancel()
                }
                else -> Unit
            }
        }.onFailure { t ->
            // v41 移除崩溃 Toast（保留 Log 供排查）
            android.util.Log.e("AnnotationHitTV", "dispatchTouchEvent hook CRASHED #p$paragraphIndex", t)
        }

        // v49 沉浸式功能栏：命中注释 Span 的 UP 不算「单击正文」→ 不切换功能栏显隐
        val systemHandled = runCatching {
            if (customHit) suppressPlainTap = true
            super.dispatchTouchEvent(event)
        }
            .onFailure { t ->
                // v41 移除崩溃 Toast（保留 Log 供排查）
                android.util.Log.e("AnnotationHitTV", "super.dispatchTouchEvent CRASHED #p$paragraphIndex", t)
            }
            .getOrDefault(false)

        android.util.Log.e(
            "HitTV-TRACE",
            "#p$paragraphIndex done $actName → customHit=$customHit || systemHandled=$systemHandled = RETURN ${customHit || systemHandled}"
        )
        return customHit || systemHandled
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderArticleScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // --- StateFlow 收集：全部用 produceState + try/catch 兜底，绝对不让任何底层异常（DB/Flow/TypeCast）冒泡崩溃 ---
    val book by produceState<BookEntity?>(initialValue = null, viewModel) {
        viewModel.currentBook.collect { value = runCatching { it }.getOrNull() }
    }
    // 正文分段：由 ViewModel 在 IO 线程切好（大文件不再在首帧做整篇 split）
    val paragraphList by produceState<List<String>>(initialValue = emptyList(), viewModel) {
        viewModel.paragraphs.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(emptyList())
        }
    }
    val contentLoading by produceState<Boolean>(initialValue = false, viewModel) {
        viewModel.contentLoading.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(false)
        }
    }
    val annotations by produceState<List<BookAnnotationEntity>>(initialValue = emptyList(), viewModel) {
        try {
            viewModel.annotations.collect { newValue ->
                value = runCatching { newValue }.getOrDefault(emptyList())
            }
        } catch (t: Throwable) {
            Log.e("ReaderArticle", "annotations flow crashed (DB migration?). Silent fallback emptyList().", t)
            value = emptyList()
        }
    }
    val selection by produceState<ReaderViewModel.SelectionState?>(initialValue = null, viewModel) {
        viewModel.selection.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    val annotationEditor by produceState<ReaderViewModel.AnnotationEditorState?>(initialValue = null, viewModel) {
        viewModel.annotationEditor.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    // v42 离线词典：注释窗「词典释义」Tab 的查询结果
    val dictLookup by produceState<ReaderViewModel.DictLookupState?>(initialValue = null, viewModel) {
        viewModel.dictLookup.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    val memoryConfirm by produceState<ReaderViewModel.MemoryConfirmState?>(initialValue = null, viewModel) {
        viewModel.memoryConfirm.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    val memoryMsg by produceState<String?>(initialValue = null, viewModel) {
        viewModel.memoryMarkMessage.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    val toast by produceState<String?>(initialValue = null, viewModel) {
        viewModel.toastMessage.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    // Phase 3：中英混排改写（原文 | 改写后 双界面）
    val rewriteView by produceState<Boolean>(initialValue = false, viewModel) {
        viewModel.rewriteView.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(false)
        }
    }
    val rewriting by produceState<Boolean>(initialValue = false, viewModel) {
        viewModel.rewriting.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(false)
        }
    }
    val rewriteMode by produceState<String>(initialValue = "replace", viewModel) {
        viewModel.rewriteMode.collect { newValue ->
            value = runCatching { newValue }.getOrNull() ?: "replace"
        }
    }
    val rewritePage by produceState<IntRange>(initialValue = IntRange.EMPTY, viewModel) {
        viewModel.rewritePage.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(IntRange.EMPTY)
        }
    }
    val rewrittenMap by produceState<Map<Int, String>>(initialValue = emptyMap(), viewModel) {
        viewModel.rewrittenMap.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(emptyMap())
        }
    }
    val rewriteStats by produceState<ReaderViewModel.RewriteStats?>(initialValue = null, viewModel) {
        viewModel.rewriteStats.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    val rewriteWords by produceState<List<ZhEnRewriter.Replacement>>(initialValue = emptyList(), viewModel) {
        viewModel.rewriteWords.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(emptyList())
        }
    }
    // 本页「全量」替换明细（不去重）：点击英文词命中用，同页重复词也能点中
    val rewriteAllWords by produceState<List<ZhEnRewriter.Replacement>>(initialValue = emptyList(), viewModel) {
        viewModel.rewriteAllWords.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(emptyList())
        }
    }
    val rewritePageNo by produceState<Int>(initialValue = 1, viewModel) {
        viewModel.rewritePageNo.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(1)
        }
    }
    val rewritePageTotal by produceState<Int>(initialValue = 1, viewModel) {
        viewModel.rewritePageTotal.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(1)
        }
    }
    // 改写难度微调（不熟 / 不会 / 新增词，各为每 100 字允许出现的个数）
    val rewriteTuning by produceState<ZhEnRewriter.Density.Tuning>(
        initialValue = ZhEnRewriter.Density.DEFAULT_TUNING, viewModel
    ) {
        viewModel.rewriteTuning.collect { newValue ->
            value = runCatching { newValue }.getOrDefault(ZhEnRewriter.Density.DEFAULT_TUNING)
        }
    }
    // 单击改写正文英文单词 → 原意卡片
    val wordLookup by produceState<ReaderViewModel.RewriteWordLookup?>(initialValue = null, viewModel) {
        viewModel.rewriteWordLookup.collect { newValue ->
            value = runCatching { newValue }.getOrNull()
        }
    }
    // 原意卡片锚点（屏幕坐标，来自被点单词的位置）
    var wordLookupAnchor by remember { mutableStateOf<IntOffset?>(null) }
    // 难度调节面板
    var densitySheetVisible by remember { mutableStateOf(false) }
    val densitySheetState = rememberModalBottomSheetState()
    // 字体大小调节面板
    var fontSheetVisible by remember { mutableStateOf(false) }
    val fontSheetState = rememberModalBottomSheetState()

    val context = LocalContext.current

    // ---- 朗读 ----
    val speaker = remember { OfflineTtsSpeaker(context) }
    var isSpeaking by remember { mutableIntStateOf(0) } // 0 停止 1 朗读
    // 离线语音包未下载时的下载引导弹窗
    var ttsDownloadVisible by remember { mutableStateOf(false) }

    // 进入阅读页：已下载则后台预加载模型（降低首次朗读延迟）；离开时释放引擎与音频资源
    DisposableEffect(Unit) {
        speaker.preload()
        onDispose { speaker.release() }
    }

    // ---- Toast 消费 ----
    LaunchedEffect(memoryMsg) {
        val m = memoryMsg ?: return@LaunchedEffect
        Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
        viewModel.consumeMemoryMarkMessage()
    }
    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
        viewModel.consumeToast()
    }

    // ---- 正文分段 ----
    val paragraphs: List<String> = paragraphList

    val listState = rememberLazyListState()

    // ═══ 沉浸式功能栏：点击正文中间区域弹出/收起上下功能栏（原文 + 改写后都生效）═══
    var toolbarsVisible by remember { mutableStateOf(true) }
    // ═══ 原文阅读模式：scroll 滚动 / pager 翻页（一屏一页，左右滑动切换）═══
    // 阅读习惯记忆：持久化在 DataStore，下次打开自动保持上次模式
    val originalPageMode by viewModel.readingPageMode.collectAsStateWithLifecycle()
    // ═══ 正文字体缩放（百分比，100 = 16sp；持久化记忆，影响正文排版与分页测量）═══
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    // 字体缩放系数（供正文渲染与分页测量使用）
    val fontScaleFactor = fontScale.coerceIn(80, 240) / 100f
    // ═══ 全文段落是否已就绪（false = 快速首屏阶段，仍只加载了开头若干段落）═══
    val paragraphsComplete by viewModel.paragraphsComplete.collectAsStateWithLifecycle()

    // ═══ 翻页式分页：按真实排版测量 → 一屏一页（页内不再需要上下滚动，一滑即翻页）═══
    // 用 StaticLayout 按与正文 TextView 完全相同的字号/字体/行高测量每段真实高度，逐段装页。
    // 大文件测量有成本 → 只在翻页模式下按需测量（后台线程），测量期间显示「正在分页」。
    val densityScale = androidx.compose.ui.platform.LocalDensity.current.density
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var pagerMeasuring by remember { mutableStateOf(false) }
    val pagerPages by produceState<List<IntRange>>(
        initialValue = emptyList(), paragraphs, contentSize, originalPageMode, densityScale, fontScaleFactor,
        paragraphsComplete
    ) {
        val size = contentSize
        if (originalPageMode != "pager" || paragraphs.isEmpty() || size.height <= 0) {
            value = emptyList()
            pagerMeasuring = false
            return@produceState
        }
        // 排版指纹：段落数 + 总字数 + 可用宽高 px + 字体缩放。
        // 一致 → 直接复用上次持久化的分页结果（大文件免每次重新测量分页）。
        val contentWidthPx = size.width - (PAGER_H_PADDING_DP * densityScale).toInt()
        val contentHeightPx = size.height -
            ((PAGER_V_PADDING_DP + PAGER_BOTTOM_RESERVE_DP) * densityScale).toInt()
        val fontScaleClamped = fontScale.coerceIn(80, 240)
        val layoutKey = "$contentWidthPx:$contentHeightPx:$fontScaleClamped"
        val signature = "${paragraphs.size}:${paragraphs.sumOf { it.length }}:$layoutKey"
        // ① 已分页过：只按「排版」宽松命中缓存 → 全文到齐前也能立即拿回整部分页表。
        //    这样「打开已分页过的大文件」不再显示「正在分页」、也不用重新测量（这是打开慢的主因）。
        //    全文就绪后改用「严格指纹」命中 → 若缓存是由乱码正文派生（段落数/总字数不符）自动失效重测（自愈）。
        val cached = if (!paragraphsComplete) {
            runCatching { viewModel.cachedPageRangesLoose(layoutKey) }.getOrNull()
        } else {
            runCatching { viewModel.cachedPageRanges(signature) }.getOrNull()
        }
        if (!cached.isNullOrEmpty() && (!paragraphsComplete || cached.last().last < paragraphs.size)) {
            value = cached
            pagerMeasuring = false
            return@produceState
        }
        // ② 无缓存（/缓存与全文不符）：用「当前已加载的段落」测量。
        //    快速首屏阶段只测前缀 → 首页立刻可见（不再空等全文）；全文到齐后重测一次补齐。
        pagerMeasuring = true
        value = emptyList()
        val computed = withContext(Dispatchers.Default) {
            runCatching {
                measurePageRanges(
                    paragraphs = paragraphs,
                    contentWidthPx = contentWidthPx,
                    contentHeightPx = contentHeightPx,
                    densityScale = densityScale,
                    fontScale = fontScaleFactor
                )
            }.getOrDefault(emptyList())
        }
        value = computed
        pagerMeasuring = false
        // 分页结果持久化：只在全文就绪后落库，避免把「只按前缀测出的半截分页表」存下来
        if (paragraphsComplete) {
            runCatching { viewModel.persistPageRanges(signature, computed) }
        }
    }
    val pagerState = rememberPagerState(pageCount = { pagerPages.size })

    // 难易统计弹窗：当前查看类别（null=关闭）；换页后自动关闭（弹窗内容属于上一页）
    var rewriteSheetType by remember { mutableStateOf<StatsSheetType?>(null) }
    LaunchedEffect(rewritePageNo) { rewriteSheetType = null }
    val rewriteSheetState = rememberModalBottomSheetState()

    // 当前阅读起始段（滚动模式 = LazyColumn 可见首段；翻页模式 = 当前页首段）
    val currentStartIndex = if (originalPageMode == "pager")
        pagerPages.getOrNull(pagerState.currentPage)?.first ?: 0
    else
        listState.firstVisibleItemIndex

    // 进入「改写后」时的目标段落区间：
    //  翻页模式 = 当前整屏页（与原文分页完全一致，切过去看到的是同一屏内容）
    //  滚动模式 = 含当前可见段的 600 字改写页（与翻页按键的单位一致）
    val rewriteEnterRange: IntRange = if (originalPageMode == "pager")
        pagerPages.getOrNull(pagerState.currentPage) ?: IntRange.EMPTY
    else
        remember(currentStartIndex, paragraphs) { viewModel.rewriteScrollPageRange(currentStartIndex) }

    // 显式 key(idx)：LazyList 复用稳定性
    // ParagraphColors 用 remember 生成稳定引用，避免每次 item 重组都 new → 触发 AndroidView 多余 update
    //     —— 注意：remember + MaterialTheme.colorScheme getter 是 @Composable，必须在 LazyListScope 之外定义
    val cs = MaterialTheme.colorScheme
    val paragraphColors = remember(cs) { ParagraphColors(onSurface = cs.onSurface, primary = cs.primary) }

    // ---- 还原上次阅读位置（只还原一次；必须等全文 + 分页都就绪，否则会停在第一页）----
    // 关键修复：原来 key 里没有 pagerPages，翻页模式首次打开时 pages 还是空的 → 跳过跳转且不再重试，
    // 结果「记住的页数进度」永远还原不到（总是回到第一页）。
    var progressRestored by remember(book?.id, originalPageMode) { mutableStateOf(false) }
    LaunchedEffect(book?.id, paragraphs, originalPageMode, pagerPages, paragraphsComplete) {
        val b = book
        if (progressRestored || b == null || paragraphs.isEmpty()) return@LaunchedEffect
        val target = b.readOffset.coerceAtLeast(0)
        if (originalPageMode == "pager") {
            // 翻页模式：等分页表就绪再定位到包含已读进度的那一页
            if (pagerPages.isEmpty()) return@LaunchedEffect
            var acc = 0
            var pageIdx = 0
            for ((i, range) in pagerPages.withIndex()) {
                pageIdx = i
                acc += paragraphs.subList(
                    range.first.coerceIn(0, paragraphs.size),
                    (range.last + 1).coerceIn(0, paragraphs.size)
                ).fastSumBy { it.length }
                if (acc > target) break
            }
            // 目标页的段落必须已加载（快速首屏前缀一定覆盖「上次位置 + 一屏」）；否则等全文
            if (acc <= target) return@LaunchedEffect
            if ((pagerPages.getOrNull(pageIdx)?.first ?: 0) >= paragraphs.size) return@LaunchedEffect
            runCatching { pagerState.scrollToPage(pageIdx) }
        } else {
            // 滚动模式：前缀已覆盖目标段即可立即还原（读过的位置必在首屏前缀内），否则等全文
            var acc = 0
            var index = 0
            var charOffset = 0
            while (index < paragraphs.size && acc < target) {
                val l = paragraphs[index].length
                if (acc + l > target) {
                    charOffset = target - acc
                    break
                }
                acc += l
                index++
            }
            if (target > 0 && acc < target) return@LaunchedEffect // 目标落在未加载区间 → 等全文
            listState.scrollToItem(index.coerceAtMost(paragraphs.size - 1), charOffset)
        }
        progressRestored = true
    }

    // ---- 实时保存已读进度（滚动模式：LazyColumn 首段字符偏移）----
    // 首段一变化立即落库（不再防抖）：保证退出时进度一定是最新位置；
    // ReaderViewModel.closeBook/onCleared 还会同步兜底存一次（双保险）。
    LaunchedEffect(listState, paragraphs, book?.id, originalPageMode, progressRestored) {
        if (originalPageMode != "scroll") return@LaunchedEffect
        // 位置还原完成前不记录进度：否则快速首屏阶段会把「第 0 段」当成新进度写回，覆盖掉记住的页数
        if (!progressRestored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                val offset = paragraphs.take(idx.coerceIn(0, paragraphs.size)).fastSumBy { it.length }
                viewModel.saveProgress(offset)
            }
    }

    // ---- 实时保存已读进度（翻页模式：当前页首段字符偏移）----
    // 翻页是低频操作 → 每次翻页立即落库（真正的实时），退出时进度一定是最新页。
    LaunchedEffect(pagerState, paragraphs, book?.id, originalPageMode, progressRestored, pagerPages) {
        if (originalPageMode != "pager") return@LaunchedEffect
        if (!progressRestored) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val first = pagerPages.getOrNull(page)?.first ?: return@collect
                val offset = paragraphs.take(first.coerceIn(0, paragraphs.size)).fastSumBy { it.length }
                viewModel.saveProgress(offset)
            }
    }

    // ---- 问题 1b + 1c：滚动时，如果 selection 对应的段已经不在屏幕可见区域 → 清掉 selection，浮条不再显示旧文字 ----
    //  根因：用户截图里选中的是「very」，浮条显示的是之前「Bermuda Triangle」。
    //  因为旧段（Bermuda那段）被 LazyList 滚动 detach 了，它的 80ms 轮询 runnable 停跑，
    //  所以永远不会检测到「选中手柄被挪到新段」或者「CAB 被销毁」的事件，selection 永远停在旧值。
    //  修法：每帧 snapshotFlow 取当前屏幕可见的 paragraph index 列表 → 如果 selection.paragraphIndex 不在里面 → dismiss。
    LaunchedEffect(listState, paragraphs, selection, originalPageMode) {
        if (originalPageMode != "scroll") return@LaunchedEffect
        val sel = selection ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? Int) ?: it.index } }
            .distinctUntilChanged()
            .collect { visibleIdxs ->
                if (sel.paragraphIndex !in visibleIdxs) {
                    Log.d("FloatBarSync", "selection p#${sel.paragraphIndex}不在可见，关闭浮条.可见=$visibleIdxs")
                    viewModel.dismissSelection()
                }
            }
    }

    // ---- 翻页模式：翻到别的页时，如果选中段不在当前页 → 清掉 selection ----
    LaunchedEffect(pagerState, paragraphs, selection, originalPageMode) {
        if (originalPageMode != "pager") return@LaunchedEffect
        val sel = selection ?: return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val range = pagerPages.getOrNull(page)
                if (range == null || sel.paragraphIndex !in range) {
                    Log.d("FloatBarSync", "pager#${page} 选中 p#${sel.paragraphIndex} 不在当前页 → 关闭浮条")
                    viewModel.dismissSelection()
                }
            }
    }

    // 阅读进度（底部进度条）：滚动模式按 LazyColumn 首段偏移；翻页模式按当前页首段偏移
    val readProgressPercent = if (originalPageMode == "pager") {
        val totalChars = paragraphs.fastSumBy { it.length }
        if (totalChars <= 0) 0
        else {
            val range = pagerPages.getOrNull(pagerState.currentPage)
            val ofs = if (range == null) 0 else paragraphs.take(range.first).fastSumBy { it.length }
            (ofs * 100f / totalChars).toInt().coerceIn(0, 100)
        }
    } else {
        val totalChars = paragraphs.fastSumBy { it.length }
        if (totalChars <= 0) 0
        else {
            var acc = 0
            val idx = listState.firstVisibleItemIndex
            for (i in 0 until idx) acc += paragraphs[i].length
            val ofs = (acc + listState.firstVisibleItemScrollOffset).coerceAtMost(totalChars)
            (ofs * 100f / totalChars).toInt().coerceIn(0, 100)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ---- 正文内容：全屏铺满；上下功能栏覆盖在其上（显示/收起不挤压、不重排文字）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                // 记录正文可用尺寸 → 翻页式按真实排版测量分页（一屏一页）
                .onSizeChanged { contentSize = it }
        ) {
            if (contentLoading && paragraphs.isEmpty()) {
                // 大文件打开中：读取 + 分段 + 字符统计都在 IO 线程，这里给明确的加载反馈
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "正在加载正文…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (paragraphs.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        "暂无内容",
                        Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (rewriteView) {
                // ========== 改写后界面（与原文共用同一套整屏分页；工具条在顶部覆盖层，随点击收起）==========
                RewrittenPageView(
                    paragraphs = paragraphs,
                    page = rewritePage,
                    pages = pagerPages,
                    pagerState = pagerState,
                    rewrittenMap = rewrittenMap,
                    mode = rewriteMode,
                    rewriting = rewriting,
                    words = rewriteAllWords,
                    pagerMode = originalPageMode == "pager",
                    pagerMeasuring = pagerMeasuring,
                    onMiddleTap = { toolbarsVisible = !toolbarsVisible },
                    onPageSelected = { viewModel.gotoRewriteRange(it) },
                    onPrefetchPage = { viewModel.prefetchRewriteRange(it) },
                    onWordLookup = { word, zh, category, anchor ->
                        wordLookupAnchor = anchor
                        viewModel.lookupRewriteWord(word, zh, category)
                    },
                    fontScale = fontScaleFactor,
                    readerViewModel = viewModel
                )
            } else if (originalPageMode == "pager") {
                // ========== 原文·翻页式：整屏一页，左右滑动切换 ==========
                OriginalPagerView(
                    paragraphs = paragraphs,
                    book = book,
                    pages = pagerPages,
                    pagerState = pagerState,
                    annotations = annotations,
                    paragraphColors = paragraphColors,
                    readerViewModel = viewModel,
                    fontScale = fontScaleFactor,
                    onMiddleTap = { toolbarsVisible = !toolbarsVisible }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 80.dp)
                ) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                            Text(
                                text = book?.title ?: "",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            book?.author?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = "作者：$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }
                    }
                    // 显式 key(idx)：LazyList 复用稳定性；paragraphColors 已经在 LazyList 外面 remember 好了
                    itemsIndexed(items = paragraphs, key = { idx, _ -> idx }) { idx, para ->
                        if (para.isBlank()) {
                            Spacer(Modifier.size(10.dp))
                        } else {
                            val paragraphAnnotations = remember(annotations, idx) {
                                runCatching { annotations.filter { it.paragraphIndex == idx } }
                                    .getOrDefault(emptyList())
                            }
                            // 回调统一走 paraCallbacks：滚动式 / 翻页式 共用同一套，避免两处逻辑漂移
                            val cb = paraCallbacks(viewModel, idx, para) { toolbarsVisible = !toolbarsVisible }
                            ParagraphItem(
                                text = para,
                                paragraphIndex = idx,
                                annotations = paragraphAnnotations,
                                colorSchemeHolder = paragraphColors,
                                readerViewModel = viewModel,
                                onSelectionChanged = cb.onSelectionChanged,
                                onTapCharPosition = cb.onTapCharPosition,
                                onOpenAnnotation = cb.onOpenAnnotation,
                                onRequestAnnotateFromActionMode = cb.onRequestAnnotateFromActionMode,
                                onRequestMemoryFromActionMode = cb.onRequestMemoryFromActionMode,
                                onTouchOtherParagraph = cb.onTouchOtherParagraph,
                                onPlainTap = cb.onPlainTap,
                                fontScale = fontScaleFactor
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                    }
                }
            }

            // ========== 注释编辑弹窗 ==========
            val editor = annotationEditor
            if (editor != null) {
                ReaderAnnotationEditor(
                    state = editor,
                    onDismiss = {
                        viewModel.clearDictionary()
                        viewModel.dismissAnnotationEditor()
                    },
                    onToggleFullscreen = { viewModel.toggleAnnotationFullscreen() },
                    onNoteChange = { viewModel.updateAnnotationDraft(it) },
                    onSave = { viewModel.saveAnnotation() },
                    onDelete = { viewModel.deleteCurrentAnnotation() },
                    // v42 离线词典
                    dictLookup = dictLookup,
                    onLookupDictionary = { viewModel.lookupDictionary(it) }
                )
            }

            // ========== 记忆库二级确认（会的/不会的）AlertDialog ==========
            val mem = memoryConfirm
            if (mem != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissMemoryConfirm() },
                    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true, dismissOnBackPress = true),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    icon = {
                        Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    title = {
                        Text("加入记忆库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column {
                            Text(
                                "已选内容（${mem.selectedText.length} 字）：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.size(4.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = remember(mem.selectedText) {
                                        if (mem.selectedText.length <= 120) mem.selectedText else mem.selectedText.take(120) + "…"
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 20.sp
                                )
                            }
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "「会的」→ FSRS 正常复习；「不熟」→ 会但不完全会的中间态；「不会的」→ 生词本（9:1 引擎优先在上下文中出现）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    },
                    confirmButton = {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.markAsKnown() },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(6.dp))
                                Text("会的", fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = { viewModel.markAsSemi() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF9A825), contentColor = Color.White)
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("不熟", fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = { viewModel.markAsUnknown() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                            ) {
                                Icon(Icons.Filled.HelpCenter, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("不会的", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissMemoryConfirm() }) { Text("取消") }
                    }
                )
            }

            // ========== 难易统计弹窗：查看对应类别的具体单词列表（改写后工具条按钮触发；本页数据）==========
            val sheet = rewriteSheetType
            if (sheet != null) {
                val sheetList = when (sheet) {
                    StatsSheetType.ALL -> rewriteWords
                    StatsSheetType.KNOWN -> rewriteWords.filter { it.category == "known" }
                    StatsSheetType.SEMI -> rewriteWords.filter { it.category == "semi" }
                    StatsSheetType.UNKNOWN -> rewriteWords.filter { it.category == "unknown" }
                }
                val sheetTitle = when (sheet) {
                    StatsSheetType.ALL -> "本页替换单词"
                    StatsSheetType.KNOWN -> "会的单词"
                    StatsSheetType.SEMI -> "不熟的单词"
                    StatsSheetType.UNKNOWN -> "不会的单词"
                }
                val sheetAccent = when (sheet) {
                    StatsSheetType.ALL -> MaterialTheme.colorScheme.primary
                    StatsSheetType.KNOWN -> Color(0xFF4CAF50)
                    StatsSheetType.SEMI -> Color(0xFFF9A825)
                    StatsSheetType.UNKNOWN -> Color(0xFFE57373)
                }
                ModalBottomSheet(
                    onDismissRequest = { rewriteSheetType = null },
                    sheetState = rewriteSheetState
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        // 标题行
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(sheetAccent)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                sheetTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "共 ${sheetList.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        if (sheetList.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "本页没有替换这一类的单词",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // 单词列表（中文词 → 英文 + 类别标签）
                            // ⚠️ key 用索引：同一页可能出现多个相同 中文→英文 组合，用内容做 key 会重复崩溃
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .padding(bottom = 24.dp),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                itemsIndexed(sheetList) { index, w ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            w.zh,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            "→",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            w.en,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = sheetAccent.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                when (w.category) {
                                                    "known" -> "会的"
                                                    "semi" -> "不熟"
                                                    else -> "不会"
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = sheetAccent
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══ 顶部覆盖层：TopAppBar + 原文|改写后 Tab（覆盖在正文之上，显示/收起不挤压、不重排文字）═══
        AnimatedVisibility(
            visible = toolbarsVisible,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = book?.title?.take(18) ?: "阅读器",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSpeaking = 0
                            speaker.stop()
                            viewModel.closeBook()
                            onBack()
                        }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                // Phase 3：原文 | 改写后 双阅读界面切换 + 滚动/翻页切换
                // ⚠️ 不透明背景（surface）：功能栏覆盖在正文上，透明会与文字重影，必须不透明
                Surface(color = MaterialTheme.colorScheme.surface) {
                    RewriteTabBar(
                        rewriteView = rewriteView,
                        originalPageMode = originalPageMode,
                        onSelectOriginal = { viewModel.exitRewriteView() },
                        onSelectRewritten = {
                            runCatching { viewModel.enterRewriteView(rewriteEnterRange) }
                                .onFailure { t -> Log.w("ReaderArticle", "enterRewriteView dropped", t) }
                        },
                        onTogglePageMode = {
                            // 阅读习惯记忆：持久化阅读方式，下次打开保持上次模式
                            viewModel.setReadingPageMode(if (originalPageMode == "scroll") "pager" else "scroll")
                        },
                        onFontClick = { fontSheetVisible = true }
                    )
                }
                // 改写后工具条（仅改写界面显示）：显示模式 + 页码 + 难易统计 + 翻页按键
                // 覆盖在正文之上不挤压内容；翻页式时隐藏翻页按键/页码（由左右滑动 + 右下角进度接管）
                if (rewriteView) {
                    RewrittenToolbar(
                        mode = rewriteMode,
                        onModeChange = { viewModel.setRewriteMode(it) },
                        tuning = rewriteTuning,
                        onDensityClick = { densitySheetVisible = true },
                        pageNo = rewritePageNo,
                        pageTotal = rewritePageTotal,
                        pagerMode = originalPageMode == "pager",
                        stats = rewriteStats,
                        onStatClick = { rewriteSheetType = it },
                        onPrevPage = { viewModel.rewriteAdjacentPage(false) },
                        onNextPage = { viewModel.rewriteAdjacentPage(true) }
                    )
                }
            }
        }

        // ═══ 底部覆盖层：翻页进度 + 选中浮条（始终显示）+ 进度/朗读/改写 BottomBar（随 toolbarsVisible 收起）═══
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // ========== 翻页式：右下角常显「当前页 / 总页数」进度（不随功能栏收起；原文/改写后翻页式共用同一分页）==========
            val pagerProgressText = if (originalPageMode == "pager" && (pagerPages.isNotEmpty() || pagerMeasuring)) {
                if (pagerMeasuring) "正在分页…" else "${pagerState.currentPage + 1} / ${pagerPages.size}"
            } else null
            if (pagerProgressText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            pagerProgressText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            // ========== 浮动工具条：选中文字 + 没有弹窗打开时出现 ==========
            //  ⚠️ 为什么弹窗打开时隐藏？
            //    截图里「编辑注释」弹窗已经打开了，但 selection != null 仍然成立，浮条会露在弹窗下半部分遮挡，视觉很差。
            //    用户希望「两种路径（CAB 菜单项 + 底部浮条）都能点注释/记忆库」——这个要求的前提是「没有其他弹窗遮住」，
            //    也就是：annotationEditor==null && memoryConfirm==null 时，两条路径都开放（CAB 菜单和底部浮条同时可用）；
            //    一旦注释窗 / 记忆库确认窗弹出 → 底部浮条隐藏（避免遮挡，且已经有弹窗在处理了）。
            if (selection != null && annotationEditor == null && memoryConfirm == null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    shape = RoundedCornerShape(14.dp),
                    // 不透明背景：覆盖在正文上，透明会与文字重影
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 左侧：选中文字预览（可点击空白取消选中）
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.dismissSelection() }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            val preview = remember(selection!!.selectedText) {
                                val text = selection!!.selectedText
                                if (text.length <= 32) text else text.take(32) + "…"
                            }
                            Text(
                                text = "已选：$preview",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        // 按钮 1：注释
                        Button(
                            onClick = { viewModel.openAnnotationEditorFromSelection() },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("注释", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        // 按钮 2：记忆库
                        OutlinedButton(
                            onClick = { viewModel.openMemoryConfirmFromSelection() },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(4.dp))
                            Text("记忆库", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            // ========== 原 BottomBar：进度 + 朗读 + 改写（随 toolbarsVisible 收起）==========
            AnimatedVisibility(visible = toolbarsVisible) {
                ReaderBottomBar(
                    progressPercent = readProgressPercent,
                    onReadAloud = {
                        val all = paragraphs.filter { it.isNotBlank() }.joinToString(" ")
                        if (all.isBlank()) return@ReaderBottomBar
                        // 语音包未下载 → 先引导下载
                        if (!TtsModelStore.isReady(context)) {
                            ttsDownloadVisible = true
                            return@ReaderBottomBar
                        }
                        speaker.speak(all,
                            onError = { err -> Toast.makeText(context, "朗读失败：$err", Toast.LENGTH_SHORT).show() },
                            onStarted = { isSpeaking = 1 },
                            onFinished = { isSpeaking = 0 }
                        )
                    },
                    onStop = {
                        speaker.stop()
                        isSpeaking = 0
                    },
                    // Phase 3：中英改写入口（顶部 Tab 已提供「原文|改写后」切换，此处按钮为便捷入口）
                    rewriteView = rewriteView,
                    onToggleRewrite = {
                        if (rewriteView) {
                            viewModel.exitRewriteView()
                        } else {
                            runCatching { viewModel.enterRewriteView(rewriteEnterRange) }
                                .onFailure { t -> Log.w("ReaderArticle", "toggleRewrite dropped", t) }
                        }
                    }
                )
            }
        }

        // ========== 改写难度调节面板：用户按自身阅读感受自定义生词密度 ==========
        if (densitySheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { densitySheetVisible = false },
                sheetState = densitySheetState
            ) {
                RewriteDensitySheet(
                    tuning = rewriteTuning,
                    onTuningChange = { viewModel.setRewriteTuning(it) }
                )
            }
        }

        // ========== 字体大小调节面板：正文字号自定义（持久化记忆，影响排版与分页）==========
        if (fontSheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { fontSheetVisible = false },
                sheetState = fontSheetState
            ) {
                FontSizeSheet(
                    fontScale = fontScale,
                    onFontScaleChange = { viewModel.setFontScale(it) }
                )
            }
        }

        // ========== 单击改写正文里的英文单词 → 「原意 + 释义」小卡片（贴近被点单词）==========
        val lookup = wordLookup
        if (lookup != null && rewriteView) {
            RewriteWordCard(
                lookup = lookup,
                anchor = wordLookupAnchor,
                onStatusChange = { viewModel.setRewriteWordStatus(it) },
                onDismiss = {
                    wordLookupAnchor = null
                    viewModel.dismissRewriteWordLookup()
                }
            )
        }

        // ========== 离线语音包按需下载弹窗（首次朗读时引导，下载后立即开始朗读）==========
        TtsDownloadDialog(
            visible = ttsDownloadVisible,
            onDismiss = { ttsDownloadVisible = false },
            onReady = {
                ttsDownloadVisible = false
                val all = paragraphs.filter { it.isNotBlank() }.joinToString(" ")
                if (all.isNotBlank()) {
                    speaker.speak(all,
                        onError = { err -> Toast.makeText(context, "朗读失败：$err", Toast.LENGTH_SHORT).show() },
                        onStarted = { isSpeaking = 1 },
                        onFinished = { isSpeaking = 0 }
                    )
                }
            }
        )
    }
}

/**
 * 改写难度微调面板：三个维度分别用滑块微调「每 100 字窗口内允许出现的个数」。
 *  - 不熟的 / 不会的 / 新增词，各自独立预算、互不影响；
 *  - 调小读得更顺畅，读顺了再逐项加难 —— 用户据此找到最合适自己的难度。
 */
@Composable
private fun RewriteDensitySheet(
    tuning: ZhEnRewriter.Density.Tuning,
    onTuningChange: (ZhEnRewriter.Density.Tuning) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(8.dp))
            Text("改写难度微调", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(4.dp))
        Text(
            "三项都是「每 100 字里最多出现多少个」，按自身水平分别微调。调小读得更顺畅，读顺了再逐步加难。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(10.dp))
        TuningSliderRow(
            label = "不熟的",
            value = tuning.semiPer100,
            min = ZhEnRewriter.Density.SEMI_MIN,
            max = ZhEnRewriter.Density.SEMI_MAX
        ) { onTuningChange(ZhEnRewriter.Density.tuning(it, tuning.unknownPer100, tuning.injectPer100)) }
        TuningSliderRow(
            label = "不会的",
            value = tuning.unknownPer100,
            min = ZhEnRewriter.Density.UNKNOWN_MIN,
            max = ZhEnRewriter.Density.UNKNOWN_MAX
        ) { onTuningChange(ZhEnRewriter.Density.tuning(tuning.semiPer100, it, tuning.injectPer100)) }
        TuningSliderRow(
            label = "新增词",
            value = tuning.injectPer100,
            min = ZhEnRewriter.Density.INJECT_MIN,
            max = ZhEnRewriter.Density.INJECT_MAX
        ) { onTuningChange(ZhEnRewriter.Density.tuning(tuning.semiPer100, tuning.unknownPer100, it)) }
        Spacer(Modifier.size(8.dp))
        Text(
            "新增词按你当前等级的词频顺序注入（越常用的越先出现）。调整后当前页立即按新难度重写，已读过的页面下次翻到时自动更新。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 难度微调单行：名称 + 当前值 + 整数步进滑块 */
@Composable
private fun TuningSliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "每 100 字 $value 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onValueChange(Math.round(it).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 正文字体大小调节面板：拖动滑块放大/缩小正文字号（80%~240%）。
 * 字号为阅读习惯记忆，持久化保存；调整后翻页式分页会用新字号重新测量（缓存按指纹自动失效）。
 */
@Composable
private fun FontSizeSheet(
    fontScale: Int,
    onFontScaleChange: (Int) -> Unit
) {
    val cur = fontScale.coerceIn(80, 240)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FormatSize,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(8.dp))
            Text("字体大小", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(4.dp))
        Text(
            "调节正文显示字号，原文与改写后同步生效；字号会记住，下次打开保持。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$cur%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                "示例：${(PARA_TEXT_SP * cur / 100f).toInt()}sp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = cur.toFloat(),
            onValueChange = { onFontScaleChange(it.toInt()) },
            valueRange = 80f..240f,
            steps = 31,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("小", "标准", "大", "特大").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 单击英文单词弹出的「原意」小卡片：英文词 + 音标 + 被替换的中文词（原意）+ 词典释义。
 * 贴近被点单词吸附（下方优先，下方放不下则翻到上方并做左右边界收拢），点空白处关闭。
 */
@Composable
private fun RewriteWordCard(
    lookup: ReaderViewModel.RewriteWordLookup,
    anchor: IntOffset?,
    onStatusChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val config = LocalConfiguration.current
    val windowWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val windowHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    // 锚点为空时退化为屏幕中心；否则用「被点词的窗口绝对坐标」
    val anchorX = if (anchor == null || anchor == IntOffset.Zero) (windowWidthPx / 2).toInt() else anchor.x
    val anchorY = if (anchor == null || anchor == IntOffset.Zero) (windowHeightPx / 2).toInt() else anchor.y
    // 卡片紧贴被点词下方（4dp 间距）；用窗口绝对坐标定位，不受父容器 insets/padding 影响
    val positionProvider = remember(anchorX, anchorY, density.density) {
        WordCardPositionProvider(
            anchorX = anchorX,
            anchorY = anchorY,
            gapPx = (4 * density.density).toInt(),
            marginPx = (8 * density.density).toInt()
        )
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(WORD_CARD_WIDTH_DP.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lookup.word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(6.dp))
                    val (catLabel, catColor) = when (lookup.category) {
                        "known" -> "会的" to Color(0xFF4CAF50)
                        "semi" -> "不熟的" to Color(0xFFF9A825)
                        else -> "不会的" to Color(0xFFE57373)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = catColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            catLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = catColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 原意：这个词在原文里的中文写法（改写前的原词）
                if (lookup.zh.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "原意",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            lookup.zh,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                val entry = lookup.entry
                if (lookup.loading) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "查询词典…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (entry != null) {
                    if (entry.phonetic?.isNotBlank() == true) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            entry.phonetic,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.translation?.isNotBlank() == true) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            entry.translation,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4
                        )
                    }
                } else {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "离线词典中暂无该词释义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // ---- 掌握程度三状态：不会 / 不熟 / 熟悉（点选后写回记忆库并同步当前卡片）----
                Spacer(Modifier.size(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.size(8.dp))
                Text(
                    "掌握程度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple("unknown", "不会", Color(0xFFE57373)),
                        Triple("semi", "不熟", Color(0xFFF9A825)),
                        Triple("known", "熟悉", Color(0xFF4CAF50))
                    ).forEach { (status, label, color) ->
                        val selected = lookup.category == status
                        Surface(
                            onClick = { onStatusChange(status) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) color.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) color else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单击英文单词卡片宽度 */
private const val WORD_CARD_WIDTH_DP = 250

/** 用来把颜色从 Composable 上下文里取出来，再交给 remember（remember 的 lambda 不能直接读 Composition 本地） */
private data class ParagraphColors(
    val onSurface: Color,
    val primary: Color
)

/**
 * 段落组件（市场成熟方案：原生 AppCompatTextView via AndroidView）
 *
 * 参考 Moon+ Reader / Readium / Kindle Android / 静读天下 所有成熟阅读器实现：
 *  - 原生 TextView setTextIsSelectable(true)：Android 2008 至今打磨 17 年的选字系统
 *    → 长按立即出两端光标手柄 + 系统复制/全选菜单 + 选中背景色（Material 主题色半透明）+ 零 bug
 *  - SpannableString + UnderlineSpan + ForegroundColorSpan：已注释区间画 primary色 下划线 + 加粗，
 *    跟正常文字画在同一 layer → 彻底无重影（之前叠层方案就是因为重复画两遍字才重影）
 *  - 原生 API TextView.getOffsetForPosition(event.x, event.y)：点击像素 → 段内字符坐标，
 *    官方公开 API（API 14+），不存在 Compose 内部方法兼容性问题，100% 命中
 *  - Selection 同步：每 80ms 轮询 tv.selectionStart / tv.selectionEnd，变化时通知 VM
 *    （AppCompatTextView 原生没有公开 onSelectionChanged，轻量轮询 CPU≈0）
 *
 * 传入的 onSelectionChanged / onTapCharPosition 回调跟之前 VM 的接口一致，无缝对接。
 */
/**
 * ============================================================
 * ParagraphItem：原生 android.widget.TextView 最终版（业内成熟方案 双保险）
 * ============================================================
 * 【问题 1 方案记录：如何调出系统选字功能栏（Contextual Action Bar / CAB）】
 *   核心 2 行（Moon+ Reader / 静读天下 / Kindle 通用方案）：
 *     1) tv.setTextIsSelectable(true)            ← 打开系统选字（长按手柄 + 复制菜单 + CAB）
 *     2) tv.customSelectionActionModeCallback    ← 可选，往系统 CAB 里塞自己的菜单项
 *   setTextIsSelectable(true) 内部会自动设置：
 *     focusable/longClickable/cursorVisible/focusableInTouchMode + SelectionController
 *     + 创建 ActionMode 把「复制/全选/分享/翻译/AI 翻译」等系统功能放进顶部菜单
 *   → 完全是原生行为，零依赖任何 Compose 文本组件，CPU/内存都最轻，兼容性最好。
 *
 * 【问题 2 方案：选中后「注释 / 记忆库」选项哪里放？】
 *   → 业内标准：塞入系统 CAB（customSelectionActionModeCallback.onCreateActionMode）
 *     menu.add(0, MENU_ANNOTATE, 0, "📝 备注") / menu.add(0, MENU_MEMORY, 1, "🧠 记忆库")
 *     用户选中文字 → 顶部系统功能栏自动出现这两项，一目了然；
 *     下方 Compose 自定义浮条同时保留作为 secondary 入口（双保险）。
 *
 * 【问题 3 方案：点击下划线文字弹不出注释窗？】
 *   → 业内标准（微信读书 Android 端）：下划线区间用 ClickableSpan 子类
 *     不依赖坐标解算 / SQL 命中；系统 TextView 的 MovementMethod 自动对触摸点做 Span 命中检测
 *     → onClick 直接拿到 annotation 引用调用 openAnnotationFromTapHit()，100% 不丢
 *   → updateDrawState 里继续保留下划线样式 + 加粗 + primary 色（原 UnderlineSpan 视觉一致）
 *   → 同时保留老的 Layout.getOffsetForHorizontal + SQL 查询作为 fallback（点击空白字符兜底）
 */
@Composable
private fun ParagraphItem(
    text: String,
    paragraphIndex: Int,
    annotations: List<BookAnnotationEntity>,
    colorSchemeHolder: ParagraphColors,
    /** ⚠️ v16：直接传入 ViewModel 引用（不订阅 State）
     *   之前用参数 selectionParagraphIndex = selection?.paragraphIndex →
     *      只要 selection State 一变 → 所有 ParagraphItem 全部 recompose →
     *      长按 CAB 触发 s/e 变化 → onSelectionChanged 写 State → ParagraphItem recompose →
     *      AndroidView factory/update 重跑 → View 重建 → CAB 立即 destroy → 选字流程永远无法完成
     *   现在改为「仅在需要时（onOtherParagraphTouched）才读 viewModel.selection.value 的当前值」
     *   → 完全切断「selection State 写 → ParagraphItem recompose」链条！长按时 View 永不重建！
     */
    readerViewModel: com.zhiji.app.ui.feature.reader.ReaderViewModel,
    onSelectionChanged: (startInParagraph: Int, endInParagraph: Int) -> Unit,
    onTapCharPosition: (charPosInParagraph: Int) -> Unit,
    onOpenAnnotation: (annotation: BookAnnotationEntity) -> Unit,
    onRequestAnnotateFromActionMode: () -> Unit,
    onRequestMemoryFromActionMode: () -> Unit,
    /** 点到别的段落时触发（外层调用 viewModel.dismissSelection() 清浮条） */
    onTouchOtherParagraph: () -> Unit,
    /** 沉浸式功能栏：单击正文（非长按/非注释命中）时触发（外层切换上下功能栏显隐） */
    onPlainTap: () -> Unit = {},
    /** 翻页式：单击正文按屏幕坐标分区回调（左=上一页/右=下一页/中=切功能栏）；非 null 时优先于 onPlainTap */
    onPlainTapAt: ((Float) -> Unit)? = null,
    /** 正文字体缩放系数（1.0 = 16sp 原始字号；与分页测量字号保持一致） */
    fontScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    // ActionMode 自定义菜单常量 id（业内标准做法，android.R.id / 自拟均可）
    // （函数体内局部 val 不能加 const，不影响运行）
    val MENU_ANNOTATE = 1001
    val MENU_MEMORY = 1002
    // setTag(int,Any) 的 key 必须是「应用资源 id」（R.id.xxx），
    // 不能用硬编码整数（如 10001）→ 否则 API 28+ 抛 IllegalArgumentException:
    //     "The key must be an application-specific resource id"
    // 在 res/values/ids.xml 中声明全局唯一 id，setTag/getTag 时使用。
    val TAG_RUNNABLE = com.zhiji.app.R.id.tag_runnable
    val TAG_SPANNABLE = com.zhiji.app.R.id.tag_spannable
    val TAG_CAB_STARTED = com.zhiji.app.R.id.tag_cab_started
    val TAG_ACTION_MODE_CALLBACK = com.zhiji.app.R.id.tag_action_mode_callback // v27：Step3 poll 取 actionModeCallback 引用
    val TAG_FIX_PENDING = com.zhiji.app.R.id.tag_fix_pending // v27：防止 poll 每 20ms 重复启动修复
    val context = LocalContext.current

    // 把 Compose 的颜色和字号转成原生的（提前转避免 update 里重建）
    val onSurfaceArgb = colorSchemeHolder.onSurface.toArgb()
    val primaryArgb = colorSchemeHolder.primary.toArgb()
    // 选中背景色 = primary 色 25% 透明
    val highlightArgb = androidx.core.graphics.ColorUtils.setAlphaComponent(primaryArgb, (255 * 0.25f).toInt())

    // 每段自己的 GestureDetector：绑定 paragraphIndex 独立 remember，避免每次重组反复 new → 触发不必要的 setOnTouchListener
    val gestureDetector = remember(paragraphIndex, onTapCharPosition, text) {
        val listener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (text.isEmpty()) return false
                // 注意：这里 layout 必须在运行时通过 tv 获取，remember 时 layout 还是 null → 留在 onTouchListener 里再判
                return false
            }
        }
        GestureDetector(context, listener)
    }

    AndroidView(
        // ⚠️ 关键修复：外层 Modifier 绝对不能 clip() / graphicsLayer alpha 套在 AndroidView 上
        //    Compose BOM 2024.04(UI 1.6.6) 中，对原生 View 做 Modifier.clip 会触发 measure 阶段
        //    GraphicsLayer + software layer 冲突，直接在 BoxMeasurePolicy measure pass 崩溃闪退。
        //    段落本来就是白底无圆角，不需要 clip，用 padding 就够了。
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        factory = { ctx ->
            // factory 全 try/catch：任何原生 View 初始化异常 → 仅 Log.e 不冒泡 crash
            try {
                // ⚠️ 方案 B：用自定义 AnnotationHitTextView（覆盖 dispatchTouchEvent，100% 不被 Compose wrapper/SelectionController 吞事件）
                val tv = AnnotationHitTextView(ctx)
                // AndroidView 明确 LayoutParams：MATCH_PARENT 宽 / WRAP_CONTENT 高，
                // 避免默认 LayoutParams(0,0) 被 Compose measure policy 拿到非法 size 崩
                tv.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 顺序不能乱：先 set 文本相关属性，再最后 setTextIsSelectable(true)
                // （setTextIsSelectable 内部会触发 setFocusable/longClickable/cursorVisible 一串 flag）
                tv.setTextColor(onSurfaceArgb)
                tv.highlightColor = highlightArgb
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
                // 26sp 的行高：LineSpacingExtra = (26sp - 16sp) × 字体缩放，用 px
                val extraLineSpacing = 10f * fontScale * ctx.resources.displayMetrics.scaledDensity
                tv.setLineSpacing(extraLineSpacing, 1f)
                tv.setPadding(0, 0, 0, 0)
                runCatching { tv.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
                // 先放一段空字符串，避免 factory 阶段没有 text 时 wrap_content 高度 0 触发 measure 异常
                tv.text = ""

                // ╔══════════════════════════════════════════════════════════════════╗
                // ⚠️ v14 问题3根因：LinkMovementMethod 与 setTextIsSelectable(true) 互斥冲突！
                //    业内公开资料 CodeGenes/Android-TextView-Click-Bug-Sample 验证过：
                //      ① 要想 ClickableSpan 自动触发 → movementMethod = LinkMovementMethod
                //      ② 要想长按出系统选字手柄+CAB菜单 → setTextIsSelectable(true) 内部强制 ArrowKeyMovementMethod
                //    两者同时设置 → SelectionController 行为异常 → 长按无选中光标+CAB（只看到浮条闪动，因为selection被改了一点）
                //    我们的三层命中链（路径A手动查span/路径C内存/路径BSQL）已经彻底不需要 LinkMovementMethod！
                //    所以**删除 LinkMovementMethod**，让 setTextIsSelectable 独占控制长按选字 → 问题3解决！
                // ╚══════════════════════════════════════════════════════════════════╝

                // ===== 业内成熟方案 A：塞入系统选字 CAB 自定义菜单项 =====
                // ⚠️ 提前把 callback 存变量（factory 临时用，update lambda 会重新绑定）
                val actionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        // v29 修复：factory callback 也必须设 TAG_CAB_STARTED=true
                        //   否则系统如果用了 factory callback 启动 CAB → TAG_CAB_STARTED 仍为 false →
                        //   fixRunnable 会误判「CAB未启动」而重复启动第二个 TYPE_FLOATING CAB → 双CAB竞争！
                        tv.setTag(TAG_CAB_STARTED, true)
                        if (menu == null) return true
                        runCatching {
                            menu.add(Menu.NONE, MENU_ANNOTATE, Menu.NONE, "📝 注释")
                                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            menu.add(Menu.NONE, MENU_MEMORY, Menu.NONE, "🧠 记忆库")
                                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        return true // true 表示保留系统默认项（复制/剪切/分享等）+ 叠加我们的自定义
                    }
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        return when (item?.itemId) {
                            MENU_ANNOTATE -> {
                                val s = tv.selectionStart.coerceAtLeast(0)
                                val eRaw = tv.selectionEnd.coerceAtLeast(0)
                                val e = if (eRaw >= s) eRaw else s
                                if (s < e) {
                                    onSelectionChanged(s, e)
                                    onRequestAnnotateFromActionMode()
                                }
                                runCatching { mode?.finish() }
                                true
                            }
                            MENU_MEMORY -> {
                                val s = tv.selectionStart.coerceAtLeast(0)
                                val eRaw = tv.selectionEnd.coerceAtLeast(0)
                                val e = if (eRaw >= s) eRaw else s
                                if (s < e) {
                                    onSelectionChanged(s, e)
                                    onRequestMemoryFromActionMode()
                                }
                                runCatching { mode?.finish() }
                                true
                            }
                            else -> false
                        }
                    }
                    override fun onDestroyActionMode(mode: ActionMode?) {
                        // v29 修复：factory callback onDestroy 也必须清标记
                        tv.setTag(TAG_CAB_STARTED, false)
                        Log.d("FloatBarSync", "factory#$paragraphIndex onDestroyActionMode → clear selection")
                        onSelectionChanged(0, 0)
                    }
                }
                // ⚠️ v29 修复顺序：先 setTextIsSelectable(true)，**然后**再设 customSelectionActionModeCallback
                //   原因：setTextIsSelectable(true) 内部会设置系统默认的 customSelectionActionModeCallback
                //   （在国产ROM上可能包含「AI写作/常用语」等厂商注入菜单项），如果我们先设自己的 callback，
                //   然后 setTextIsSelectable 又覆盖了我们的 → 系统就用了厂商注入的默认 callback！
                //   正确顺序：先 setTextIsSelectable（设置系统默认 callback），然后我们的 callback 覆盖上去 →
                //   系统只能用我们的 callback → 只有一个 CAB（无竞争）
                tv.setTextIsSelectable(true)
                tv.customSelectionActionModeCallback = actionModeCallback
                Log.d("ParagraphSel", "#$paragraphIndex setTextIsSelectable ok, movementMethod=${tv.movementMethod?.javaClass?.simpleName} isLongClickable=${tv.isLongClickable} selectable=${tv.isTextSelectable}")
                // ⚠️ v24 教训：绝对不能调用 setOnLongClickListener（哪怕返回false）！会替换 setTextIsSelectable(true) 内部设置的系统默认监听器！
                //     系统默认的 OnLongClickListener 才是触发 SelectionController.startSelection() 画手柄的关键，被替换后长按就只能检测到、永远不画手柄。
                //     调试长按检测请用 AnnotationHitTextView.performLongClick() 重写法（先super再打Toast，100%不干扰）。

                // 生命周期监听：item 被 LazyList 回收 detach → 立刻 removeCallbacks
                //     彻底解决旧 postDelayed Message 在复用之后跑 → 回调越界值
                tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        try {
                            (v.getTag(TAG_RUNNABLE) as? Runnable)?.let { (v as TextView).removeCallbacks(it) }
                        } catch (_: Throwable) {}
                    }
                })

                // ====== v8 强调试 1：段索引绑定（让 dispatchTouchEvent Toast 知道是第几段） ======
                tv.paragraphIndex = paragraphIndex
                // v49 沉浸式功能栏：单击正文（非长按/非注释命中）→ 切换上下功能栏显隐
                tv.onPlainTap = onPlainTap
                tv.onPlainTapAt = onPlainTapAt
                // ====== v8 强调试 2：确认 factory 返回的实例真的是我们的自定义类（防止 Compose wrapper 替换类型） ======
                runCatching {
                    var c: Class<*>? = tv::class.java
                    val chain = mutableListOf<String>()
                    while (c != null) { chain.add(c.simpleName); c = c.superclass }
                    Log.e("HitTV-VERIFY", "#$paragraphIndex 实例类继承链：${chain.joinToString(" → ")}")
                    if (tv !is AnnotationHitTextView) {
                        // v41 移除崩溃 Toast（保留 Log）
                        Log.e("HitTV-VERIFY", "❌FATAL #$paragraphIndex 实例类型居然不是 AnnotationHitTextView！这意味着 Compose/父类 wrapper 做了替换！")
                    }
                }

                // ================================================================
                // v10 双保险兜底：factory 里先给一个「临时但有功能」的 resolveTap（SQL fallback 路径可用）
                //     为什么要 factory 先设？因为之前 v8-v9 用户现场「✅resolveTap赋值成功」从未弹，
                //     → 说明 update lambda 可能被全局 try/catch 吞或者根本没跑，
                //        → resolveTap 就一直用默认的 `{_,_->false}`，所以 after resolveTap returned=false 啥都不做。
                //     现在 factory 第一时间塞一个兜底版：
                //        · 布局可用时也尝试 Layout 解算（Step 0 resolveTap 同样 5 层防御简化版）；
                //        · 命中不到 Span（factory 时没设 Span）就直接走 SQL fallback（onTapCharPosition 参数在 factory 闭包里）。
                //     这样哪怕 update 真的出问题不跑，点击下划线位置也能查 DB 命中注释，不至于白屏。
                // ================================================================
                tv.resolveTap = resolveTap@{ ev: MotionEvent, reason: String ->
                    android.util.Log.e("HitTV-TRACE", "factory兜底resolveTap reason=$reason p#$paragraphIndex")
                    val layout = tv.layout ?: return@resolveTap false
                    val totalPadLeft = tv.totalPaddingLeft
                    val totalPadTop = tv.totalPaddingTop
                    val xInLayout = ev.x - totalPadLeft
                    val yInLayout = ev.y - totalPadTop
                    val line = layout.getLineForVertical(yInLayout.toInt())
                        .coerceAtLeast(0).coerceAtMost((layout.lineCount - 1).coerceAtLeast(0))
                    val rawOffset = runCatching { layout.getOffsetForHorizontal(line, xInLayout) }.getOrDefault(0)
                    val lineStart = layout.getLineStart(line)
                    val lineEnd = layout.getLineEnd(line)
                    val tvLen = tv.text?.length?.coerceAtLeast(0) ?: 0
                    val charOffset = rawOffset.toInt()
                        .coerceAtLeast(0).coerceAtMost(tvLen).coerceAtMost(lineEnd)

                    // v13：factory 兜底路径 C 内存查表（Readium 同款），不要 Toast，直接命中
                    var handled = false
                    if (annotations.isNotEmpty()) {
                        android.util.Log.e("HitTV-TRACE", "factory兜底pathC内存查表 annotations=${annotations.size}条 @$charOffset")
                        val hitAnn = annotations.firstOrNull { ann ->
                            val s = ann.startInParagraph.coerceAtLeast(0)
                            val e = ann.endInParagraph.coerceAtLeast(s)
                            val hitS = (s - 2).coerceAtLeast(0)
                            val hitE = (e + 2).coerceAtMost(tvLen)
                            charOffset in hitS until hitE
                        }
                        if (hitAnn != null) {
                            android.util.Log.e("HitTV-TRACE", "factory兜底pathC内存命中 ann.id=${hitAnn.id}")
                            runCatching { onOpenAnnotation(hitAnn) }
                            handled = true
                        }
                    }
                    if (!handled) {
                        android.util.Log.e("HitTV-TRACE", "factory兜底pathC未命中 → 走pathB SQL查 @$charOffset")
                        onTapCharPosition(charOffset)
                    }
                    true
                }
                runCatching {
                    val factoryHash = System.identityHashCode(tv.resolveTap)
                    android.util.Log.e("HitTV-VERIFY", "#$paragraphIndex factory 兜底 resolveTap assignedHash=$factoryHash（update 执行时会覆盖这个值）")
                    // v13 移除 factory 兜底 hash Toast（不要让用户进正文就弹一串）
                }

                tv
            } catch (t: Throwable) {
                Log.e("ParagraphItem", "factory TextView(#$paragraphIndex) crash → fallback empty View", t)
                View(ctx).also {
                    it.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    )
                }
            }
        },
        update = { rawView ->
            // update 全 try/catch：任何 Spannable/selection/onTouchListener 异常 → 仅 Log.w 不冒泡 crash
            val tv = rawView as? AnnotationHitTextView ?: run {
                Log.w("ParagraphItem", "update rawView not AnnotationHitTextView(#$paragraphIndex) → skip")
                return@AndroidView
            }
            // ╔══════════════════════════════════════════════════════════════════╗
            // ⚠️⚠️ v10 修复根因：全局 try/catch 把中间异常吞掉 → resolveTap 从未赋值
            //    之前做法：Step1(Spannable) → Step2(poll) → Step3(resolveTap赋值)
            //             → Step1/Step2 任何异常 throw → 被最外层 catch 吞 → Step3 永不执行
            //             → resolveTap 是默认的 {_,_->false} → 点击无任何反应（符合你看到的四图！）
            //    现在：Step3(resolveTap赋值) 挪到 **第0步，最先执行**，并且每一步独立 try/catch+Toast，
            //          同时 factory 里再给一个兜底临时 resolveTap，双保险保证一定能命中。
            // ╚══════════════════════════════════════════════════════════════════╝
            val currentText: String = text
            val safeLen: Int = currentText.length.coerceAtLeast(0)

            // 字体缩放变化 → 同步字号与行距（与分页测量使用的字号保持一致，避免测量/实际排版不符）
            runCatching {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
                tv.setLineSpacing(10f * fontScale * tv.resources.displayMetrics.scaledDensity, 1f)
            }

            // =================================================================
            // v27：View 回收复用时，factory 初始化的 paragraphIndex 是旧值，这里在 update 开头强制同步最新段号
            //      → 保证 AnnotationHitTextView 内部 ①② Toast 的段号来源与 update lambda 完全一致
            // v34：不要无条件重置 TAG_CAB_STARTED！
            //      系统 CAB 启动后会触发 recompose → update lambda 重跑 → 如果强制重置 TAG_CAB_STARTED=false
            //      → poll 继续检测到 tagCab=false → 以为 CAB 没启动 → 启动 TYPE_FLOATING → 双 CAB 竞争！
            //      改为：只在 CAB 未启动时重置；CAB 已启动时保留 true 状态
            // =================================================================
            runCatching {
                tv.paragraphIndex = paragraphIndex
                // v49 每次 update 刷新单击回调（外层 toolbarsVisible 状态变化时 lambda 引用可能更新）
                tv.onPlainTap = onPlainTap
                tv.onPlainTapAt = onPlainTapAt
                // v34 修复：不要无条件重置 TAG_CAB_STARTED
                val cabStarted = tv.getTag(TAG_CAB_STARTED) == true
                if (!cabStarted) {
                    tv.setTag(TAG_CAB_STARTED, false)
                }
                tv.setTag(TAG_FIX_PENDING, false)
            }

            // =================================================================
            // Step 0 (MOST PRIORITY)：最先设置 resolveTap，保证任何点击都不会走默认 lambda
            // =================================================================
            runCatching {
                tv.resolveTap = resolveTap@{ ev: MotionEvent, reason: String ->
                    // ============================================================
                    // v13 移除所有调试 Toast（之前堆积 10+ 条 Toast 排队 1-2s 造成用户「心理延迟感」）
                    //    完整 Log.e 仍然保留，开发调试时用 Logcat 看；崩溃 Toast 仍然保留（只有异常时弹）。
                    // ============================================================
                    android.util.Log.e(
                        "ParagraphTap",
                        "#$paragraphIndex >>> ENTER resolveTap($reason) tv.text.len=${tv.text?.length} currentText.len=${currentText.length}"
                    )

                    // 空文本直接放弃（防御 0：长度合法性）
                    if (currentText.isEmpty()) {
                        android.util.Log.e("ParagraphTap", "#$paragraphIndex currentText.isEmpty → abort")
                        return@resolveTap false
                    }

                    // ========= 防御 ①：强制取最新 Layout =========
                    val layout = tv.layout
                    if (layout == null) {
                        Log.w("ParagraphTap", "#$paragraphIndex layout null ($reason) → 放弃命中")
                        return@resolveTap false
                    }

                    // ========= 防御 ②：实时取 padding（不用缓存 remember 值）=========
                    val totalPadLeft = tv.totalPaddingLeft
                    val totalPadTop = tv.totalPaddingTop
                    val xInLayout = ev.x - totalPadLeft
                    val yInLayout = ev.y - totalPadTop

                    // ========= 防御 ③：line 合理性（line ∈ [0, lineCount)）=========
                    val line = layout.getLineForVertical(yInLayout.toInt())
                        .coerceAtLeast(0)
                        .coerceAtMost(layout.lineCount - 1)
                    val lineTop = layout.getLineTop(line)
                    val lineBottom = layout.getLineBottom(line)
                    if (yInLayout < lineTop - 20 || yInLayout > lineBottom + 20) {
                        Log.d("ParagraphTap", "#$paragraphIndex yInLayout=$yInLayout 不在 line#$line 范围[$lineTop,$lineBottom] → 放弃")
                    }

                    // ========= 防御 ④：offset 合法性（0 ≤ offset ≤ safeLen，且 0 ≤ offset ≤ line 末尾）=========
                    val rawOffset = runCatching { layout.getOffsetForHorizontal(line, xInLayout) }.getOrDefault(0)
                    val lineStart = layout.getLineStart(line)
                    val lineEnd = layout.getLineEnd(line)
                    val charOffset = rawOffset.toInt()
                        .coerceAtLeast(0)
                        .coerceAtMost(safeLen)
                        .coerceAtMost(lineEnd)

                    Log.d(
                        "ParagraphTap",
                        "#$paragraphIndex $reason → x=${ev.x.toInt()}/padL=$totalPadLeft → xL=$xInLayout; " +
                                "y=${ev.y.toInt()}/padT=$totalPadTop → yL=$yInLayout; line=$line[$lineStart..$lineEnd) → off=$charOffset/$safeLen"
                    )

                    // ========= 路径 A（主路径，ClickableSpan）：优先从 TAG_SPANNABLE 里取（彻底绕开系统解包）=========
                    var handled = false
                    val spanSrc: Spannable? = (tv.getTag(TAG_SPANNABLE) as? Spannable) ?: (tv.text as? Spannable)
                    if (spanSrc != null && safeLen > 0) {
                        val rangeStart = (charOffset - 1).coerceAtLeast(0)
                        val rangeEnd = (charOffset + 3).coerceAtMost(safeLen)
                        if (rangeStart < rangeEnd) {
                            val hits = runCatching {
                                spanSrc.getSpans(rangeStart, rangeEnd, ClickableSpan::class.java)
                            }.getOrDefault(emptyArray())
                            Log.d("ParagraphTap", "#$paragraphIndex range[$rangeStart,$rangeEnd) hits=${hits.size} $reason")
                            if (hits.isNotEmpty()) {
                                // 路径 A 命中：直接调用 ClickableSpan.onClick（内部会 call onOpenAnnotation 打开弹窗）
                                hits.lastOrNull()?.onClick(tv)
                                handled = true
                            }
                        }
                    } else {
                        Log.d("ParagraphTap", "#$paragraphIndex spanSrc null → skip path A ($reason)")
                    }

                    // ╔══════════════════════════════════════════════════════════════╗
                    // 路径 C（内存查表，Readium highlightActivated 同款架构）⚠️
                    // ╚══════════════════════════════════════════════════════════════╝
                    // 纯内存 O(N) IntRange 匹配，<10ms 瞬时命中，不用 DB，无任何 IO
                    if (!handled && annotations.isNotEmpty()) {
                        val hitAnn = annotations.firstOrNull { ann ->
                            val s = ann.startInParagraph.coerceAtLeast(0)
                            val e = ann.endInParagraph.coerceAtLeast(s)
                            // 容错 ±2 字符（指腹按下去偏左偏右也能命中）
                            val hitS = (s - 2).coerceAtLeast(0)
                            val hitE = (e + 2).coerceAtMost(safeLen)
                            charOffset in hitS until hitE
                        }
                        if (hitAnn != null) {
                            Log.d("ParagraphTap", "#$paragraphIndex pathC内存命中 ann.id=${hitAnn.id} @[$charOffset] vs [${hitAnn.startInParagraph},${hitAnn.endInParagraph})")
                            // 路径 C 命中：直接打开注释弹窗（<10ms，瞬时无感知延迟）
                            runCatching { onOpenAnnotation(hitAnn) }.onFailure { t ->
                                // v41 移除崩溃 Toast（保留 Log）
                                Log.e("ParagraphTap", "#$paragraphIndex pathC命中 onOpenAnnotation CRASHED", t)
                            }
                            handled = true
                        } else {
                            val ranges = annotations.take(10).joinToString(";") { "[${it.startInParagraph},${it.endInParagraph})" }
                            Log.d("ParagraphTap", "#$paragraphIndex pathC内存无命中 @$charOffset 同段范围:$ranges")
                        }
                    }

                    // ========= 路径 B（兜底，SQL）：A/C 都没命中才查 DB =========
                    if (!handled) {
                        Log.d("ParagraphTap", "#$paragraphIndex $reason offset=$charOffset → A/C均未命中 → path B SQL")
                        onTapCharPosition(charOffset)
                    }
                    return@resolveTap handled
                }
                val assignedHash = System.identityHashCode(tv.resolveTap)
                Log.e("ParagraphTap", "#$paragraphIndex update resolveTap assigned → hash=$assignedHash annotations=${annotations.size} text.len=${currentText.length}")
                // v13 移除 assignedHash Toast（不要让用户进阅读页就弹一串 Toast 队列）
            }.onFailure { t ->
                // v41 移除崩溃 Toast（保留 Log）
                Log.e("ParagraphItem", "Step0 resolveTap赋值 CRASHED #$paragraphIndex", t)
            }

            // =================================================================
            // Step 1：颜色/字号/段落基础属性（独立 try/catch）
            // =================================================================
            runCatching {
                tv.setTextColor(onSurfaceArgb)
                tv.highlightColor = highlightArgb
            }.onFailure { t ->
                // v41 移除崩溃 Toast（保留 Log）
                Log.e("ParagraphItem", "Step1颜色设置 CRASHED #$paragraphIndex", t)
            }

            // =================================================================
            // Step 2：文本 + Spannable（v21彻底移除ClickableSpan，下划线改用UnderlineSpan；独立 try/catch）
            // =================================================================
            runCatching {
                // v21 修复：彻底移除 ClickableSpan（与 setTextIsSelectable(true) 根本冲突！）
                //   文字区域设置 ClickableSpan → 手柄拖到这里 SelectionController 会崩清手柄 → 空格正常文字不行（用户关键线索）
                // 视觉下划线改用原生 UnderlineSpan；颜色=ForegroundColorSpan；加粗=StyleSpan(BOLD)（视觉效果与之前 ClickableSpan.updateDrawState 完全一致）
                // 注释点击检测完全由 AnnotationHitTextView.dispatchTouchEvent 三层命中链处理：
                //   路径C（内存 annotations IntRange.contains(charOffset, ±2字符容错）→ 路径B（SQL 查询兜底）
                val spannable = SpannableString(currentText)
                val sorted = annotations.sortedBy { it.startInParagraph }
                for (ann in sorted) {
                    val s = ann.startInParagraph.coerceIn(0, safeLen)
                    val e = ann.endInParagraph.coerceIn(s, safeLen)
                    if (s >= e) continue
                    runCatching {
                        spannable.setSpan(
                            ForegroundColorSpan(primaryArgb),
                            s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            android.text.style.UnderlineSpan(),
                            s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            StyleSpan(Typeface.BOLD),
                            s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                // =================================================================
                // v27 核心修复（替换 v20 SpanWatcher 方案）：
                // v20 SpanWatcher 的致命问题：与 LazyColumn View 回收复用根本冲突！
                //   旧Paragraph（#p11）被回收复用为新Paragraph（#p15）后，旧spannable上的旧spanWatcher仍被
                //   SelectionController 在旧spannable上触发 → 段号乱飘、当前显示的新spannable上永远没有手柄Span！
                // v27 彻底绕开 SpanWatcher，改用 Step3 已经存在的 20ms poll 轮询（稳定读 tv.selectionStart/End）
                //   来做 v20 的修复：发现 s<e → 检测 CAB 是否启动 → 未启动则恢复手柄 + 手动 TYPE_FLOATING startActionMode
                //   → poll 永远运行在「当前最新 ParagraphItem 的 update lambda 上下文」，不存在旧对象引用问题！
                // =================================================================
                val actionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        // v41 移除调试 Toast（节点④）
                        tv.setTag(TAG_CAB_STARTED, true) // v20 标记：CAB 启动成功
                        if (menu == null) return true
                        runCatching {
                            menu.add(Menu.NONE, MENU_ANNOTATE, Menu.NONE, "📝 注释")
                                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            menu.add(Menu.NONE, MENU_MEMORY, Menu.NONE, "🧠 记忆库")
                                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        return when (item?.itemId) {
                            MENU_ANNOTATE -> {
                                val s = tv.selectionStart.coerceAtLeast(0)
                                val eRaw = tv.selectionEnd.coerceAtLeast(0)
                                val e = if (eRaw >= s) eRaw else s
                                if (s < e) {
                                    onSelectionChanged(s, e)
                                    onRequestAnnotateFromActionMode()
                                }
                                runCatching { mode?.finish() }
                                true
                            }
                            MENU_MEMORY -> {
                                val s = tv.selectionStart.coerceAtLeast(0)
                                val eRaw = tv.selectionEnd.coerceAtLeast(0)
                                val e = if (eRaw >= s) eRaw else s
                                if (s < e) {
                                    onSelectionChanged(s, e)
                                    onRequestMemoryFromActionMode()
                                }
                                runCatching { mode?.finish() }
                                true
                            }
                            else -> false
                        }
                    }
                    override fun onDestroyActionMode(mode: ActionMode?) {
                        tv.setTag(TAG_CAB_STARTED, false) // v20 清标记
                        Log.d("FloatBarSync", "update#$paragraphIndex onDestroyActionMode → clear selection")
                        onSelectionChanged(0, 0)
                    }
                }
                tv.customSelectionActionModeCallback = actionModeCallback // v20 覆盖 factory 临时设置
                // v27：把 callback 存到 TextView tag，供 Step3 poll 的 Runnable 跨作用域取到（因为 poll 在 Step3 runCatching 里创建）
                tv.setTag(TAG_ACTION_MODE_CALLBACK, actionModeCallback)

                runCatching { tv.setText(spannable, TextView.BufferType.SPANNABLE) }
                tv.setTag(TAG_SPANNABLE, spannable)
                // v27：删除 v26 toggle setTextIsSelectable(false→true) 重建代码
                //     重建会导致 SelectionController 状态重置引起乱跳，改用 poll 方案后不再需要
            }.onFailure { t ->
                // v41 移除崩溃 Toast（保留 Log）
                Log.e("ParagraphItem", "Step2 Spannable设置 CRASHED #$paragraphIndex", t)
            }

            // =================================================================
            // Step 3：Selection 轮询 poll runnable（独立 try/catch）
            // ╔══════════════════════════════════════════════════════════════════╗
            // ⚠️⚠️ v15-v16 根因修复（长按 CAB 从不触发 + 刚进页就写State清空连锁重组）：
            //   根因1（v15修了s==e部分）：SelectionController 长按初始化会调 setSelection(x,x) 锚点 → 写 State → 父重组
            //   根因2（v16修复）：lastSel 初始值 (-1,-1) && 读到 s=0,e=0 → 原规则 shouldReport 触发写 (0,0)
            //         → 刚进页每段都写一次 (0,0) → 父反复重组 → 大量 Poll 写 State Toast + 段被重建
            //   根因3（v16修复架构级）：selection State 变化 → 所有 ParagraphItem recompose → CAB destroy
            //         → 已通过「移除 selectionParagraphIndex 参数，改 readerViewModel 引用」切断
            //   lastReportedSelStart/End 存放到 AnnotationHitTextView 属性（View级别持久化）
            // ╚══════════════════════════════════════════════════════════════════╝
            // =================================================================
            runCatching {
                runCatching { (tv.getTag(TAG_RUNNABLE) as? Runnable)?.let { tv.removeCallbacks(it) } }
                val runnable = object : Runnable {
                    override fun run() {
                        try {
                            if (!tv.isAttachedToWindow) return
                            val realLen = tv.text?.length?.coerceAtLeast(0) ?: 0
                            val a = runCatching { tv.selectionStart }.getOrDefault(-1)
                            val b = runCatching { tv.selectionEnd }.getOrDefault(-1)
                            val start = a.coerceAtLeast(0).coerceAtMost(realLen)
                            val end = b.coerceAtLeast(start).coerceAtMost(realLen)
                            val isClear = start == 0 && end == 0
                            val isRealSelection = start < end
                            val lastS = tv.lastReportedSelStart
                            val lastE = tv.lastReportedSelEnd
                            val isDiff = start != lastS || end != lastE

                            if (isDiff) {
                                Log.d("ParagraphSel", "#$paragraphIndex s/e changed ($start,$end) last=($lastS,$lastE) realSel=$isRealSelection clear=$isClear")

                                // ════════════════════════════════════════════════
                                // v28 终极过滤规则 shouldReport（核心变化）：
                                //   ① 首次初始化状态(last=-1,-1) 同时读到 s=0,e=0 → 绝对 SKIP
                                //   ② 真实选中(start<end) 不写 State！→ 防止写State触发ParagraphItem recompose→重建spannable→手柄丢失
                                //      isRealSelection 走独立分支（下面 v28 修复块）只做 CAB 启动修复，完全不碰 State
                                //   ③ 清空(0,0)时必须上次不是清空才写 → 防止 onDestroyActionMode 反复清空
                                //   ④ s==e（SelectionController 光标锚点）永远不写（防止 500ms 长按初始化期写 State→重组）
                                // ════════════════════════════════════════════════
                                val isFirstInit = lastS == -1 && lastE == -1
                                // v28：isRealSelection 不再走 shouldReport（不写State），改为独立分支处理
                                val shouldReport = when {
                                    isFirstInit && isClear -> false // 规则①：首次(0,0)跳过
                                    isRealSelection -> false       // v28 规则②：真实选中不写State！走下面独立分支
                                    isClear && !(lastS == 0 && lastE == 0) -> true // 规则③：清空且上次非空写
                                    else -> false                    // 规则④：其余(s==e等)全部跳过
                                }

                                // v28 独立分支：真实选中 s<e 时，不写 State、不触发 recompose，
                                //   只做 CAB 启动修复（手柄恢复 + 手动 TYPE_FLOATING startActionMode）
                                //   关键：从 TextView 当前实时读 spannable/callback，不依赖闭包变量（防止被中间 recompose 替换）
                                if (isRealSelection && tv.getTag(TAG_FIX_PENDING) != true) {
                                    tv.setTag(TAG_FIX_PENDING, true)
                                    tv.setTag(TAG_CAB_STARTED, false)
                                    val savedS = start
                                    val savedE = end
                                    // v41 移除调试 Toast（节点③）
                                    // v35 修复：彻底删除 TYPE_FLOATING 启动逻辑
                                    //   原因：此 ROM 上系统 CAB 是 ROM 级注入的（绕过 customSelectionActionModeCallback），
                                    //   tagCab 始终为 false，导致 poll 误判 CAB 没启动 → 启动 TYPE_FLOATING → 双 CAB 竞争
                                    //   方案：系统 CAB 在此 ROM 上工作正常，不需要兜底
                                    //   poll 只保留「恢复手柄」功能（Selection.setSelection + invalidate）
                                    val fixRunnable = Runnable {
                                        runCatching {
                                            tv.setTag(TAG_FIX_PENDING, false)
                                            // 恢复手柄（Selection.setSelection + invalidate）
                                            val curSpannable = tv.getTag(TAG_SPANNABLE) as? Spannable
                                            if (curSpannable != null) {
                                                android.text.Selection.setSelection(curSpannable, savedS, savedE)
                                            }
                                            tv.invalidate()
                                            // v41 移除调试 Toast（节点③.5）
                                        }
                                    }
                                    tv.postDelayed(fixRunnable, 50L)
                                }

                                // 真正的 shouldReport 只处理清空逻辑（isClear），不处理 isRealSelection
                                if (shouldReport) {
                                    tv.lastReportedSelStart = start
                                    tv.lastReportedSelEnd = end
                                    Log.d("ParagraphSel", "#$paragraphIndex REPORT onSelectionChanged($start,$end) [only clear]")
                                    onSelectionChanged(start, end)
                                } else if (isRealSelection) {
                                    // v28：真实选中时不写 State，但更新 lastReportedSel 防止下次清空漏触发
                                    tv.lastReportedSelStart = start
                                    tv.lastReportedSelEnd = end
                                    Log.d("ParagraphSel", "#$paragraphIndex isRealSelection skip stateWrite (keep handles visible)")
                                } else {
                                    // 不写 State 的情况：lastSel 也**不要更新**（否则下次清空 isDiff=false 漏触发）
                                    Log.d("ParagraphSel", "#$paragraphIndex SKIP report (firstInitClear/s==e anchor), not write State")
                                }
                            }
                            tv.postDelayed(this, 20L)
                        } catch (t: Throwable) {
                            Log.w("ParagraphItem", "selection poll #$paragraphIndex abort", t)
                        }
                    }
                }
                tv.setTag(TAG_RUNNABLE, runnable)
                tv.post(runnable)
            }.onFailure { t ->
                // v41 移除崩溃 Toast（保留 Log）
                Log.e("ParagraphItem", "Step3 poll设置 CRASHED #$paragraphIndex", t)
            }

            // =================================================================
            // Step 4：ACTION_DOWN 钩子 + 段切换浮条清理（之前的 onBeforeDown/onOtherParagraphTouched）
            // v37 修复：删除 onBeforeDown 中无条件的 requestDisallowInterceptTouchEvent(true)
            //   原因：每次触摸都禁止父容器拦截 → 完全破坏正常滑动流畅性
            //   方案：只保留 performLongClick 成功时的临时禁止（v36 已做）+ onAfterUpOrCancel 的兜底释放
            // =================================================================
            runCatching {
                tv.onBeforeDown = {
                    // v37: 不再在此处禁止父容器拦截，让系统 touch slop 机制自然工作
                    // 用户手指不动 500ms 会自然触发 CheckForLongPress，不需要手动禁止
                }
                // ⚠️ v14/v37：保留 onAfterUpOrCancel 作为兜底释放
                tv.onAfterUpOrCancel = {
                    runCatching {
                        (tv.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        (tv.parent?.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                tv.onOtherParagraphTouched = {
                    val currentSelPara = readerViewModel.selection.value?.paragraphIndex
                    if (currentSelPara != null && currentSelPara != paragraphIndex) {
                        Log.d("FloatBarSync", "dispatchTouchEvent ACTION_DOWN 点 p#$paragraphIndex 但浮条挂在p#$currentSelPara → dismiss")
                        onTouchOtherParagraph()
                    }
                }
            }.onFailure { t ->
                // v41 移除崩溃 Toast（保留 Log）
                Log.e("ParagraphItem", "Step4 DOWN钩子设置 CRASHED #$paragraphIndex", t)
            }

            // 最外层兜底 catch（万一上面所有 try 都漏了）
            // （此处不再有全局 try/catch，因为每一步已经独立了）
        }
    )
}

// ============================================================
// 原文阅读辅助：段落交互回调统一封装 + 翻页式分页
// ============================================================

/** 段落交互回调集合：滚动式 / 翻页式 共用同一套（避免两处逻辑漂移） */
private data class ParaCallbacks(
    val onSelectionChanged: (start: Int, end: Int) -> Unit,
    val onTapCharPosition: (charPos: Int) -> Unit,
    val onOpenAnnotation: (BookAnnotationEntity) -> Unit,
    val onRequestAnnotateFromActionMode: () -> Unit,
    val onRequestMemoryFromActionMode: () -> Unit,
    val onTouchOtherParagraph: () -> Unit,
    val onPlainTap: () -> Unit
)

/** 构造某段的交互回调（所有回调外层 try/catch：LazyList 回收/reuse 期间过期 lambda 一律静默丢） */
private fun paraCallbacks(
    viewModel: ReaderViewModel,
    idx: Int,
    para: String,
    onPlainTap: () -> Unit
) = ParaCallbacks(
    onSelectionChanged = { start, end ->
        runCatching { viewModel.onParagraphSelectionChanged(idx, para, start, end) }
            .onFailure { t -> Log.w("ParagraphItem", "selection cb #$idx($start..$end) dropped", t) }
    },
    onTapCharPosition = { charPos ->
        runCatching { viewModel.handleParagraphTap(idx, charPos) }
            .onFailure { t -> Log.w("ParagraphItem", "tap cb #$idx pos=$charPos dropped", t) }
    },
    onOpenAnnotation = { annotation ->
        runCatching { viewModel.openAnnotationFromTapHit(annotation) }
            .onFailure { t -> Log.w("ParagraphItem", "actionMode/clickable open-annotation cb #$idx dropped", t) }
    },
    onRequestAnnotateFromActionMode = {
        runCatching { viewModel.openAnnotationEditorFromSelection() }
            .onFailure { t -> Log.w("ParagraphItem", "actionMode annotate cb #$idx dropped", t) }
    },
    onRequestMemoryFromActionMode = {
        runCatching { viewModel.openMemoryConfirmFromSelection() }
            .onFailure { t -> Log.w("ParagraphItem", "actionMode memory cb #$idx dropped", t) }
    },
    onTouchOtherParagraph = {
        runCatching { viewModel.dismissSelection() }
            .onFailure { t -> Log.w("ParagraphItem", "dismissSelection cb #$idx dropped", t) }
    },
    onPlainTap = onPlainTap
)

/**
 * 按真实排版测量分页（一屏一页，页内无需上下滚动）：
 *  - 用 [StaticLayout] 以与正文 [ParagraphItem] 完全相同的 16sp SERIF、26sp 行高测量每段换行后的真实高度
 *  - 逐段累加装页，装不下就另起一页；单段本身超过一屏时独占一页（页内纵向滚动兜底，不会丢文字）
 *  - 首页额外预留标题/作者区高度
 *  - 测量在后台线程执行，大文件切换「翻页」时不会卡住 UI
 * @return 每页的段落区间（半开区间 [first, last]），参数非法时返回空表
 */
private fun measurePageRanges(
    paragraphs: List<String>,
    contentWidthPx: Int,
    contentHeightPx: Int,
    densityScale: Float,
    fontScale: Float = 1f
): List<IntRange> {
    if (paragraphs.isEmpty() || contentWidthPx <= 0 || contentHeightPx <= 0) return emptyList()
    val textSizeSp = PARA_TEXT_SP * fontScale
    val lineHeightSp = PARA_LINE_SP * fontScale
    val paint = TextPaint().apply {
        isAntiAlias = true
        textSize = textSizeSp * densityScale
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    // 段间开销：ParagraphItem 上下 padding 各 6dp + 段后 Spacer 4dp；空段是 10dp 占位
    val paraBoxPx = (12f * densityScale).toInt()
    val paraGapPx = (4f * densityScale).toInt()
    val blankGapPx = (10f * densityScale).toInt()
    val titleReservePx = (78f * densityScale).toInt() // 首页标题 + 作者区
    val lineHeightPx = lineHeightSp * densityScale
    val lineSpacingExtraPx = lineHeightPx - paint.textSize

    fun heightOf(para: String): Int {
        if (para.isBlank()) return blankGapPx
        return runCatching {
            StaticLayout.Builder
                .obtain(para, 0, para.length, paint, contentWidthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(lineSpacingExtraPx, 1f)
                .setIncludePad(true) // 与 TextView 默认 includeFontPadding 一致
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
                .height + paraBoxPx + paraGapPx
        }.getOrDefault((lineHeightPx * 2).toInt() + paraBoxPx + paraGapPx)
    }

    val pages = mutableListOf<IntRange>()
    var start = 0
    var used = titleReservePx
    var i = 0
    while (i < paragraphs.size) {
        val h = heightOf(paragraphs[i])
        // 装不下且本页已有内容 → 收页另起（保证每页正好一屏）
        if (i > start && used + h > contentHeightPx) {
            pages += start until i
            start = i
            used = 0
        }
        used += h
        i++
    }
    pages += start until paragraphs.size
    return pages
}

/**
 * 原文·翻页式：整屏一页，左右滑动切换。
 * 每页渲染一段段落区间；超长段用页内纵向滚动兜底（标题只在第 0 页展示）。
 * 复用 ParagraphItem（选字/注释/记忆库/单击切换功能栏全部一致）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OriginalPagerView(
    paragraphs: List<String>,
    book: BookEntity?,
    pages: List<IntRange>,
    pagerState: PagerState,
    annotations: List<BookAnnotationEntity>,
    paragraphColors: ParagraphColors,
    readerViewModel: ReaderViewModel,
    fontScale: Float = 1f,
    onMiddleTap: () -> Unit
) {
    // 翻页式：单击左 1/3 = 上一页，右 1/3 = 下一页，中间 = 切换上下功能栏
    val tapScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val onZoneTap: (Float) -> Unit = { rawX ->
        val last = pages.lastIndex
        when {
            rawX < screenWidthPx / 3f -> tapScope.launch {
                runCatching { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceIn(0, maxOf(0, last))) }
            }
            rawX > screenWidthPx * 2f / 3f -> tapScope.launch {
                runCatching { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceIn(0, maxOf(0, last))) }
            }
            else -> onMiddleTap()
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { pageIdx ->
        val range = pages.getOrNull(pageIdx) ?: return@HorizontalPager
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (pageIdx == 0) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(
                        text = book?.title ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    book?.author?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "作者：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
            }
            range.forEach { idx ->
                val para = paragraphs.getOrNull(idx) ?: return@forEach
                if (para.isBlank()) {
                    Spacer(Modifier.size(10.dp))
                } else {
                    val paragraphAnnotations = remember(annotations, idx) {
                        runCatching { annotations.filter { it.paragraphIndex == idx } }
                            .getOrDefault(emptyList())
                    }
                    val cb = paraCallbacks(readerViewModel, idx, para, onMiddleTap)
                    ParagraphItem(
                        text = para,
                        paragraphIndex = idx,
                        annotations = paragraphAnnotations,
                        colorSchemeHolder = paragraphColors,
                        readerViewModel = readerViewModel,
                        onSelectionChanged = cb.onSelectionChanged,
                        onTapCharPosition = cb.onTapCharPosition,
                        onOpenAnnotation = cb.onOpenAnnotation,
                        onRequestAnnotateFromActionMode = cb.onRequestAnnotateFromActionMode,
                        onRequestMemoryFromActionMode = cb.onRequestMemoryFromActionMode,
                        onTouchOtherParagraph = cb.onTouchOtherParagraph,
                        onPlainTap = cb.onPlainTap,
                        onPlainTapAt = onZoneTap,
                        fontScale = fontScale
                    )
                    Spacer(Modifier.size(4.dp))
                }
            }
        }
    }
}

// ============================================================
// Phase 3 中英混排改写：Tab 切换 + 改写后页面展示
// ============================================================

/** 顶部「原文 | 改写后」双阅读界面切换栏；原文模式下额外提供 滚动/翻页 切换 */
@Composable
private fun RewriteTabBar(
    rewriteView: Boolean,
    originalPageMode: String,
    onSelectOriginal: () -> Unit,
    onSelectRewritten: () -> Unit,
    onTogglePageMode: () -> Unit,
    onFontClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Filled.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "中英结合",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        // 字体大小入口：正文字号自定义（持久化记忆，下次打开保持）
        IconButton(onClick = onFontClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.FormatSize,
                contentDescription = "字体大小",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        // 滚动式 / 翻页式 一键切换（点击标签即切换，标签文字显示当前模式；原文/改写后界面都生效）
        FilterChip(
            selected = false,
            onClick = onTogglePageMode,
            label = { Text(if (originalPageMode == "scroll") "滚动" else "翻页", fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "切换阅读方式",
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        FilterChip(
            selected = !rewriteView,
            onClick = onSelectOriginal,
            label = { Text("原文") }
        )
        FilterChip(
            selected = rewriteView,
            onClick = onSelectRewritten,
            label = { Text("改写后") }
        )
    }
}

/** 统计弹窗查看的类别：全部 / 会的 / 不熟的 / 不会的 */
private enum class StatsSheetType { ALL, KNOWN, SEMI, UNKNOWN }

/**
 * 改写后界面：中英结合正文。
 * 顶部工具条（显示模式/难度/页码/难易统计/翻页按键）已上移到 ReaderArticleScreen 顶部覆盖层，
 * 覆盖在正文之上不挤压内容；这里只负责正文内容。
 * 滚动模式：LazyColumn 渲染当前 600 字改写页段落。
 * 翻页模式：与原文共用同一套「整屏测量分页」和同一个 pager 状态 → 一页一翻、页内无需滚动，
 *           且原文/改写后始终停在同一位次（切来切去不跑页）。
 * 单击英文单词 → 弹「原意 + 释义」；长按仍是系统选字 CAB（注释/记忆库）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RewrittenPageView(
    paragraphs: List<String>,
    /** 滚动模式：当前 600 字改写页区间 */
    page: IntRange,
    /** 翻页模式：整屏测量分页区间（与原文完全一致） */
    pages: List<IntRange>,
    /** 翻页模式：与原文共用的 pager 状态（位次同步） */
    pagerState: PagerState,
    rewrittenMap: Map<Int, String>,
    mode: String,
    rewriting: Boolean,
    /** 当前改写页的改写明细（带段落索引，供单击英文词命中） */
    words: List<ZhEnRewriter.Replacement>,
    pagerMode: Boolean,
    pagerMeasuring: Boolean,
    /** 点击改写正文中间区域（单击）→ 切换上下功能栏显隐 */
    onMiddleTap: () -> Unit,
    /** 翻页式：当前整屏页区间变化 → 加载该页改写 */
    onPageSelected: (IntRange) -> Unit,
    /** 翻页式：预取下一页改写（读当前页时后台先改好下一页，翻页无需等待改写刷新） */
    onPrefetchPage: (IntRange) -> Unit,
    /** 单击英文单词 → (英文词, 原中文词, 类别, 屏幕锚点) */
    onWordLookup: (word: String, zh: String, category: String, anchor: IntOffset) -> Unit,
    /** 正文字体缩放系数（1.0 = 16sp 原始字号） */
    fontScale: Float,
    /** 改写后选字同步用（复刻原文 CAB：注释/记忆库） */
    readerViewModel: ReaderViewModel
) {
    val annotate = mode == "annotate"
    // 按段落索引分组的改写明细：每段点词只需自己那一小份，命中判定 O(段内替换数)
    val wordsByParagraph = remember(words) { words.groupBy { it.paragraphIndex } }

    // 翻页式：单击左 1/3 = 上一页，右 1/3 = 下一页，中间 = 切换上下功能栏（滚动式保持点击切栏）
    val tapScope = rememberCoroutineScope()
    val tapDensity = LocalDensity.current
    val screenWidthPx = with(tapDensity) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val onZoneTap: ((Float) -> Unit)? = if (pagerMode) {
        { rawX ->
            val last = pages.lastIndex
            when {
                rawX < screenWidthPx / 3f -> tapScope.launch {
                    runCatching { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceIn(0, maxOf(0, last))) }
                }
                rawX > screenWidthPx * 2f / 3f -> tapScope.launch {
                    runCatching { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceIn(0, maxOf(0, last))) }
                }
                else -> onMiddleTap()
            }
        }
    } else null

    if (pagerMode) {
        // ---- 翻页式：一屏一页，左右滑动即翻页（与原文共用分页与 pager 位置）----
        Box(Modifier.fillMaxSize()) {
            if (pages.isEmpty() || pagerMeasuring) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "正在分页…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 当前页变化（滑动 / 分页重算）→ 让 VM 加载该页改写（VM 内取消过期加载）
                val currentRange = pages.getOrNull(pagerState.currentPage)
                LaunchedEffect(currentRange) {
                    if (currentRange != null) {
                        onPageSelected(currentRange)
                        // 预取下一页改写：翻到下一页时改写已就绪，无需等待
                        pages.getOrNull(pagerState.currentPage + 1)?.let(onPrefetchPage)
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIdx ->
                    val range = pages.getOrNull(pageIdx) ?: return@HorizontalPager
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (pageIdx == 0) {
                            Text(
                                text = if (annotate) "改写后（对照式 · 中文(英文)）" else "改写后（替换式 · 中文词→英文）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        range.forEach { idx ->
                            val text = rewrittenMap[idx] ?: paragraphs.getOrNull(idx) ?: return@forEach
                            if (text.isBlank()) {
                                Spacer(Modifier.size(10.dp))
                            } else {
                                RewriteParagraphItem(
                                    idx = idx,
                                    text = text,
                                    words = wordsByParagraph[idx].orEmpty(),
                                    annotate = annotate,
                                    onMiddleTap = onMiddleTap,
                                    onPlainTapAt = onZoneTap,
                                    onWordLookup = onWordLookup,
                                    fontScale = fontScale,
                                    readerViewModel = readerViewModel
                                )
                            }
                        }
                    }
                }
            }
            // 改写加载中：当前页顶部细进度条（不重建 pager，保持滑动流畅）
            if (rewriting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter)
                )
            }
        }
    } else if (page.isEmpty() || rewriting) {
        // ---- 滚动模式：改写中 / 无数据 ----
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(12.dp))
                Text(
                    if (rewriting) "正在改写本页…" else "暂无改写内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        // ---- 滚动模式：LazyColumn 渲染当前 600 字改写页段落（保留原有滚动阅读体验）----
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 24.dp)
        ) {
            item {
                Text(
                    text = if (annotate) "改写后（对照式 · 中文(英文)）" else "改写后（替换式 · 中文词→英文）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(page.toList(), key = { it }) { idx ->
                val text = rewrittenMap[idx] ?: paragraphs.getOrNull(idx) ?: return@items
                // 改写正文用原生 TextView 渲染，支持长按选字（复刻原文：复制/全选 + 「注释」「记忆库」）
                RewriteParagraphItem(
                    idx = idx,
                    text = text,
                    words = wordsByParagraph[idx].orEmpty(),
                    annotate = annotate,
                    onMiddleTap = onMiddleTap,
                    onWordLookup = onWordLookup,
                    fontScale = fontScale,
                    readerViewModel = readerViewModel
                )
            }
        }
    }
}

/** 改写正文的单段：原生 TextView（长按选字 CAB + 单击英文词弹原意） */
@Composable
private fun RewriteParagraphItem(
    idx: Int,
    text: String,
    words: List<ZhEnRewriter.Replacement>,
    annotate: Boolean,
    onMiddleTap: () -> Unit,
    onPlainTapAt: ((Float) -> Unit)? = null,
    onWordLookup: (word: String, zh: String, category: String, anchor: IntOffset) -> Unit,
    fontScale: Float,
    readerViewModel: ReaderViewModel
) {
    RewriteParagraphText(
        text = text,
        paragraphIndex = idx,
        words = words,
        annotate = annotate,
        fontScale = fontScale,
        onSelectionChanged = { s, e ->
            runCatching { readerViewModel.onParagraphSelectionChanged(idx, text, s, e) }
                .onFailure { t -> Log.w("RewriteParagraphText", "selection cb #$idx($s..$e) dropped", t) }
        },
        onRequestAnnotateFromActionMode = {
            runCatching { readerViewModel.openAnnotationEditorFromSelection() }
                .onFailure { t -> Log.w("RewriteParagraphText", "annotate cb #$idx dropped", t) }
        },
        onRequestMemoryFromActionMode = {
            runCatching { readerViewModel.openMemoryConfirmFromSelection() }
                .onFailure { t -> Log.w("RewriteParagraphText", "memory cb #$idx dropped", t) }
        },
        onWordLookup = onWordLookup,
        modifier = Modifier.padding(vertical = 4.dp),
        onPlainTap = onMiddleTap,
        onPlainTapAt = onPlainTapAt
    )
    Spacer(Modifier.size(4.dp))
}

/**
 * 改写后工具条（覆盖在正文之上，不透明背景防文字重影）：
 * 显示模式（替换式/对照式）+ 难度微调入口 + 页码 + 难易统计按钮 + 翻页按键。
 * 翻页式：页码与翻页按键隐藏（由左右滑动 + 底部右下角页数进度接管），统计按钮保留并显示当前页数据。
 */
@Composable
private fun RewrittenToolbar(
    mode: String,
    onModeChange: (String) -> Unit,
    /** 当前难度微调三值（用户自定义生词密度） */
    tuning: ZhEnRewriter.Density.Tuning,
    onDensityClick: () -> Unit,
    pageNo: Int,
    pageTotal: Int,
    pagerMode: Boolean,
    stats: ReaderViewModel.RewriteStats?,
    onStatClick: (StatsSheetType) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "显示：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = mode == "replace",
                    onClick = { onModeChange("replace") },
                    label = { Text("替换式") },
                    modifier = Modifier.padding(end = 6.dp)
                )
                FilterChip(
                    selected = mode == "annotate",
                    onClick = { onModeChange("annotate") },
                    label = { Text("对照式") }
                )
                Spacer(Modifier.weight(1f))
                // 难度入口：用户按自身阅读感受调节生词密度（读着顺 → 逐步加难）
                AssistChip(
                    onClick = onDensityClick,
                    label = {
                        Text(
                            tuning.compactLabel,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.padding(end = 6.dp)
                )
                // 翻页式：页码由右下角页数进度显示，这里隐藏避免重复
                if (!pagerMode) {
                    Text(
                        "$pageNo / $pageTotal 页",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // ---- 难易统计：四个可点击按钮，点开查看对应类别具体单词（当前页数据）----
            val st = stats
            if (st != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    RewriteStatButton(
                        label = "本页替换 ${st.replaced} 词",
                        dotColor = MaterialTheme.colorScheme.primary,
                        enabled = st.replaced > 0,
                        onClick = { onStatClick(StatsSheetType.ALL) }
                    )
                    RewriteStatButton(
                        label = "会的 ${st.known}",
                        dotColor = Color(0xFF4CAF50),
                        enabled = st.known > 0,
                        onClick = { onStatClick(StatsSheetType.KNOWN) }
                    )
                    RewriteStatButton(
                        label = "不熟的 ${st.semi}",
                        dotColor = Color(0xFFF9A825),
                        enabled = st.semi > 0,
                        onClick = { onStatClick(StatsSheetType.SEMI) }
                    )
                    RewriteStatButton(
                        label = "不会的 ${st.unknown}",
                        dotColor = Color(0xFFE57373),
                        enabled = st.unknown > 0,
                        onClick = { onStatClick(StatsSheetType.UNKNOWN) }
                    )
                }
            } else {
                Spacer(Modifier.height(30.dp))
            }
            // ---- 翻页按键：滚动模式保留；翻页式由左右滑动接管，隐藏 ----
            if (!pagerMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onPrevPage, enabled = pageNo > 1) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "上一页")
                    }
                    IconButton(onClick = onNextPage, enabled = pageNo < pageTotal) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "下一页")
                    }
                }
            }
        }
    }
}

/** 难易统计行里的小按钮：彩色圆点 + 标签（点击查看该类别的具体单词） */
@Composable
private fun RewriteStatButton(
    label: String,
    dotColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(dotColor.copy(alpha = if (enabled) 1f else 0.35f))
        )
        Spacer(Modifier.size(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 改写后正文段落：用原生 TextView 渲染（setTextIsSelectable=true），
 * 与原文阅读页一致的阅读排版，并支持长按选字调出系统选字功能栏。
 * ⚠️ 复刻原文界面 CAB：在系统选字功能栏中追加「📝 注释」「🧠 记忆库」两项，
 *    选中文字 → 顶部系统功能栏点「注释/记忆库」→ 与原文流程一致打开编辑窗/记忆库确认。
 * 单击正文（非长按/非拖动）→ onPlainTap，用于切换上下功能栏显隐。
 */
@Composable
private fun RewriteParagraphText(
    text: String,
    /** 段落索引 = 原文段索引（rewritePage 区间即原文段区间，保证注释/记忆库落在原文同段） */
    paragraphIndex: Int,
    /** 这段文本里的改写明细（含英文词在产物中的偏移）→ 支持单击英文单词弹「原意」 */
    words: List<ZhEnRewriter.Replacement>,
    /** 当前显示模式是否对照式（决定用哪一套偏移） */
    annotate: Boolean,
    /** 选字同步（与原文 ParagraphItem 一致：先写 VM selection，再打开注释/记忆库） */
    onSelectionChanged: (startInParagraph: Int, endInParagraph: Int) -> Unit,
    onRequestAnnotateFromActionMode: () -> Unit,
    onRequestMemoryFromActionMode: () -> Unit,
    /** 单击英文单词 → (英文词, 原中文词, 类别, 屏幕锚点) */
    onWordLookup: (word: String, zh: String, category: String, anchor: IntOffset) -> Unit,
    /** 正文字体缩放系数（1.0 = 16sp 原始字号） */
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
    onPlainTap: () -> Unit = {},
    /** 翻页式：单击正文按屏幕坐标分区回调（左=上一页/右=下一页/中=切功能栏）；非 null 时优先于 onPlainTap */
    onPlainTapAt: ((Float) -> Unit)? = null
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    // ActionMode 自定义菜单常量 id（与原文 ParagraphItem 完全一致）
    val MENU_ANNOTATE = 1001
    val MENU_MEMORY = 1002
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            PlainTapDetectTextView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
                setLineSpacing(10f * fontScale * ctx.resources.displayMetrics.scaledDensity, 1f)
                runCatching { typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
                // 关键：可选字 → 长按调出系统选字功能栏
                setTextIsSelectable(true)
                // ⚠️ 顺序与原文 ParagraphItem 一致：先 setTextIsSelectable(true)，再设自定义 callback
                val tv = this
                customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        if (menu == null) return true
                        runCatching {
                            menu.add(Menu.NONE, MENU_ANNOTATE, Menu.NONE, "📝 注释")
                                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            menu.add(Menu.NONE, MENU_MEMORY, Menu.NONE, "🧠 记忆库")
                                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        return true // true 表示保留系统默认项（复制/全选等）+ 叠加我们的自定义
                    }
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        return when (item?.itemId) {
                            MENU_ANNOTATE -> {
                                val s = tv.selectionStart.coerceAtLeast(0)
                                val eRaw = tv.selectionEnd.coerceAtLeast(0)
                                val e = if (eRaw >= s) eRaw else s
                                if (s < e) {
                                    onSelectionChanged(s, e)
                                    onRequestAnnotateFromActionMode()
                                }
                                runCatching { mode?.finish() }
                                true
                            }
                            MENU_MEMORY -> {
                                val s = tv.selectionStart.coerceAtLeast(0)
                                val eRaw = tv.selectionEnd.coerceAtLeast(0)
                                val e = if (eRaw >= s) eRaw else s
                                if (s < e) {
                                    onSelectionChanged(s, e)
                                    onRequestMemoryFromActionMode()
                                }
                                runCatching { mode?.finish() }
                                true
                            }
                            else -> false
                        }
                    }
                    override fun onDestroyActionMode(mode: ActionMode?) {
                        Log.d("FloatBarSync", "rewrite#$paragraphIndex onDestroyActionMode → clear selection")
                        onSelectionChanged(0, 0)
                    }
                }
                // 沉浸式功能栏：单击正文切换显隐
                this.onPlainTap = onPlainTap
                // 翻页式：单击按屏幕分区（左/右翻页，中间切栏）
                this.onPlainTapAt = onPlainTapAt
                // 单击英文单词 → 弹「原意」卡片（命中即消费本次点击，不再切换功能栏）
                this.onCharTap = charTap@ { offset, tapX, tapY ->
                    val hit = hitReplacement(words, offset, annotate) ?: return@charTap false
                    onWordLookup(hit.en, hit.zh, hit.category, tapAnchor(this, tapX, tapY))
                    true
                }
            }
        },
        update = { tv ->
            if (tv.text.toString() != text) tv.text = text
            runCatching { tv.onPlainTap = onPlainTap }
            runCatching { tv.onPlainTapAt = onPlainTapAt }
            // 字体缩放变化 → 同步字号与行距（与分页测量使用的字号保持一致）
            runCatching {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
                tv.setLineSpacing(10f * fontScale * tv.resources.displayMetrics.scaledDensity, 1f)
            }
            // words/模式变化时同步命中数据（避免旧断言偏移错配）
            runCatching {
                val curWords = words
                val curAnnotate = annotate
                tv.onCharTap = charTap@ { offset, tapX, tapY ->
                    val hit = hitReplacement(curWords, offset, curAnnotate) ?: return@charTap false
                    onWordLookup(hit.en, hit.zh, hit.category, tapAnchor(tv, tapX, tapY))
                    true
                }
            }
        }
    )
}

/**
 * 计算被点单词的窗口锚点：直接用「手指点击点」在本窗口中的绝对坐标
 * （TextView 在窗口中的左上角 + 段内点击坐标），卡片因此紧贴被点词、不受行高/段落偏移影响。
 */
private fun tapAnchor(tv: android.widget.TextView, tapX: Float, tapY: Float): IntOffset = runCatching {
    val loc = IntArray(2)
    tv.getLocationInWindow(loc)
    IntOffset((loc[0] + tapX).toInt(), (loc[1] + tapY).toInt())
}.getOrDefault(IntOffset.Zero)

/**
 * 注释卡片定位（窗口绝对坐标）。
 * 说明：Compose 的 Popup 默认按「父容器左上角 + offset」定位，而锚点用的是窗口坐标；
 * 父容器带 insets/padding 时会整体错位（表现为卡片离文字好几行行高）。
 * 这里改用自己的 PopupPositionProvider：其返回值即「弹窗左上角的窗口坐标」，
 * 直接落位到被点词下方，彻底消除父容器偏移带来的间距。
 */
private class WordCardPositionProvider(
    private val anchorX: Int,
    private val anchorY: Int,
    private val gapPx: Int,
    private val marginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val x = (anchorX - w / 2).coerceIn(
            marginPx,
            (windowSize.width - w - marginPx).coerceAtLeast(marginPx)
        )
        val below = anchorY + gapPx
        val y = if (below + h > windowSize.height - marginPx) {
            (anchorY - h - gapPx).coerceAtLeast(marginPx)
        } else below
        return IntOffset(x, y)
    }
}

/**
 * 单击位置是否落在某个英文改写词上：优先精确命中改写时记录的单词区间，
 * 其次容忍偏 1 个字符（手指落在词首/词尾外侧一点点也算命中，避免点不准弹不出来）。
 * @param annotate true = 用对照式偏移（括号内英文），false = 用替换式偏移
 */
private fun hitReplacement(
    words: List<ZhEnRewriter.Replacement>,
    offset: Int,
    annotate: Boolean
): ZhEnRewriter.Replacement? {
    fun hit(r: ZhEnRewriter.Replacement, pad: Int): Boolean {
        val s = if (annotate) r.startInAnnotate else r.startInReplace
        val e = if (annotate) r.endInAnnotate else r.endInReplace
        return e > s && offset >= s - pad && offset <= e + pad
    }
    words.firstOrNull { hit(it, 0) }?.let { return it }
    return words.firstOrNull { hit(it, 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomBar(
    progressPercent: Int,
    onReadAloud: () -> Unit,
    onStop: () -> Unit,
    rewriteView: Boolean,
    onToggleRewrite: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressPercent.coerceIn(0, 100) / 100f)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "阅读进度 $progressPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    // Phase 3：中英改写（原文 | 改写后）
                    IconButton(onClick = onToggleRewrite) {
                        Icon(
                            Icons.Filled.Translate,
                            contentDescription = "中英改写",
                            tint = if (rewriteView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onReadAloud) {
                        Icon(Icons.Filled.VolumeUp, contentDescription = "朗读", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

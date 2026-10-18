package com.zhiji.app.ui.feature.reader

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhiji.app.ZhiJiApp
import com.zhiji.app.core.algorithm.ZhEnRewriter
import com.zhiji.app.data.backup.BackupManager
import com.zhiji.app.data.database.entity.BookAnnotationEntity
import com.zhiji.app.data.database.entity.BookEntity
import com.zhiji.app.data.database.entity.BookFolderEntity
import com.zhiji.app.data.datastore.SettingsDataStore
import com.zhiji.app.data.repository.EcdictRepository
import com.zhiji.app.data.repository.ReaderRepository
import com.zhiji.app.data.repository.ZhEnRewriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(app: ZhiJiApp) : AndroidViewModel(app) {

    private val repository: ReaderRepository = app.readerRepository
    /** Phase 3 中英混排改写仓库（600字一页 + 记忆库 8:1:1 三分类难易联动） */
    private val zhEnRewriteRepository: ZhEnRewriteRepository = app.zhEnRewriteRepository

    /**
     * v39 所属板块：smart_reader / multi_format
     * 书架流按 board 过滤，实现两个板块书架数据完全独立
     */
    private val _board = MutableStateFlow("smart_reader")

    /** 设置板块（ReaderHost 进入时幂等调用；同一 key 的 ViewModel 只创建一次） */
    fun setBoard(board: String) {
        if (_board.value != board) {
            _board.value = board
            _currentFolderId.value = null
        }
    }

    val shelfBooks: StateFlow<List<BookEntity>> = _board
        .flatMapLatest { board -> repository.books(board) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ============================================================
    // 书籍分类（文件夹树）：当前文件夹 + 文件夹列表 + 夹内书籍
    // ============================================================

    /** 当前所在文件夹 id；null = 根目录 */
    private val _currentFolderId = MutableStateFlow<String?>(null)
    val currentFolderId: StateFlow<String?> = _currentFolderId.asStateFlow()

    /** 当前板块全部分类文件夹（构建分类树/面包屑） */
    val folders: StateFlow<List<BookFolderEntity>> = _board
        .flatMapLatest { board -> repository.folders(board) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前文件夹下的书籍（folderId = null 表示根目录） */
    val folderBooks: StateFlow<List<BookEntity>> = combine(_board, _currentFolderId) { b, f -> b to f }
        .flatMapLatest { (b, f) -> repository.books(b, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 进入某文件夹（null = 回到根目录） */
    fun enterFolder(folderId: String?) {
        _currentFolderId.value = folderId
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            runCatching { repository.createFolder(name, _currentFolderId.value, _board.value) }
            _toastMessage.value = "文件夹已创建"
        }
    }

    fun renameFolder(folderId: String, name: String) {
        viewModelScope.launch {
            runCatching { repository.renameFolder(folderId, name) }
            _toastMessage.value = "已重命名"
        }
    }

    /** 删除文件夹：夹内内容上移到父级；若删除的是当前文件夹则回退到父级 */
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val parent = runCatching { repository.deleteFolder(folderId) }.getOrNull()
            if (_currentFolderId.value == folderId) _currentFolderId.value = parent
            _toastMessage.value = "文件夹已删除，内容已上移"
        }
    }

    /** 重命名书籍 */
    fun renameBook(bookId: String, title: String) {
        viewModelScope.launch {
            runCatching { repository.renameBook(bookId, title) }
            _toastMessage.value = "已重命名"
        }
    }

    /** 移动书籍到指定文件夹 */
    fun moveBookTo(bookId: String, folderId: String?) {
        viewModelScope.launch {
            runCatching { repository.moveBook(bookId, folderId) }
            _toastMessage.value = "已移动"
        }
    }

    // ---- 书籍复制/剪切/粘贴（分类整理用）----
    data class ClipboardItem(val mode: String, val bookId: String, val title: String)

    private val _clipboard = MutableStateFlow<ClipboardItem?>(null)
    val clipboard: StateFlow<ClipboardItem?> = _clipboard.asStateFlow()

    fun copyBookToClipboard(book: BookEntity) {
        _clipboard.value = ClipboardItem("copy", book.id, book.title)
        _toastMessage.value = "已复制《${book.title}》，可到目标文件夹粘贴"
    }

    fun cutBookToClipboard(book: BookEntity) {
        _clipboard.value = ClipboardItem("cut", book.id, book.title)
        _toastMessage.value = "已剪切《${book.title}》，可到目标文件夹粘贴"
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    /** 粘贴到当前文件夹：复制 = 新建副本；剪切 = 移动 */
    fun pasteClipboard() {
        val item = _clipboard.value ?: return
        val target = _currentFolderId.value
        viewModelScope.launch {
            when (item.mode) {
                "copy" -> runCatching { repository.copyBook(item.bookId, target) }
                "cut" -> runCatching { repository.moveBook(item.bookId, target) }
            }
            if (item.mode == "cut") _clipboard.value = null
            _toastMessage.value = "已粘贴《${item.title}》"
        }
    }

    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBook: StateFlow<BookEntity?> = _currentBookId
        .flatMapLatest { id -> repository.getBookFlow(id ?: "") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 本书全部注释（Flow，DB 一有插入就立即回推到 UI 重绘下划线） */
    val annotations: StateFlow<List<BookAnnotationEntity>> = _currentBookId
        .flatMapLatest { id -> repository.getAnnotationsByBookFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 正文分段：在 IO 线程一次性切好并缓存（大文件关键优化）。
     * 旧实现在阅读页首帧组合里做整篇 replace+lines+map，几 MB 文本会直接卡住首帧 → 打开慢。
     */
    private val _paragraphs = MutableStateFlow<List<String>>(emptyList())
    val paragraphs: StateFlow<List<String>> = _paragraphs.asStateFlow()

    /** 正文是否正在加载（读文件 + 分段 + 统计字符全部在 IO 线程，UI 显示加载态） */
    private val _contentLoading = MutableStateFlow(false)
    val contentLoading: StateFlow<Boolean> = _contentLoading.asStateFlow()

    /** 全文段落是否已就绪（快速首屏阶段为 false：此时只加载了开头若干字符） */
    private val _paragraphsComplete = MutableStateFlow(false)
    val paragraphsComplete: StateFlow<Boolean> = _paragraphsComplete.asStateFlow()

    /** 最近一次「已落库」的阅读进度字符偏移：退出书籍时兜底再存一次（防抖窗口内退出不丢进度） */
    private var lastSavedOffset: Int? = null

    // ============================================================
    // 新交互：选字 → 浮动工具栏 → 「注释」/「记忆库」按钮
    // ============================================================

    /** 用户在某个段落里选中的区间；null = 当前无选中（浮动工具条不显示） */
    data class SelectionState(
        val paragraphIndex: Int,
        val startInParagraph: Int,
        val endInParagraph: Int,
        val selectedText: String
    )
    private val _selection = MutableStateFlow<SelectionState?>(null)
    val selection: StateFlow<SelectionState?> = _selection.asStateFlow()

    /** 浮动工具栏 → 注释编辑弹窗 */
    data class AnnotationEditorState(
        /** =null 表示本次是新建注释；有 id 则是在编辑已有注释（点下划线进入） */
        val existingAnnotationId: String?,
        val paragraphIndex: Int,
        val startInParagraph: Int,
        val endInParagraph: Int,
        val selectedText: String,
        val note: String,
        val fullscreen: Boolean = false
    )
    private val _annotationEditor = MutableStateFlow<AnnotationEditorState?>(null)
    val annotationEditor: StateFlow<AnnotationEditorState?> = _annotationEditor.asStateFlow()

    // ============================================================
    // 记忆库（原 MarkMemorySheetState 保留但改名，由浮动工具栏触发）
    // ============================================================
    data class MemoryConfirmState(
        val selectedText: String,
        val paragraphIndex: Int,
        val charStartInParagraph: Int,
        val charEndInParagraph: Int
    )
    private val _memoryConfirm = MutableStateFlow<MemoryConfirmState?>(null)
    val memoryConfirm: StateFlow<MemoryConfirmState?> = _memoryConfirm.asStateFlow()
    private val _memoryMarkMessage = MutableStateFlow<String?>(null)
    val memoryMarkMessage: StateFlow<String?> = _memoryMarkMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // ============================================================
    // 离线词典（ECDICT 精简版）：注释窗「词典释义」Tab
    // ============================================================
    data class DictLookupState(
        val word: String,
        val entry: EcdictRepository.DictEntry?,
        val loading: Boolean
    )
    private val _dictLookup = MutableStateFlow<DictLookupState?>(null)
    val dictLookup: StateFlow<DictLookupState?> = _dictLookup.asStateFlow()

    /** 查词典（幂等导入 + 查询）；相同词不重复触发 */
    fun lookupDictionary(word: String) {
        val w = word.trim()
        if (w.isEmpty()) {
            _dictLookup.value = null
            return
        }
        if (_dictLookup.value?.word == w) return
        viewModelScope.launch {
            val repo = getApplication<ZhiJiApp>().ecdictRepository
            _dictLookup.value = DictLookupState(w, null, true)
            if (!repo.isReady()) runCatching { repo.ensureImported() }
            val entry = if (repo.isReady()) repo.lookup(w) else null
            _dictLookup.value = DictLookupState(w, entry, false)
        }
    }

    /** 清空词典查询结果（注释窗关闭时） */
    fun clearDictionary() {
        _dictLookup.value = null
    }

    // ============================================================
    // Phase 3 中英混排改写（原文 → 中英结合，8:1:1 记忆库三分类联动）
    //  - 原文 | 改写后 两个阅读界面切换
    //  - 显示模式：replace 替换式 / annotate 对照式（双模式都预生成落库，切换秒开）
    //  - 一页 ≈ 600 字自动改写；书打开后后台全篇改写生成持久化缓存
    //  - 记忆库卡片变化实时监测：版本递增 → 后台刷新全篇缓存 + 当前页即时重写
    //  - 难易比例：会的全改 / 不熟的、不会的 各按每100字10%预算
    // ============================================================

    /** 本页改写统计（替换数 / 会的 / 不熟的 / 不会的） */
    data class RewriteStats(
        val replaced: Int,
        val known: Int,
        val semi: Int,
        val unknown: Int
    )

    /** 是否显示「改写后」界面（false = 原文） */
    private val _rewriteView = MutableStateFlow(false)
    val rewriteView: StateFlow<Boolean> = _rewriteView.asStateFlow()

    /** 改写显示模式：replace 替换式 / annotate 对照式（持久化：下次打开保持上次模式） */
    private val _rewriteMode = MutableStateFlow("replace")
    val rewriteMode: StateFlow<String> = _rewriteMode.asStateFlow()

    init {
        // 恢复上次的改写显示模式（阅读习惯记忆）
        viewModelScope.launch {
            runCatching { _rewriteMode.value = app.settingsDataStore.getReadingRewriteMode() }
        }
    }

    /** 正在改写中（改写界面顶部显示加载态） */
    private val _rewriting = MutableStateFlow(false)
    val rewriting: StateFlow<Boolean> = _rewriting.asStateFlow()

    /** 当前改写页覆盖的段落范围（对应 ~600 字一页） */
    private val _rewritePage = MutableStateFlow<IntRange>(IntRange.EMPTY)
    val rewritePage: StateFlow<IntRange> = _rewritePage.asStateFlow()

    /** 当前改写页：段索引 → 改写后文本（未产生替换的段不在其中，展示层回退原文） */
    private val _rewrittenMap = MutableStateFlow<Map<Int, String>>(emptyMap())
    val rewrittenMap: StateFlow<Map<Int, String>> = _rewrittenMap.asStateFlow()

    /** 本页改写统计：替换数 / 会的 / 不熟的 / 不会的 */
    private val _rewriteStats = MutableStateFlow<RewriteStats?>(null)
    val rewriteStats: StateFlow<RewriteStats?> = _rewriteStats.asStateFlow()

    /** 本页替换明细（zh/en/category），供统计按钮点开查看具体单词 */
    private val _rewriteWords = MutableStateFlow<List<ZhEnRewriter.Replacement>>(emptyList())
    val rewriteWords: StateFlow<List<ZhEnRewriter.Replacement>> = _rewriteWords.asStateFlow()

    /**
     * 本页「全量」替换明细（不去重）——单击英文词命中用。
     * 去重的 [rewriteWords] 只保留每个英文词首次出现的偏移，同页重复出现的同一英文词会点不中。
     */
    private val _rewriteAllWords = MutableStateFlow<List<ZhEnRewriter.Replacement>>(emptyList())
    val rewriteAllWords: StateFlow<List<ZhEnRewriter.Replacement>> = _rewriteAllWords.asStateFlow()

    /** 当前页码（1 起）与总页数 */
    private val _rewritePageNo = MutableStateFlow(1)
    val rewritePageNo: StateFlow<Int> = _rewritePageNo.asStateFlow()
    private val _rewritePageTotal = MutableStateFlow(1)
    val rewritePageTotal: StateFlow<Int> = _rewritePageTotal.asStateFlow()

    /** 后台全篇改写任务（防并发：已有任务在跑则不重复调度） */
    private var fullRewriteJob: Job? = null

    /** 改写翻页导航任务（翻页式滑动时竞态保护：新导航取消旧导航，保证最终页 = 最新请求页） */
    private var rewriteNavJob: Job? = null

    /** 记忆库卡片变化实时监测：版本递增 → 后台刷新全篇缓存 + 改写界面当前页即时重写 */
    private val memoryWatcher = viewModelScope.launch {
        // StateFlow 天然去重；drop(1) 跳过初始 0（打开书后的首次全篇改写由 openBook 负责）
        app.memoryRepository.versionFlow
            .drop(1)
            .collect { _ ->
                if (_currentBookId.value != null && _paragraphs.value.isNotEmpty()) {
                    scheduleFullRewrite()
                    if (_rewriteView.value && !_rewritePage.value.isEmpty()) {
                        redoCurrentRewritePage()
                    }
                }
            }
    }

    /** 书内容就绪后：后台全篇改写生成持久化缓存（改写界面切换页/切换模式秒开） */
    private fun scheduleFullRewrite() {
        val bookId = _currentBookId.value ?: return
        val paras = currentParagraphs() ?: return
        if (fullRewriteJob?.isActive == true) return
        fullRewriteJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { zhEnRewriteRepository.rewriteBook(bookId, paras) }
        }
    }

    /** 用持久化缓存组装当前页（缺/过期段落即时计算回写），统一更新全部改写状态 */
    private suspend fun loadCurrentRewritePage(bookId: String, paras: List<String>, range: IntRange) {
        val page = runCatching {
            zhEnRewriteRepository.loadPage(bookId, paras, range, _rewriteMode.value)
        }.getOrNull()
        if (page != null) {
            _rewrittenMap.value = page.paragraphs
            _rewritePage.value = range
            _rewriteStats.value = RewriteStats(page.replaced, page.known, page.semi, page.unknown)
            _rewriteWords.value = page.words
            _rewriteAllWords.value = page.allWords
            // 预改写下一页并落库：用户读当前页时后台先改好下一页，翻页无需等待改写刷新
            prefetchNextPage(bookId, paras, range)
        }
    }

    /**
     * 进入「改写后」界面：立即切换视图，并组装 UI 给出的这一段（翻页式 = 当前整屏页；
     * 滚动式 = 含当前可见段的 600 字页）。优先读持久化缓存，秒开；缺/过期段落即时计算回写。
     */
    fun enterRewriteView(range: IntRange) {
        val paras = currentParagraphs()
        if (paras.isNullOrEmpty() || range.isEmpty()) {
            _toastMessage.value = "暂无内容可改写"
            return
        }
        val bookId = _currentBookId.value ?: return
        _rewriteView.value = true
        viewModelScope.launch {
            runCatching { getApplication<ZhiJiApp>().settingsDataStore.setReadingViewMode("rewritten") }
        }
        viewModelScope.launch {
            _rewriting.value = true
            loadCurrentRewritePage(bookId, paras, range)
            if (!_rewritePage.value.isEmpty()) {
                _rewritePageNo.value = zhEnRewriteRepository.pageIndexOf(paras, range.first)
                _rewritePageTotal.value = zhEnRewriteRepository.pageCount(paras)
            }
            _rewriting.value = false
        }
    }

    /** 退出「改写后」界面，回到原文 */
    fun exitRewriteView() {
        _rewriteView.value = false
        viewModelScope.launch {
            runCatching { getApplication<ZhiJiApp>().settingsDataStore.setReadingViewMode("original") }
        }
    }

    /** 切换显示模式（替换式/对照式）：双模式已预存，直接切换缓存文本，无需重算 */
    fun setRewriteMode(mode: String) {
        if (_rewriteMode.value == mode) return
        _rewriteMode.value = mode
        // 持久化：下次打开保持上次的显示模式
        viewModelScope.launch {
            runCatching { getApplication<ZhiJiApp>().settingsDataStore.setReadingRewriteMode(mode) }
        }
        if (_rewriteView.value && !_rewritePage.value.isEmpty()) {
            redoCurrentRewritePage()
        }
    }

    /** 翻到上一页 / 下一页并组装改写页（forward=true 下一页） */
    fun rewriteAdjacentPage(forward: Boolean) {
        val paras = currentParagraphs() ?: return
        val bookId = _currentBookId.value ?: return
        val cur = _rewritePage.value
        if (cur.isEmpty()) return
        val nextStart = if (forward) cur.last + 1 else cur.first - 1
        if (nextStart !in paras.indices) {
            _toastMessage.value = if (forward) "已是最后一页" else "已是第一页"
            return
        }
        viewModelScope.launch {
            _rewriting.value = true
            val range = zhEnRewriteRepository.pageRange(paras, nextStart)
            loadCurrentRewritePage(bookId, paras, range)
            if (!_rewritePage.value.isEmpty()) {
                _rewritePageNo.value = zhEnRewriteRepository.pageIndexOf(paras, range.first)
            }
            _rewriting.value = false
        }
    }

    /** 当前页按新模式重读缓存（模式切换/记忆库变化后调用，不改页码） */
    private fun redoCurrentRewritePage() {
        val paras = currentParagraphs() ?: return
        val bookId = _currentBookId.value ?: return
        val cur = _rewritePage.value
        if (cur.isEmpty()) return
        viewModelScope.launch {
            _rewriting.value = true
            loadCurrentRewritePage(bookId, paras, cur)
            _rewriting.value = false
        }
    }

    /**
     * 翻页式阅读：按 UI 测量出的整屏页区间加载改写内容。
     * 竞态保护：新导航取消旧导航，快速连续滑动时保证最终停在最新请求的页。
     */
    fun gotoRewriteRange(range: IntRange) {
        if (range.isEmpty()) return
        val paras = currentParagraphs() ?: return
        val bookId = _currentBookId.value ?: return
        rewriteNavJob?.cancel()
        rewriteNavJob = viewModelScope.launch {
            _rewriting.value = true
            loadCurrentRewritePage(bookId, paras, range)
            _rewriting.value = false
        }
    }

    /** 滚动式阅读进入「改写后」时的 600 字页区间（含 startIdx 所在页，与翻页按键单位一致） */
    fun rewriteScrollPageRange(startIdx: Int): IntRange {
        val paras = currentParagraphs() ?: return IntRange.EMPTY
        if (paras.isEmpty()) return IntRange.EMPTY
        return zhEnRewriteRepository.pageRange(paras, startIdx.coerceIn(0, paras.size - 1))
    }

    /** 清空改写相关状态（换书 / 关书时调用） */
    private fun resetRewriteState() {
        _rewriteView.value = false
        _rewriting.value = false
        _rewritePage.value = IntRange.EMPTY
        _rewrittenMap.value = emptyMap()
        _rewriteStats.value = null
        _rewriteWords.value = emptyList()
        _rewriteAllWords.value = emptyList()
        _rewritePageNo.value = 1
        _rewritePageTotal.value = 1
        _rewriteWordLookup.value = null
    }

    // ============================================================
    // 预改写下一页（用户读当前页时后台先改好下一页并落库，翻页无需等待改写刷新）
    // ============================================================

    /** 预取整屏分页的下一页（翻页式 UI 明确告知下一页区间，命中更精准） */
    fun prefetchRewriteRange(range: IntRange) {
        val paras = currentParagraphs() ?: return
        val bookId = _currentBookId.value ?: return
        prefetchRange(bookId, paras, range)
    }

    /** 取「当前 600 字页」之后的下一个 600 字页做预改写（滚动式使用） */
    private fun prefetchNextPage(bookId: String, paras: List<String>, cur: IntRange) {
        val nextStart = cur.last + 1
        if (nextStart !in paras.indices) return
        prefetchRange(bookId, paras, zhEnRewriteRepository.pageRange(paras, nextStart))
    }

    /** 后台预改写指定区间并落库（loadPage 会把缺失/过期段落写回缓存） */
    private fun prefetchRange(bookId: String, paras: List<String>, range: IntRange) {
        if (range.isEmpty() || range.first !in paras.indices) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { zhEnRewriteRepository.loadPage(bookId, paras, range, _rewriteMode.value) }
        }
    }

    // ============================================================
    // 翻页式整屏分页结果持久化（大文件分页后保存住，再次打开免重新分页）
    // ============================================================

    /**
     * 读分页缓存（宽松命中）：只比排版（可用宽高 + 字体缩放）。
     * 快速首屏阶段只加载了开头若干段落，段落数/总字数与全文不符 → 严格指纹不匹配，
     * 用宽松命中才能在全文到齐前就拿回分页表，首屏立即可见、不显示「正在分页」。
     */
    suspend fun cachedPageRangesLoose(layoutKey: String): List<IntRange>? {
        val bookId = _currentBookId.value ?: return null
        return runCatching { zhEnRewriteRepository.loadCachedPagesLoose(bookId, layoutKey) }.getOrNull()
    }

    /**
     * 读分页缓存（严格命中）：段落数 + 总字数 + 排版指纹全一致才命中。
     * 全文就绪后用它自愈：若缓存是由「乱码正文」派生（段落数/总字数与修复后全文不符）→ 不命中 → 重测分页。
     */
    suspend fun cachedPageRanges(signature: String): List<IntRange>? {
        val bookId = _currentBookId.value ?: return null
        return runCatching { zhEnRewriteRepository.loadCachedPages(bookId, signature) }.getOrNull()
    }

    /** 保存分页结果（带排版指纹） */
    suspend fun persistPageRanges(signature: String, pages: List<IntRange>) {
        val bookId = _currentBookId.value ?: return
        runCatching { zhEnRewriteRepository.savePages(bookId, signature, pages) }
    }

    // ============================================================
    // 阅读习惯记忆：阅读方式（滚动/翻页）+ 正文字体缩放
    // ============================================================

    /** 阅读方式：scroll 滚动式 / pager 翻页式（持久化，下次打开保持） */
    val readingPageMode: StateFlow<String> = app.settingsDataStore.readingPageMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "scroll")

    fun setReadingPageMode(mode: String) {
        viewModelScope.launch {
            runCatching { getApplication<ZhiJiApp>().settingsDataStore.setReadingPageMode(mode) }
        }
    }

    /** 正文字体缩放百分比（100 = 16sp 原始字号） */
    val fontScale: StateFlow<Int> = app.settingsDataStore.readingFontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDataStore.DEFAULT_FONT_SCALE)

    fun setFontScale(percent: Int) {
        viewModelScope.launch {
            runCatching { getApplication<ZhiJiApp>().settingsDataStore.setReadingFontScale(percent) }
        }
    }

    private fun indexAtOffset(paragraphs: List<String>, offset: Int): Int {
        var acc = 0
        for (i in paragraphs.indices) {
            val len = paragraphs[i].length
            if (acc + len > offset) return i
            acc += len
        }
        return (paragraphs.size - 1).coerceAtLeast(0)
    }

    // ============================================================
    // 改写难度微调（用户自定义）：每 100 字窗口内「不熟 / 不会 / 新增词」各自可出现的最大个数
    // 三个数值参与缓存版本号：微调后当前页与后台缓存自动按新难度重写
    // ============================================================

    val rewriteTuning: StateFlow<ZhEnRewriter.Density.Tuning> = app.settingsDataStore.rewriteTuning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ZhEnRewriter.Density.DEFAULT_TUNING)

    /** 微调难度：写设置 → 版本号变化 → 当前改写页立即按新难度重算，后台缓存按页失效重写 */
    fun setRewriteTuning(tuning: ZhEnRewriter.Density.Tuning) {
        val target = ZhEnRewriter.Density.tuning(
            tuning.semiPer100, tuning.unknownPer100, tuning.injectPer100
        )
        viewModelScope.launch {
            getApplication<ZhiJiApp>().settingsDataStore.setRewriteTuning(target)
            if (_currentBookId.value == null) return@launch
            scheduleFullRewrite()
            if (_rewriteView.value && !_rewritePage.value.isEmpty()) redoCurrentRewritePage()
        }
    }

    // ============================================================
    // 改写正文单击英文单词 → 弹出「原意（被替换的中文词）+ 词典释义」
    // ============================================================

    data class RewriteWordLookup(
        /** 英文单词（改写后正文里出现的） */
        val word: String,
        /** 该英文词改写自的中文词（原意） */
        val zh: String,
        /** 记忆库类别：known / semi / unknown */
        val category: String,
        val entry: EcdictRepository.DictEntry?,
        val loading: Boolean
    )

    private val _rewriteWordLookup = MutableStateFlow<RewriteWordLookup?>(null)
    val rewriteWordLookup: StateFlow<RewriteWordLookup?> = _rewriteWordLookup.asStateFlow()

    /** 单击某英文单词：先出卡片骨架，再异步补上离线词典释义 */
    fun lookupRewriteWord(word: String, zh: String, category: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        _rewriteWordLookup.value = RewriteWordLookup(w, zh, category, null, true)
        viewModelScope.launch {
            val repo = getApplication<ZhiJiApp>().ecdictRepository
            if (!repo.isReady()) runCatching { repo.ensureImported() }
            val entry = if (repo.isReady()) runCatching { repo.lookup(w) }.getOrNull() else null
            // 用户可能已改点别的词：只在仍是同一个词时回填，避免结果错配
            if (_rewriteWordLookup.value?.word == w) {
                _rewriteWordLookup.value = RewriteWordLookup(w, zh, category, entry, false)
            }
        }
    }

    fun dismissRewriteWordLookup() {
        _rewriteWordLookup.value = null
    }

    /**
     * 注释卡片「不会 / 不熟 / 熟悉」三状态选择：把该英文词掌握状态写回记忆库并同步卡片。
     * 记忆库版本随之递增 → 改写缓存按新难度失效重写（该词的改写表现立刻贴合新状态）。
     */
    fun setRewriteWordStatus(status: String) {
        val cur = _rewriteWordLookup.value ?: return
        val word = cur.word
        viewModelScope.launch {
            val card = runCatching {
                getApplication<ZhiJiApp>().memoryRepository.upsertWordStatus(word, status)
            }.getOrNull()
            if (card == null) {
                _toastMessage.value = "状态同步失败，请重试"
                return@launch
            }
            if (_rewriteWordLookup.value?.word == word) {
                _rewriteWordLookup.value = cur.copy(category = card.status)
            }
            _toastMessage.value = when (card.status) {
                "known" -> "已标记为「熟悉」，与记忆库同步"
                "semi" -> "已标记为「不熟」，与记忆库同步"
                else -> "已标记为「不会」，与记忆库同步"
            }
        }
    }

    /** 当前文章分段（IO 线程切好的缓存，与阅读页段落切分规则完全一致） */
    private fun currentParagraphs(): List<String>? =
        _paragraphs.value.takeIf { it.isNotEmpty() }

    /**
     * ParagraphItem（BasicTextField 内部）当 selection 变化 / 失去 / 点击时，
     * 主动调用此方法把最新选中状态同步到 ViewModel。
     * 当用户选择 start == end（没选中具体字符）时自动 dismiss 浮动工具条。
     */
    fun onParagraphSelectionChanged(
        paragraphIndex: Int,
        paragraphText: String,
        startInParagraph: Int,
        endInParagraph: Int
    ) {
        val start = startInParagraph.coerceIn(0, paragraphText.length)
        val end = endInParagraph.coerceIn(start, paragraphText.length)
        if (start >= end || paragraphText.isEmpty()) {
            _selection.value = null
            return
        }
        val selected = paragraphText.substring(start, end)
        if (selected.isBlank()) {
            _selection.value = null
            return
        }
        _selection.value = SelectionState(paragraphIndex, start, end, selected)
    }

    /** 外部取消选中（点击空白、翻页、关闭选字菜单等） */
    fun dismissSelection() {
        _selection.value = null
    }

    /** 工具条「注释」按钮：如果当前选中已有同区间注释 → 直接打开编辑窗，否则新建空注释 */
    fun openAnnotationEditorFromSelection() {
        val s = _selection.value ?: return
        val bookId = _currentBookId.value
        if (bookId.isNullOrBlank()) {
            _toastMessage.value = "请先打开一本书"
            return
        }
        viewModelScope.launch {
            // 查同区间同段是否已有注释，有则直接加载（覆盖/编辑）
            val list = annotations.value
            val hit = list.firstOrNull {
                it.paragraphIndex == s.paragraphIndex &&
                    it.startInParagraph == s.startInParagraph &&
                    it.endInParagraph == s.endInParagraph
            }
            _annotationEditor.value = AnnotationEditorState(
                existingAnnotationId = hit?.id,
                paragraphIndex = s.paragraphIndex,
                startInParagraph = s.startInParagraph,
                endInParagraph = s.endInParagraph,
                selectedText = s.selectedText,
                note = hit?.note ?: ""
            )
        }
    }

    /** 用户在某段点击文字 → UI 判断点击字符坐标命中某条注释 → 直接打开编辑窗 */
    fun openAnnotationFromTapHit(annotation: BookAnnotationEntity?) {
        Log.e("VM-OPEN", "openAnnotationFromTapHit called → annotation=${annotation?.id} / paragraphIndex=${annotation?.paragraphIndex} [${annotation?.startInParagraph},${annotation?.endInParagraph})")
        if (annotation == null) {
            Log.e("VM-OPEN", "openAnnotationFromTapHit FATAL: annotation == null → abort")
            return
        }
        // v13 移除入口 Toast（之前排队 10+ 条 Toast 造成「心理延迟感」），保留 Log
        _annotationEditor.value = AnnotationEditorState(
            existingAnnotationId = annotation.id,
            paragraphIndex = annotation.paragraphIndex,
            startInParagraph = annotation.startInParagraph,
            endInParagraph = annotation.endInParagraph,
            selectedText = annotation.selectedText,
            note = annotation.note
        )
        val after = _annotationEditor.value
        Log.e("VM-OPEN", "after write → after = $after / after?.existingId = ${after?.existingAnnotationId}")
        if (after == null) {
            Log.e("VM-OPEN", "FATAL: _annotationEditor 写入后仍是 null！(state可能被其他协程立刻覆盖)")
        }
    }

    fun updateAnnotationDraft(newNote: String) {
        val cur = _annotationEditor.value ?: return
        _annotationEditor.value = cur.copy(note = newNote)
    }

    fun toggleAnnotationFullscreen() {
        val cur = _annotationEditor.value ?: return
        _annotationEditor.value = cur.copy(fullscreen = !cur.fullscreen)
    }

    fun dismissAnnotationEditor() {
        _annotationEditor.value = null
    }

    /** 注释窗点「保存」→ 写入 book_annotations（upsert），成功后工具条和弹窗一起关掉 */
    fun saveAnnotation() {
        val cur = _annotationEditor.value ?: return
        val bookId = _currentBookId.value
        if (bookId.isNullOrBlank()) {
            _toastMessage.value = "请先打开一本书"
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                repository.upsertAnnotation(
                    existingId = cur.existingAnnotationId,
                    bookId = bookId,
                    paragraphIndex = cur.paragraphIndex,
                    startInParagraph = cur.startInParagraph,
                    endInParagraph = cur.endInParagraph,
                    selectedText = cur.selectedText,
                    note = cur.note
                )
            }
            if (result.isSuccess) {
                _toastMessage.value = if (cur.existingAnnotationId == null) "注释已添加" else "注释已更新"
                dismissAnnotationEditor()
                dismissSelection()
            } else {
                _toastMessage.value = "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }

    /** 注释编辑窗点「删除」→ 仅当已有 id 时可删 */
    fun deleteCurrentAnnotation() {
        val cur = _annotationEditor.value ?: return
        val id = cur.existingAnnotationId?.ifBlank { null } ?: run {
            // 未保存的草稿无需删，直接关窗
            dismissAnnotationEditor()
            return
        }
        viewModelScope.launch {
            runCatching { repository.deleteAnnotation(id) }
            _toastMessage.value = "注释已删除"
            dismissAnnotationEditor()
            dismissSelection()
        }
    }

    /** 某段点击某字符（未选中时的单点）→ 查注释命中，有则弹；无则什么都不做 */
    fun handleParagraphTap(paragraphIndex: Int, charPosInParagraph: Int) {
        val bookId = _currentBookId.value ?: run {
            Log.e("VM-SQL", "handleParagraphTap 未打开书籍 → skip")
            return
        }
        viewModelScope.launch {
            Log.e("VM-SQL", "handleParagraphTap p#$paragraphIndex @$charPosInParagraph start → bookId=$bookId")
            val hit = withContext(Dispatchers.IO) {
                runCatching {
                    repository.findAnnotationAt(bookId, paragraphIndex, charPosInParagraph)
                }.onFailure { t ->
                    Log.e("VM-SQL", "findAnnotationAt CRASHED", t)
                    _toastMessage.value = "❌SQL查询崩溃: ${t.message}"
                }.getOrNull()
            }
            if (hit != null) {
                // v13：移除命中SQL Toast（路径B作为兜底，真到这一步不要Toast干扰），保留Log
                Log.e("VM-SQL", "hit != null (id=${hit.id})，准备调用 openAnnotationFromTapHit")
                openAnnotationFromTapHit(hit)
            } else {
                val samePara = annotations.value
                    .filter { it.paragraphIndex == paragraphIndex }
                    .joinToString(";") { "[${it.startInParagraph},${it.endInParagraph})${it.selectedText.take(8)}" }
                Log.e("VM-SQL", "hit == null @$charPosInParagraph p#$paragraphIndex → samePara = $samePara")
            }
        }
    }

    // ============================================================
    // 记忆库建卡（由工具条「记忆库」按钮触发）
    // ============================================================
    fun openMemoryConfirmFromSelection() {
        val s = _selection.value ?: return
        val clean = s.selectedText.trim()
        if (clean.isEmpty()) return
        _memoryConfirm.value = MemoryConfirmState(
            selectedText = clean,
            paragraphIndex = s.paragraphIndex,
            charStartInParagraph = s.startInParagraph,
            charEndInParagraph = s.endInParagraph
        )
    }

    fun dismissMemoryConfirm() { _memoryConfirm.value = null }
    fun consumeMemoryMarkMessage() { _memoryMarkMessage.value = null }
    fun consumeToast() { _toastMessage.value = null }

    private fun mark(status: String) {
        val s = _memoryConfirm.value ?: return
        val book = currentBook.value ?: run {
            _memoryMarkMessage.value = "加入记忆库失败：当前未打开书籍"
            return
        }
        val bookId = book.id
        val bookTitle = book.title
        val text = s.selectedText
        val pIdx = s.paragraphIndex
        val cs = s.charStartInParagraph
        val ce = s.charEndInParagraph
        viewModelScope.launch {
            val app = getApplication<ZhiJiApp>()
            val existingCard = runCatching {
                app.memoryRepository.findCardByFront(text.trim())
            }.getOrNull()
            if (existingCard != null) {
                // 卡片已存在，给用户提示
                _memoryMarkMessage.value = buildString {
                    append("📋 卡片已存在记忆库中")
                    val front = if (text.length <= 18) text else text.take(18) + "…"
                    append("：").append(front)
                }
                dismissMemoryConfirm()
                dismissSelection()
                return@launch
            }
            val card = app.memoryRepository.createCardFromReader(
                bookId = bookId,
                bookTitle = bookTitle,
                paragraphIndex = pIdx,
                charOffsetStart = cs,
                charOffsetEnd = ce,
                selectedText = text,
                status = status
            )
            if (card != null) {
                _memoryMarkMessage.value = buildString {
                    append(
                        when (status) {
                            "known" -> "✅ 已加入「会的」"
                            "semi" -> "⚠️ 已加入「不熟」"
                            else -> "❓ 已加入「不会的」生词本"
                        }
                    )
                    val front = if (text.length <= 18) text else text.take(18) + "…"
                    append("：").append(front)
                }
                dismissMemoryConfirm()
                dismissSelection()
            } else {
                _memoryMarkMessage.value = "加入记忆库失败，请稍后重试"
            }
        }
    }

    /** 选中文字标记为「会的」→ 进入正常 FSRS 复习队列；9:1 引擎作为 90% 已掌握背景素材 */
    fun markAsKnown() = mark("known")

    /** 选中文字标记为「不会的」→ 进生词本；9:1 引擎作为 10% 难度素材优先在上下文中出现 */
    fun markAsUnknown() = mark("unknown")

    /** 选中文字标记为「不熟」→ 会但不完全会的中间态；按权重计入掌握度并进入复习队列 */
    fun markAsSemi() = mark("semi")

    fun openBook(bookId: String) {
        if (_currentBookId.value == bookId) return
        _currentBookId.value = bookId
        _paragraphs.value = emptyList()
        _paragraphsComplete.value = false
        lastSavedOffset = null
        resetRewriteState()
        viewModelScope.launch {
            _contentLoading.value = true
            // ① 快速首屏：先只读「开头若干字符」→ 立即渲染第一屏（大文件也不再等待全文切段）
            var readOffset = 0
            var book = runCatching { repository.getBook(bookId) }.getOrNull()
                ?: run { _contentLoading.value = false; return@launch }
            // 乱码自愈：旧版本按错编码导入的正文（含替换符 U+FFFD → 界面一堆「？」）→
            // 按探测编码从源文件重读并回写；同时清掉由乱码正文派生出的分页/改写缓存
            val repaired = runCatching { repository.repairGarbledContentIfNeeded(book) }.getOrNull()
            if (repaired != null) {
                book = repaired
                runCatching { zhEnRewriteRepository.deleteBookRewrites(bookId) }
            }
            readOffset = book.readOffset
            // 前缀必须覆盖「上次阅读位置 + 一屏」：少一点就还原不到那一页（翻页/滚动都算不对偏移）。
            // 因此这里不设上限——读过的位置越靠后，前缀就越长，但这是正确还原位置的必要代价；
            // 前缀与全文前缀完全一致 → 段落索引/字符偏移不漂移，进度与还原位置都安全。
            val prefixChars = (readOffset + 12_000).coerceAtLeast(3_000)
            val prefix = runCatching { repository.readBookParagraphsPrefix(book, prefixChars) }
                .getOrDefault(emptyList())
            if (prefix.isNotEmpty()) {
                _paragraphs.value = prefix
                _contentLoading.value = false
                // 前缀已覆盖全书（小文件/读到末尾）→ 无需再等全文，直接视为全文就绪
                if (prefix.sumOf { it.length } >= book.totalChars && book.totalChars > 0) {
                    _paragraphsComplete.value = true
                }
            }
            // ② 后台读全文并替换（前缀部分一致，仅追加后续段落）
            val paras = runCatching { repository.readBookParagraphs(book) }.getOrDefault(emptyList())
            _paragraphs.value = paras
            _paragraphsComplete.value = true
            _contentLoading.value = false
            // 书内容就绪后：后台全篇改写生成持久化缓存（改写界面切换页/模式秒开）
            scheduleFullRewrite()
            // 阅读习惯记忆：上次停留在「改写后」界面则直接恢复（阅读位置由进度偏移还原）
            if (paras.isNotEmpty()) {
                val savedView = runCatching {
                    getApplication<ZhiJiApp>().settingsDataStore.getReadingViewMode()
                }.getOrDefault("original")
                if (savedView == "rewritten") {
                    val startIdx = indexAtOffset(paras, readOffset)
                    enterRewriteView(zhEnRewriteRepository.pageRange(paras, startIdx))
                }
            }
        }
    }

    /** 删除已导入的电子书：连带清理注释、改写缓存与本地副本文件 */
    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            val ok = runCatching {
                zhEnRewriteRepository.deleteBookRewrites(bookId)
                repository.deleteBook(bookId)
            }.isSuccess
            if (_currentBookId.value == bookId) closeBook()
            _toastMessage.value = if (ok) "已删除" else "删除失败，请稍后重试"
        }
    }

    /**
     * 实时保存阅读进度：每次页面变更立即写库，保证下次打开就是最新阅读位置。
     * 口径 = 「当前页首段的段落起始字符偏移」（段落粒度，不会漂移）。
     */
    fun saveProgress(offset: Int) {
        val id = _currentBookId.value ?: return
        val safe = offset.coerceAtLeast(0)
        lastSavedOffset = safe
        viewModelScope.launch { runCatching { repository.saveProgress(id, safe) } }
    }

    fun closeBook() {
        // 退出前兜底：同步（runBlocking）把最近一次进度立即落库。
        // 用 runBlocking 而非 viewModelScope.launch：Activity 销毁时 viewModelScope 会被取消，
        // 异步任务可能来不及执行 → 进度丢失；同步落库保证「退出即保存」。
        persistLastProgressBlocking()
        lastSavedOffset = null
        _currentBookId.value = null
        _paragraphs.value = emptyList()
        _annotationEditor.value = null
        _memoryConfirm.value = null
        _selection.value = null
        resetRewriteState()
    }

    /** 同步落库最近进度（供 closeBook / onCleared 兜底；无进度时静默跳过） */
    private fun persistLastProgressBlocking() {
        val id = _currentBookId.value
        val off = lastSavedOffset
        if (id == null || off == null) return
        runBlocking(Dispatchers.IO) {
            runCatching { repository.saveProgress(id, off) }
        }
    }

    override fun onCleared() {
        // ViewModel 销毁兜底：若 closeBook 未被调用（如进程回收/直接 finish），也同步保存最新进度
        persistLastProgressBlocking()
        super.onCleared()
    }

    fun onImportPicked(uri: Uri) {
        viewModelScope.launch {
            repository.importFromUri(uri, _board.value, _currentFolderId.value)
        }
    }

    // ---- 备份 / 恢复（书架 TopAppBar 调用）----
    suspend fun backupManually(): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        BackupManager.backup(getApplication())
    }

    suspend fun restoreFromUri(uri: Uri): BackupManager.BackupResult {
        return BackupManager.restore(getApplication(), uri)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReaderViewModel(ZhiJiApp.instance) as T
            }
        }
    }
}

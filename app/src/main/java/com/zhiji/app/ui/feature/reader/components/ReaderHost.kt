package com.zhiji.app.ui.feature.reader.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiji.app.ui.feature.reader.ReaderViewModel

/**
 * 阅读器入口宿主：
 *  - 书架 → 点击书籍 → 进入阅读页
 *  - 支持返回键：阅读页先返回书架，书架再返回由外层 Resource 处理
 *
 * v38 复刻「多格式阅读器」：参数化 title 与 viewModelKey，
 *   让「智能阅读器」与「多格式阅读器」两个板块共享同一套 UI 代码，
 *   但各自持有独立 ViewModel（独立书架，未来多格式阅读器扩展 epub/pdf 不影响智能阅读器书架）。
 * v39 新增 board 参数：数据层按板块过滤 books 表，实现书架数据完全独立。
 */
@Composable
fun ReaderHost(
    modifier: Modifier = Modifier,
    title: String = "智能阅读器",
    viewModelKey: String = "smart_reader",
    board: String = "smart_reader",
    viewModel: ReaderViewModel = viewModel(key = viewModelKey, factory = ReaderViewModel.Factory)
) {
    // v39 进入板块时设置所属板块（幂等；按 key 的 ViewModel 只创建一次，book 数据隔离）
    LaunchedEffect(Unit) { viewModel.setBoard(board) }

    val currentBook by viewModel.currentBook.collectAsStateWithLifecycle()
    val inArticle = currentBook != null

    BackHandler(enabled = inArticle) {
        // 阅读页按返回 → 回到书架
        viewModel.closeBook()
    }

    if (inArticle) {
        ReaderArticleScreen(
            viewModel = viewModel,
            onBack = { viewModel.closeBook() },
            modifier = modifier
        )
    } else {
        ReaderShelfScreen(
            title = title,
            viewModel = viewModel,
            onOpenBook = { id -> viewModel.openBook(id) },
            modifier = modifier
        )
    }
}

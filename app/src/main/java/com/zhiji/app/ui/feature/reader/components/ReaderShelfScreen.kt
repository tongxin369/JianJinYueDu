package com.zhiji.app.ui.feature.reader.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiji.app.data.database.entity.BookEntity
import com.zhiji.app.ui.feature.reader.ReaderViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderShelfScreen(
    title: String = "智能阅读器",
    viewModel: ReaderViewModel,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val booksAll by viewModel.folderBooks.collectAsStateWithLifecycle()
    val allBooks by viewModel.shelfBooks.collectAsStateWithLifecycle()
    // 书架只显示用户勾选「显示在书架」的书籍；分类抽屉则展示全部
    val books = remember(booksAll) { booksAll.filter { it.shownOnShelf } }
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val currentFolderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(toast) {
        val msg = toast
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.onImportPicked(uri)
    }
    val doImport = { importLauncher.launch(arrayOf("text/plain", "text/markdown", "*/*")) }

    // 当前文件夹名称（顶部展示）
    val currentFolderName = remember(folders, currentFolderId) {
        folders.firstOrNull { it.id == currentFolderId }?.name
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.9f)) {
                ShelfFolderDrawer(
                    folders = folders,
                    currentBooks = booksAll,
                    allBooks = allBooks,
                    currentFolderId = currentFolderId,
                    clipboard = clipboard,
                    onEnterFolder = { viewModel.enterFolder(it) },
                    onCreateFolder = { viewModel.createFolder(it) },
                    onRenameFolder = { id, name -> viewModel.renameFolder(id, name) },
                    onDeleteFolder = { viewModel.deleteFolder(it) },
                    onOpenBook = { id ->
                        scope.launch { drawerState.close() }
                        onOpenBook(id)
                    },
                    onRenameBook = { id, name -> viewModel.renameBook(id, name) },
                    onMoveBook = { id, folderId -> viewModel.moveBookTo(id, folderId) },
                    onCopyBook = { viewModel.copyBookToClipboard(it) },
                    onCutBook = { viewModel.cutBookToClipboard(it) },
                    onToggleShowOnShelf = { viewModel.setBookShownOnShelf(it.id, !it.shownOnShelf) },
                    onPaste = { viewModel.pasteClipboard() },
                    onImportBook = { doImport() }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "书籍分类列表")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    title,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1
                                )
                                Text(
                                    if (currentFolderId == null) "全部书籍" else currentFolderName ?: "文件夹",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { doImport() }) {
                            Icon(Icons.Filled.Add, contentDescription = "导入书籍")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // 面包屑（当前分类位置）：记录 (id, name) 便于点击跳回任意层级
                val path = remember(folders, currentFolderId) {
                    val map = folders.associateBy { it.id }
                    val list = mutableListOf<Pair<String, String>>()
                    var id = currentFolderId
                    var guard = 0
                    while (id != null && guard < 64) {
                        val f = map[id] ?: break
                        list.add(0, f.id to f.name)
                        id = f.parentId
                        guard++
                    }
                    list
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Breadcrumb("全部", currentFolderId == null) { viewModel.enterFolder(null) }
                    path.forEachIndexed { index, (fid, name) ->
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                        Breadcrumb(name, index == path.lastIndex) { viewModel.enterFolder(fid) }
                    }
                }

                if (books.isEmpty()) {
                    EmptyShelf(
                        inFolder = currentFolderId != null,
                        hasHidden = booksAll.isNotEmpty(),
                        onImport = { doImport() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                onClick = { onOpenBook(book.id) },
                                onDelete = { viewModel.deleteBook(book.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun EmptyShelf(
    inFolder: Boolean,
    hasHidden: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (inFolder) Icons.Filled.Folder else Icons.Filled.Book,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            when {
                hasHidden -> "部分书籍已隐藏"
                inFolder -> "此文件夹还没有书籍"
                else -> "书架还空着"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            when {
                hasHidden -> "这些书已设置为不显示在书架，可在左上角分类列表里改回「显示在书架」"
                inFolder -> "点击右上角导入按钮，把书籍加入此文件夹"
                else -> "导入 .txt / .md / .html 文件，开启循序渐进的阅读学习"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 6.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onImport() }
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(6.dp))
                Text("导入书籍", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                val lastText = if (book.lastReadAt > 0) "上次阅读：${fmt.format(Date(book.lastReadAt))}" else "尚未阅读"
                val pct = when {
                    book.totalChars > 0 && book.readOffset >= 0 ->
                        (book.readOffset.toDouble() / book.totalChars * 100).toInt().coerceIn(0, 100)
                    !book.content.isNullOrBlank() && book.readOffset >= 0 ->
                        (book.readOffset.toDouble() / book.content.length.coerceAtLeast(1) * 100).toInt().coerceIn(0, 100)
                    else -> null
                }
                val progressText = if (pct != null && pct > 0) " · 进度 $pct%" else ""
                Text(
                    text = lastText + progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                if (pct != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct / 100f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "格式：${book.format.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这本书",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将同时删除这本书的阅读进度、注释与改写缓存，且不可恢复。原始文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

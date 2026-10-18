package com.zhiji.app.ui.feature.reader.components

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiji.app.data.database.entity.BookEntity
import com.zhiji.app.data.database.entity.BookFolderEntity
import com.zhiji.app.ui.feature.reader.ReaderViewModel

/**
 * 书架左侧「分类抽屉」内容：
 *   - 顶部面包屑导航（根目录 › 子文件夹 …，可点击跳转）
 *   - 工具栏：新建文件夹 / 导入书籍 / 粘贴
 *   - 文件夹树 + 当前文件夹内书籍，支持重命名 / 删除(文件夹) / 移动 / 复制 / 剪切
 *
 * 目的：用文件夹式结构对书籍做分类整理。
 */
@Composable
fun ShelfFolderDrawer(
    folders: List<BookFolderEntity>,
    currentBooks: List<BookEntity>,
    allBooks: List<BookEntity>,
    currentFolderId: String?,
    clipboard: ReaderViewModel.ClipboardItem?,
    onEnterFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onRenameBook: (String, String) -> Unit,
    onMoveBook: (String, String?) -> Unit,
    onCopyBook: (BookEntity) -> Unit,
    onCutBook: (BookEntity) -> Unit,
    onPaste: () -> Unit,
    onImportBook: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 面包屑：从根到当前文件夹
    val path = remember(folders, currentFolderId) {
        val map = folders.associateBy { it.id }
        val list = mutableListOf<BookFolderEntity>()
        var id = currentFolderId
        var guard = 0
        while (id != null && guard < 64) {
            val f = map[id] ?: break
            list.add(0, f)
            id = f.parentId
            guard++
        }
        list
    }
    val subFolders = remember(folders, currentFolderId) {
        folders.filter { it.parentId == currentFolderId }.sortedBy { it.name.lowercase() }
    }

    // ---- 弹窗状态 ----
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<BookFolderEntity?>(null) }
    var renameBookTarget by remember { mutableStateOf<BookEntity?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<BookFolderEntity?>(null) }
    var moveBookTarget by remember { mutableStateOf<BookEntity?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // 标题栏
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "书籍分类",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (currentFolderId != null) {
                    IconButton(onClick = {
                        onEnterFolder(folders.firstOrNull { it.id == currentFolderId }?.parentId)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                    }
                }
            }
        }

        // 面包屑导航
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Crumb(text = "根目录", active = currentFolderId == null) { onEnterFolder(null) }
            path.forEach { f ->
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                Crumb(text = f.name, active = f.id == currentFolderId) { onEnterFolder(f.id) }
            }
        }

        // 工具栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolButton(Icons.Filled.CreateNewFolder, "新建文件夹") { showNewFolder = true }
            ToolButton(Icons.Filled.NoteAdd, "导入书籍") { onImportBook() }
            if (clipboard != null) {
                ToolButton(Icons.Filled.ContentPaste, "粘贴《${clipboard.title}》") { onPaste() }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        // 文件夹 + 书籍列表
        if (subFolders.isEmpty() && currentBooks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "此文件夹为空\n可新建文件夹或导入书籍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(subFolders, key = { "folder_${it.id}" }) { folder ->
                    FolderRow(
                        folder = folder,
                        count = allBooks.count { it.folderId == folder.id },
                        onEnter = { onEnterFolder(folder.id) },
                        onRename = { renameFolderTarget = folder },
                        onDelete = { deleteFolderTarget = folder }
                    )
                }
                items(currentBooks, key = { "book_${it.id}" }) { book ->
                    BookRow(
                        book = book,
                        onOpen = { onOpenBook(book.id) },
                        onRename = { renameBookTarget = book },
                        onMove = { moveBookTarget = book },
                        onCopy = { onCopyBook(book) },
                        onCut = { onCutBook(book) }
                    )
                }
            }
        }
    }

    // ===== 弹窗 =====
    if (showNewFolder) {
        NameInputDialog(
            title = "新建文件夹",
            initial = "",
            hint = "文件夹名称",
            onConfirm = { showNewFolder = false; onCreateFolder(it) },
            onDismiss = { showNewFolder = false }
        )
    }
    renameFolderTarget?.let { target ->
        NameInputDialog(
            title = "重命名文件夹",
            initial = target.name,
            hint = "文件夹名称",
            onConfirm = { renameFolderTarget = null; onRenameFolder(target.id, it) },
            onDismiss = { renameFolderTarget = null }
        )
    }
    renameBookTarget?.let { target ->
        NameInputDialog(
            title = "重命名书籍",
            initial = target.title,
            hint = "书籍名称",
            onConfirm = { renameBookTarget = null; onRenameBook(target.id, it) },
            onDismiss = { renameBookTarget = null }
        )
    }
    deleteFolderTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("删除文件夹「${target.name}」？") },
            text = { Text("文件夹内的子文件夹与书籍将上移到上一级，不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolderTarget = null
                    onDeleteFolder(target.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text("取消") } }
        )
    }
    moveBookTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { moveBookTarget = null },
            title = { Text("移动《${target.title}》") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    item {
                        MoveTargetRow(
                            label = "根目录",
                            selected = target.folderId == null,
                            onClick = { moveBookTarget = null; onMoveBook(target.id, null) }
                        )
                    }
                    items(folders, key = { "move_${it.id}" }) { f ->
                        MoveTargetRow(
                            label = folderFullPath(f, folders),
                            selected = target.folderId == f.id,
                            onClick = { moveBookTarget = null; onMoveBook(target.id, f.id) }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveBookTarget = null }) { Text("取消") } }
        )
    }
}

private fun folderFullPath(folder: BookFolderEntity, all: List<BookFolderEntity>): String {
    val map = all.associateBy { it.id }
    val names = mutableListOf<String>()
    var id: String? = folder.id
    var guard = 0
    while (id != null && guard < 64) {
        val f = map[id] ?: break
        names.add(0, f.name)
        id = f.parentId
        guard++
    }
    return names.joinToString(" › ")
}

@Composable
private fun Crumb(text: String, active: Boolean, onClick: () -> Unit) {
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
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderRow(
    folder: BookFolderEntity,
    count: Int,
    onEnter: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEnter() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count > 0) {
                Text("$count 本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                    onClick = { menu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PictureAsPdf, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                    onClick = { menu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("移动到…") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                    onClick = { menu = false; onMove() }
                )
                DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { menu = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text("剪切") },
                    leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                    onClick = { menu = false; onCut() }
                )
            }
        }
    }
}

@Composable
private fun MoveTargetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Folder, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) else onDismiss() }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

package com.zhiji.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zhiji.app.ui.feature.memory.components.MemoryHost
import com.zhiji.app.ui.feature.reader.components.ReaderHost
import com.zhiji.app.ui.feature.settings.UserSettingsScreen

private data class BottomTab(
    val label: String,
    val icon: ImageVector
)

/** 「渐进阅读」三大板块：书架（智能阅读器） + 记忆库 + 用户（设置） */
private val tabs = listOf(
    BottomTab("书架", Icons.AutoMirrored.Filled.MenuBook),
    BottomTab("记忆库", Icons.Filled.PsychologyAlt),
    BottomTab("用户", Icons.Filled.Person)
)

@Composable
fun MainScreen(appViewModel: AppViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ReaderHost(
                modifier = Modifier.padding(innerPadding),
                title = "书架",
                viewModelKey = "smart_reader",
                board = "smart_reader"
            )
            1 -> MemoryHost(
                modifier = Modifier.padding(innerPadding)
            )
            else -> UserSettingsScreen(
                appViewModel = appViewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

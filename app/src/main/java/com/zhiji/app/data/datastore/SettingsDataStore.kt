package com.zhiji.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhiji.app.core.algorithm.ZhEnRewriter
import com.zhiji.app.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zhiji_settings")

/**
 * 「阅见」偏好设置存储：仅保留阅读器 + 记忆库相关配置。
 *   - 阅读习惯（翻页/滚动、原文/改写、改写显示模式、字体缩放）
 *   - 改写难度微调（每 100 字窗口内 不熟/不会/新增词 的个数）
 *   - 主题（深浅色 / 动态取色）
 *   - 自动备份版本号
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        // 上次自动备份时的应用版本号（longVersionCode），应用升级则触发备份
        private val LAST_BACKUP_VERSION = longPreferencesKey("last_backup_version")
        // 改写难度微调：每 100 字窗口内「不熟 / 不会 / 新增词」各自可出现的最大个数
        private val REWRITE_SEMI_PER100 = intPreferencesKey("rewrite_semi_per100")
        private val REWRITE_UNKNOWN_PER100 = intPreferencesKey("rewrite_unknown_per100")
        private val REWRITE_INJECT_PER100 = intPreferencesKey("rewrite_inject_per100")
        // ---- 阅读习惯记忆（下次打开保持上次设置的模式，不用每次重设）----
        // 阅读方式：scroll 滚动式 / pager 翻页式
        private val READING_PAGE_MODE = stringPreferencesKey("reading_page_mode")
        // 阅读内容：original 原文 / rewritten 改写后
        private val READING_VIEW_MODE = stringPreferencesKey("reading_view_mode")
        // 改写显示模式：replace 替换式 / annotate 对照式
        private val READING_REWRITE_MODE = stringPreferencesKey("reading_rewrite_mode")
        // 正文字体缩放百分比（100 = 原始 16sp；范围 80~240）
        private val READING_FONT_SCALE = intPreferencesKey("reading_font_scale")

        /** 正文字体缩放默认值/上下限（百分比） */
        const val DEFAULT_FONT_SCALE = 100
        const val MIN_FONT_SCALE = 80
        const val MAX_FONT_SCALE = 240
    }

    // ---- 改写难度微调：每 100 字窗口内「不熟 / 不会 / 新增词」的允许出现个数（可分别微调）----
    val rewriteTuning: Flow<ZhEnRewriter.Density.Tuning> = context.dataStore.data.map { prefs ->
        ZhEnRewriter.Density.tuning(
            semi = prefs[REWRITE_SEMI_PER100] ?: ZhEnRewriter.Density.SEMI_DEFAULT,
            unknown = prefs[REWRITE_UNKNOWN_PER100] ?: ZhEnRewriter.Density.UNKNOWN_DEFAULT,
            inject = prefs[REWRITE_INJECT_PER100] ?: ZhEnRewriter.Density.INJECT_DEFAULT
        )
    }

    suspend fun getRewriteTuning(): ZhEnRewriter.Density.Tuning = rewriteTuning.first()

    suspend fun setRewriteTuning(tuning: ZhEnRewriter.Density.Tuning) {
        val t = ZhEnRewriter.Density.tuning(tuning.semiPer100, tuning.unknownPer100, tuning.injectPer100)
        context.dataStore.edit {
            it[REWRITE_SEMI_PER100] = t.semiPer100
            it[REWRITE_UNKNOWN_PER100] = t.unknownPer100
            it[REWRITE_INJECT_PER100] = t.injectPer100
        }
    }

    // ---- 阅读习惯记忆（下次打开保持上次设置的模式）----
    /** 阅读方式：scroll 滚动式 / pager 翻页式 */
    val readingPageMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[READING_PAGE_MODE]?.takeIf { it == "pager" } ?: "scroll"
    }

    suspend fun getReadingPageMode(): String = readingPageMode.first()

    suspend fun setReadingPageMode(mode: String) {
        context.dataStore.edit { it[READING_PAGE_MODE] = if (mode == "pager") "pager" else "scroll" }
    }

    /** 阅读内容：original 原文 / rewritten 改写后 */
    val readingViewMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[READING_VIEW_MODE]?.takeIf { it == "rewritten" } ?: "original"
    }

    suspend fun getReadingViewMode(): String = readingViewMode.first()

    suspend fun setReadingViewMode(mode: String) {
        context.dataStore.edit { it[READING_VIEW_MODE] = if (mode == "rewritten") "rewritten" else "original" }
    }

    /** 改写显示模式：replace 替换式 / annotate 对照式 */
    val readingRewriteMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[READING_REWRITE_MODE]?.takeIf { it == "annotate" } ?: "replace"
    }

    suspend fun getReadingRewriteMode(): String = readingRewriteMode.first()

    suspend fun setReadingRewriteMode(mode: String) {
        context.dataStore.edit { it[READING_REWRITE_MODE] = if (mode == "annotate") "annotate" else "replace" }
    }

    /** 正文字体缩放百分比（100 = 16sp 原始字号；范围 80~240） */
    val readingFontScale: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[READING_FONT_SCALE] ?: DEFAULT_FONT_SCALE).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    suspend fun getReadingFontScale(): Int = readingFontScale.first()

    suspend fun setReadingFontScale(percent: Int) {
        context.dataStore.edit { it[READING_FONT_SCALE] = percent.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[THEME_MODE] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[DYNAMIC_COLOR] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    // ---- 自动备份版本号（防止重复备份 + 应用升级触发） ----
    suspend fun getLastBackupVersion(): Long = context.dataStore.data.first()[LAST_BACKUP_VERSION] ?: 0L

    suspend fun setLastBackupVersion(version: Long) {
        context.dataStore.edit { it[LAST_BACKUP_VERSION] = version }
    }
}

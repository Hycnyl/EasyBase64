@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.orbis.easybase64.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orbis.easybase64.core.*
import com.orbis.easybase64.data.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EasyBase64App(
    repository: BatchRepository,
    onPickFiles: () -> Unit,
    onPickInputTree: (recursive: Boolean) -> Unit,
    onPickOutput: () -> Unit,
    onStart: (CodecOptions, retryOnly: Boolean) -> Unit,
    onOpenOutput: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("easybase64_ui", Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString("language", "system") ?: "system") }
    var theme by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val systemLocale = LocalConfiguration.current.locales[0]
    val tx = UiText(language == "zh" || (language == "system" && systemLocale.language == "zh"))
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val state by repository.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(loadOptions(prefs)) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var folderOpen by rememberSaveable { mutableStateOf(false) }
    var clearOpen by rememberSaveable { mutableStateOf(false) }
    var pendingReplacement by rememberSaveable { mutableStateOf<String?>(null) }
    var recursive by rememberSaveable { mutableStateOf(prefs.getBoolean("recursive", false)) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val locked = state.busy || !state.loaded
    val unresolvedCleanup = state.residualCount > 0 || state.tasks.any { task ->
        task.attempts.any { it.phase == "RENAMING" && it.finalUri == null }
    }
    val launchInput: (String) -> Unit = { kind ->
        when (kind) {
            "files" -> onPickFiles()
            "output" -> onPickOutput()
            "folder" -> onPickInputTree(recursive)
        }
    }
    val requestInput: (String) -> Unit = { kind ->
        if (unresolvedCleanup) pendingReplacement = kind else launchInput(kind)
    }
    val frozenOptions = state.hasFrozenOptions
    val options = if (frozenOptions) state.options else draft
    val updateOptions: (CodecOptions) -> Unit = { draft = it; saveOptions(prefs, it) }
    val perform: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            try { action() }
            catch (_: Exception) { snackbar.showSnackbar(tx.t("Unable to complete the operation. Check access and retry.", "操作未能完成，请检查访问权限后重试。")) }
        }
    }
    DisposableEffect(dark) {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    MaterialTheme(colorScheme = colors) {
        val adaptive = currentWindowAdaptiveInfoV2()
        val windowSize = LocalWindowInfo.current.containerSize
        val hingeInsets = remember(windowSize, adaptive.windowPosture) {
            safeHingeInsets(windowSize.width, windowSize.height, adaptive.windowPosture.hingeList)
        }
        val density = LocalDensity.current
        val safeArea = with(density) {
            Modifier.fillMaxSize().padding(
                start = hingeInsets[0].toDp(), top = hingeInsets[1].toDp(),
                end = hingeInsets[2].toDp(), bottom = hingeInsets[3].toDp(),
            )
        }
        BoxWithConstraints(safeArea) {
            val wide = maxWidth >= 840.dp && maxHeight >= 480.dp &&
                adaptive.windowSizeClass.isWidthAtLeastBreakpoint(840)
            val selected = state.tasks.firstOrNull { it.id == selectedId }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.padding(10.dp).size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("EasyBase64", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("app_title"))
                            Text(tx.t("FILES, SIMPLY CONVERTED", "让文件转换更简单"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { settingsOpen = true }, modifier = Modifier.testTag("settings_button")) {
                            Icon(Icons.Default.Tune, tx.t("Settings and about", "设置与关于"))
                        }
                    }
                },
                bottomBar = {
                    ActionBar(state, options, tx, onStart, { repository.cancel() }, onOpenOutput)
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { insets ->
                Row(Modifier.fillMaxSize().padding(insets).imePadding(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("queue_list"),
                        contentPadding = PaddingValues(start = 20.dp, end = if (wide) 0.dp else 20.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Hero(options, locked || frozenOptions, tx, updateOptions)
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = { requestInput("files") }, enabled = !locked, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) {
                                    Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(tx.t("Add files", "添加文件"))
                                }
                                OutlinedButton(onClick = { folderOpen = true }, enabled = !locked, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) {
                                    Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(tx.t("Folder", "选择文件夹"))
                                }
                            }
                        }
                        item { OutputCard(state, tx, !locked) { requestInput("output") } }
                        if (!wide) item {
                            OutlinedCard(onClick = { optionsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(tx.t("Format options", "格式参数"), style = MaterialTheme.typography.titleSmall)
                                        Text(tx.summary(options), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                        state.warning?.let { warning -> item {
                            Notice(tx.error(warning), Icons.Default.Info)
                        } }
                        if (state.residualCount > 0) item {
                            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(tx.t("${state.residualCount} residual file(s)", "${state.residualCount} 个残留文件"), fontWeight = FontWeight.SemiBold)
                                    Text(tx.error("CLEANUP_FAILED"), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { perform { repository.cleanupResiduals() } }, enabled = !locked) {
                                        Text(tx.t("Retry cleanup", "重试清理"))
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tx.t("File queue", "文件队列"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (state.tasks.isEmpty()) tx.t("Any file type. One safe batch.", "支持任意文件类型，逐个安全处理。")
                                        else tx.t("${state.tasks.size} files · ${state.succeeded} completed", "${state.tasks.size} 个文件 · ${state.succeeded} 个完成"),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (state.tasks.isNotEmpty()) TextButton(onClick = { clearOpen = true }, enabled = !locked) {
                                    Text(tx.t("Clear", "清空"))
                                }
                            }
                        }
                        if (state.scanning) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(tx.t("Scanning folder… ${state.tasks.size} files found", "正在扫描文件夹… 已找到 ${state.tasks.size} 个文件"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.tasks.isEmpty() && !state.scanning) item {
                            EmptyQueue(tx)
                        }
                        items(state.tasks, key = { it.id }) { task ->
                            FileRow(task, selectedId == task.id, tx, !locked && task.status == TaskStatus.QUEUED,
                                onSelect = { selectedId = task.id }, onRemove = { perform { repository.removeTask(task.id) } })
                        }
                        if (state.tasks.isNotEmpty()) item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(tx.t("New batch folder · Originals preserved · No overwrites", "新建批次目录 · 保留源文件 · 不覆盖已有文件"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (wide) {
                        Surface(
                            Modifier.widthIn(min = 320.dp, max = 420.dp).weight(0.8f).fillMaxHeight().padding(end = 20.dp, top = 8.dp, bottom = 16.dp),
                            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (selected != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tx.t("File details", "文件详情"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { selectedId = null }) { Icon(Icons.Default.Close, tx.t("Close details", "关闭详情")) }
                                    }
                                    FileDetails(selected, tx)
                                    HorizontalDivider()
                                }
                                OptionsPanel(options, !locked && !frozenOptions, tx, updateOptions)
                            }
                        }
                    }
                }
            }
            if (!wide && selected != null) {
                SheetDialog(tx.t("File details", "文件详情"), tx, { selectedId = null }, "details_dialog") {
                    FileDetails(selected, tx)
                }
            }
            if (optionsOpen && !wide) {
                SheetDialog(tx.t("Format options", "格式参数"), tx, { optionsOpen = false }, "options_dialog") {
                    OptionsPanel(options, !locked && !frozenOptions, tx, updateOptions)
                }
            }
            if (settingsOpen) SettingsDialog(tx, language, theme,
                onLanguage = { language = it; prefs.edit().putString("language", it).apply() },
                onTheme = { theme = it; prefs.edit().putString("theme", it).apply() },
                onClose = { settingsOpen = false })
            if (folderOpen) AlertDialog(
                onDismissRequest = { folderOpen = false },
                icon = { Icon(Icons.Default.FolderOpen, null) },
                title = { Text(tx.t("Scan a folder", "扫描文件夹")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tx.t("A folder replaces the current input queue. Relative subfolders are preserved in the new output batch.", "选择文件夹会替换当前输入队列；输出批次将保留来源的相对子目录。"))
                        SwitchRow(tx.t("Include subfolders", "包含子目录"), "", recursive, true, "include_recursive") {
                            recursive = it; prefs.edit().putBoolean("recursive", it).apply()
                        }
                        if (state.outputRoot == null) Text(tx.t("Choose an output folder before scanning.", "请先选择输出目录，再扫描输入文件夹。"), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        folderOpen = false
                        requestInput(if (state.outputRoot == null) "output" else "folder")
                    }) { Text(if (state.outputRoot == null) tx.t("Choose output", "选择输出目录") else tx.t("Choose folder", "选择文件夹")) }
                },
                dismissButton = { TextButton(onClick = { folderOpen = false }) { Text(tx.t("Cancel", "取消")) } },
            )
            if (pendingReplacement != null) AlertDialog(
                onDismissRequest = { pendingReplacement = null },
                title = { Text(tx.t("Review unfinished output", "请检查未完成输出")) },
                text = { Text(tx.t("Some output files still need cleanup or verification. Starting a new batch removes their tracked locations without deleting those files. Review the current file details first, or continue if you have already handled them.", "部分输出仍需清理或验证。建立新批次会移除其位置记录，但不会删除这些文件。请先检查当前文件详情；若已处理，可继续。")) },
                confirmButton = { TextButton(onClick = {
                    val kind = pendingReplacement
                    pendingReplacement = null
                    if (kind != null) perform { repository.clear(); launchInput(kind) }
                }) { Text(tx.t("Clear records & continue", "清除记录并继续")) } },
                dismissButton = { TextButton(onClick = { pendingReplacement = null }) { Text(tx.t("Review first", "先检查")) } },
            )
            if (clearOpen) AlertDialog(
                onDismissRequest = { clearOpen = false },
                title = { Text(tx.t("Clear this queue?", "清空当前队列？")) },
                text = { Text(if (unresolvedCleanup)
                    tx.t("This queue still has cleanup records. Clearing removes their tracked locations, without deleting residual files. Review the file details before continuing. Source files and completed outputs remain.", "当前队列仍有待检查的清理记录。清空会移除其位置记录，但不会删除残留文件。请先查看文件详情。源文件与已完成输出均保留。")
                    else tx.t("Only task records are cleared. Your source files and completed output files remain.", "仅清除任务记录。源文件与已完成的输出文件都会保留。")) },
                confirmButton = { TextButton(onClick = { clearOpen = false; selectedId = null; perform { repository.clear() } }) { Text(tx.t("Clear queue", "清空队列")) } },
                dismissButton = { TextButton(onClick = { clearOpen = false }) { Text(tx.t("Keep", "保留")) } },
            )
        }
    }
}

@Composable
private fun Hero(options: CodecOptions, locked: Boolean, tx: UiText, onChange: (CodecOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tx.t("Files, transformed.", "文件转换，轻松完成。"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(tx.t("Encode or decode with full control.", "编码或解码，每个参数由你掌控。"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("operation_selector")) {
            SegmentedButton(selected = options.operation == Operation.ENCODE, onClick = { onChange(options.copy(operation = Operation.ENCODE)) }, enabled = !locked, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp)) }) {
                Text(tx.t("Encode", "编码"))
            }
            SegmentedButton(selected = options.operation == Operation.DECODE, onClick = { onChange(options.copy(operation = Operation.DECODE)) }, enabled = !locked, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Default.Download, null, Modifier.size(18.dp)) }) {
                Text(tx.t("Decode", "解码"))
            }
        }
    }
}

@Composable
private fun OutputCard(state: BatchState, tx: UiText, enabled: Boolean, onSelect: () -> Unit) {
    Card(onClick = onSelect, enabled = enabled, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(tx.t("Output folder", "输出目录"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.outputRoot?.let(::displayUri) ?: tx.t("Choose where files are saved", "选择文件保存位置"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.outputRoot != null) Text(tx.t("A new batch subfolder is created here", "将在此目录中新建批次子目录"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, tx.t("Select output folder", "选择输出目录"))
        }
    }
}

@Composable
private fun EmptyQueue(tx: UiText) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Default.FileCopy, null, Modifier.padding(18.dp).size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(tx.t("Your files start here", "从添加文件开始"), style = MaterialTheme.typography.titleMedium)
            Text(tx.t("Add files or scan a folder. Then choose an output folder and start your batch.", "添加文件或扫描文件夹，选择输出目录，即可开始批量处理。"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileRow(task: FileTask, selected: Boolean, tx: UiText, removable: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    val running = task.status == TaskStatus.RUNNING || task.status == TaskStatus.COMMITTING
    val tint = when (task.status) {
        TaskStatus.FAILED, TaskStatus.INTERRUPTED -> MaterialTheme.colorScheme.error
        TaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(onClick = onSelect, shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(statusIcon(task.status), null, tint = tint, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${tx.status(task.status)} · ${formatBytes(task.size)}", style = MaterialTheme.typography.bodySmall, color = tint)
                    if (task.readBytes > 0 || running) Text(tx.t("Read ${formatBytes(task.readBytes)} · Wrote ${formatBytes(task.writtenBytes)}", "已读 ${formatBytes(task.readBytes)} · 已写 ${formatBytes(task.writtenBytes)}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (removable) IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, tx.t("Remove ${task.name}", "移除 ${task.name}")) }
                else Icon(Icons.Default.ChevronRight, tx.t("File details", "文件详情"), Modifier.size(20.dp))
            }
            if (running) {
                if (task.size != null && task.size!! > 0 && task.status != TaskStatus.COMMITTING) {
                    LinearProgressIndicator(progress = { (task.readBytes.toDouble() / task.size!!).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            task.errorCode?.let { Text(tx.error(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ActionBar(state: BatchState, options: CodecOptions, tx: UiText, onStart: (CodecOptions, Boolean) -> Unit, onCancel: () -> Unit, onOpenOutput: () -> Unit) {
    val retry = state.tasks.any { it.status in setOf(TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELLED) }
    val ready = state.tasks.any { it.status == TaskStatus.QUEUED }
    Surface(shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy || state.hasFrozenOptions) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        when {
                            state.cancelling -> tx.t("Cancelling… waiting for the file provider", "正在取消… 等待文档提供方响应")
                            state.scanning -> tx.t("Scanning folder…", "正在扫描文件夹…")
                            else -> tx.batch(state.status)
                        },
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    )
                    if (state.tasks.isNotEmpty()) Text("${state.succeeded}/${state.tasks.size}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!state.busy && state.outputBatch != null) OutlinedButton(onClick = onOpenOutput, modifier = Modifier.heightIn(min = 52.dp)) {
                    Icon(Icons.Default.FolderOpen, tx.t("Open output folder", "打开输出目录"))
                }
                if (state.busy) {
                    Button(onClick = onCancel, enabled = !state.cancelling, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("cancel_button"), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(tx.t("Cancel", "取消任务"))
                    }
                } else {
                    Button(onClick = { onStart(options, retry && !ready) }, enabled = state.loaded && state.outputRoot != null && (ready || retry), modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("start_button")) {
                        Icon(if (retry && !ready) Icons.Default.Refresh else Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (retry && !ready) tx.t("Retry unfinished files", "重试未完成文件") else if (options.operation == Operation.ENCODE) tx.t("Start encoding", "开始编码") else tx.t("Start decoding", "开始解码"))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsPanel(options: CodecOptions, enabled: Boolean, tx: UiText, onChange: (CodecOptions) -> Unit) {
    val certificate = options.operation == Operation.ENCODE && options.certificate
    Column(Modifier.fillMaxWidth().testTag("options_panel"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tx.t("Format options", "格式参数"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (!enabled) Notice(tx.t("This batch uses its saved settings. Clear the queue or add a new input to start a new batch.", "此批次使用已保存的参数。清空队列或添加新来源即可建立新批次。"), Icons.Default.Lock)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tx.t("Alphabet", "字母表"), style = MaterialTheme.typography.titleSmall)
            Choices(listOf("Standard", "URL-safe"), if (certificate || options.alphabet == Alphabet.STANDARD) 0 else 1, enabled && !certificate) { onChange(options.copy(alphabet = if (it == 0) Alphabet.STANDARD else Alphabet.URL_SAFE)) }
        }
        if (options.operation == Operation.ENCODE) {
            SwitchRow(tx.t("CERTIFICATE wrapper", "CERTIFICATE 包装"), tx.t("Text wrapping only; does not validate a certificate.", "仅为文本封装，不验证内容是否为证书。"), options.certificate, enabled, "certificate_toggle") {
                onChange(if (it) options.copy(certificate = true, alphabet = Alphabet.STANDARD, padding = true, wrapWidth = 64, lineEnding = LineEnding.CRLF, finalNewline = true) else options.copy(certificate = false))
            }
            if (certificate) Notice(tx.t("Wrapper format: Standard · padding · 64 characters · CRLF", "包装固定格式：标准字母表 · 保留填充 · 每行 64 字符 · CRLF"), Icons.Default.Info)
            SwitchRow(tx.t("Keep padding (=)", "保留填充（=）"), tx.t("Keep the standard ending of each encoded file.", "保留编码文件末尾的标准填充字符。"), certificate || options.padding, enabled && !certificate, "padding_toggle") { onChange(options.copy(padding = it)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx.t("Line wrapping", "正文折行"), style = MaterialTheme.typography.titleSmall)
                Choices(listOf(tx.t("None", "不折行"), "64", "76"), listOf(0, 64, 76).indexOf(if (certificate) 64 else options.wrapWidth).coerceAtLeast(0), enabled && !certificate) { onChange(options.copy(wrapWidth = listOf(0, 64, 76)[it])) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx.t("Line ending", "换行符"), style = MaterialTheme.typography.titleSmall)
                Choices(listOf("LF", "CRLF"), if (certificate || options.lineEnding == LineEnding.CRLF) 1 else 0, enabled && !certificate) { onChange(options.copy(lineEnding = if (it == 0) LineEnding.LF else LineEnding.CRLF)) }
            }
            SwitchRow(tx.t("Final newline", "末尾换行"), tx.t("Append one line ending to a nonempty body.", "在非空正文末尾添加一个换行符。"), certificate || options.finalNewline, enabled && !certificate, "final_newline_toggle") { onChange(options.copy(finalNewline = it)) }
        } else {
            SwitchRow(tx.t("Ignore ASCII whitespace", "忽略 ASCII 空白"), tx.t("Allow spaces, tabs, CR and LF in the body.", "允许正文中出现空格、制表符、CR 和 LF。"), options.ignoreWhitespace, enabled, "whitespace_toggle") { onChange(options.copy(ignoreWhitespace = it)) }
            SwitchRow(tx.t("Recognize CERTIFICATE wrapper", "识别 CERTIFICATE 包装"), tx.t("Accept plain Base64 or one matching CERTIFICATE block.", "接受纯 Base64 正文或单个匹配的 CERTIFICATE 包装块。"), options.recognizeCertificate, enabled, "recognize_wrapper_toggle") { onChange(options.copy(recognizeCertificate = it)) }
            SwitchRow(tx.t("Allow omitted padding", "允许省略填充"), tx.t("Accept valid unpadded tails; malformed input still fails.", "接受有效的无填充尾部；格式错误仍会失败。"), options.allowOmittedPadding, enabled, "omitted_padding_toggle") { onChange(options.copy(allowOmittedPadding = it)) }
            Notice(tx.t("The entire input is validated, including data after padding and the final boundary.", "将校验完整输入，包括填充字符和结束边界之后的内容。"), Icons.Default.VerifiedUser)
        }
    }
}

@Composable
private fun Choices(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, onClick = { onSelect(index) }, enabled = enabled, label = { Text(label) }, leadingIcon = if (selected == index) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null)
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun Notice(message: String, icon: ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileDetails(task: FileTask, tx: UiText) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SelectionContainer { Text(task.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon(task.status), null, Modifier.size(20.dp))
            Text(tx.status(task.status), style = MaterialTheme.typography.labelLarge)
        }
        DetailValue(tx.t("Source size", "源文件大小"), formatBytes(task.size))
        DetailValue(tx.t("Progress", "处理进度"), tx.t("Read ${formatBytes(task.readBytes)} · Wrote ${formatBytes(task.writtenBytes)}", "已读 ${formatBytes(task.readBytes)} · 已写 ${formatBytes(task.writtenBytes)}"))
        if (task.relativePath.isNotEmpty()) DetailValue(tx.t("Relative folder", "相对目录"), task.relativePath.joinToString("/"))
        DetailValue(tx.t("Source URI", "来源 URI"), task.sourceUri)
        task.outputName?.let { DetailValue(tx.t("Saved as", "实际保存名称"), it) }
        task.outputUri?.let { DetailValue(tx.t("Output URI", "输出 URI"), it) }
        task.errorCode?.let {
            Text(tx.error(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            DetailValue(tx.t("Error code", "错误代码"), it)
        }
        task.error?.let { DetailValue(tx.t("Technical detail", "技术详情"), it) }
        if (task.attempts.isNotEmpty()) {
            HorizontalDivider()
            Text(tx.t("Attempt history", "尝试记录"), style = MaterialTheme.typography.titleMedium)
            task.attempts.asReversed().forEachIndexed { index, attempt ->
                OutlinedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx.t("Attempt ${task.attempts.size - index} · ${tx.status(attempt.status)}", "第 ${task.attempts.size - index} 次尝试 · ${tx.status(attempt.status)}"), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, if (tx.zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH).format(Date(attempt.startedAt)), style = MaterialTheme.typography.bodySmall)
                        attempt.errorCode?.let { Text(tx.error(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        attempt.error?.let { DetailValue(tx.t("Technical detail", "技术详情"), it) }
                        DetailValue(tx.t("Commit stage", "提交阶段"), attempt.phase)
                        attempt.plannedName?.let { DetailValue(tx.t("Intended output name", "计划输出名称"), it) }
                        attempt.parentUri?.let { DetailValue(tx.t("Output parent folder", "输出父目录"), it) }
                        if (attempt.residualUris.isNotEmpty()) {
                            Text(tx.t("Residual files", "残留文件"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            attempt.residualUris.forEach { DetailValue("URI", it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SheetDialog(title: String, tx: UiText, onClose: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.94f).fillMaxHeight(0.88f).testTag(tag), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, tx.t("Close", "关闭")) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 12.dp)) { Text(tx.t("Done", "完成")) }
            }
        }
    }
}

@Composable
private fun SettingsDialog(tx: UiText, language: String, theme: String, onLanguage: (String) -> Unit, onTheme: (String) -> Unit, onClose: () -> Unit) {
    SheetDialog(tx.t("Settings & about", "设置与关于"), tx, onClose, "settings_dialog") {
        Text(tx.t("Language", "语言"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("system" to tx.t("System", "跟随系统"), "en" to "English", "zh" to "简体中文").forEach { (value, label) ->
                FilterChip(selected = language == value, onClick = { onLanguage(value) }, label = { Text(label) }, modifier = Modifier.testTag("language_$value"))
            }
        }
        Text(tx.t("Appearance", "外观"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("system" to tx.t("System", "跟随系统"), "light" to tx.t("Light", "浅色"), "dark" to tx.t("Dark", "深色")).forEach { (value, label) ->
                FilterChip(selected = theme == value, onClick = { onTheme(value) }, label = { Text(label) }, modifier = Modifier.testTag("theme_$value"))
            }
        }
        Text(tx.t("Colors adapt to your Android wallpaper.", "配色随 Android 系统壁纸动态变化。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.SwapHoriz, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("EasyBase64", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("1.0.0", style = MaterialTheme.typography.bodySmall)
            }
        }
        DetailValue(tx.t("Developers", "开发者"), "Orbis & Codex")
        Notice(tx.t("Local processing. No account, advertising or telemetry. The app has no Internet permission.", "本地处理，无账号、广告或遥测。应用未声明网络权限。"), Icons.Default.Shield)
        Text(tx.t("A cloud document provider may transfer files using its own network connection. Background tasks can be interrupted by Android; reopen the app to review and retry.", "云盘文档提供方可能通过其自身网络连接传输文件。后台任务可能被 Android 中断；重新打开应用后可检查并重试。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tx.t("Base64 is an encoding, not encryption. File names and metadata cannot be recovered from Base64 alone.", "Base64 是编码方式，不是加密。仅靠 Base64 正文无法恢复原文件名和元数据。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusIcon(status: TaskStatus): ImageVector = when (status) {
    TaskStatus.QUEUED -> Icons.Default.InsertDriveFile
    TaskStatus.RUNNING -> Icons.Default.Sync
    TaskStatus.COMMITTING -> Icons.Default.Save
    TaskStatus.SUCCEEDED -> Icons.Default.CheckCircle
    TaskStatus.FAILED -> Icons.Default.ErrorOutline
    TaskStatus.CANCELLED -> Icons.Default.Cancel
    TaskStatus.INTERRUPTED -> Icons.Default.WarningAmber
}

private fun displayUri(value: String): String = try {
    Uri.decode(Uri.parse(value).lastPathSegment ?: value).substringAfter(":", Uri.decode(Uri.parse(value).lastPathSegment ?: value))
        .ifBlank { value }
} catch (_: Exception) { value }

private fun loadOptions(prefs: SharedPreferences): CodecOptions = CodecOptions(
    operation = runCatching { Operation.valueOf(prefs.getString("operation", "ENCODE")!!) }.getOrDefault(Operation.ENCODE),
    alphabet = runCatching { Alphabet.valueOf(prefs.getString("alphabet", "STANDARD")!!) }.getOrDefault(Alphabet.STANDARD),
    padding = prefs.getBoolean("padding", true), wrapWidth = prefs.getInt("wrapWidth", 0),
    lineEnding = runCatching { LineEnding.valueOf(prefs.getString("lineEnding", "LF")!!) }.getOrDefault(LineEnding.LF),
    finalNewline = prefs.getBoolean("finalNewline", false), certificate = prefs.getBoolean("certificate", false),
    ignoreWhitespace = prefs.getBoolean("ignoreWhitespace", true), recognizeCertificate = prefs.getBoolean("recognizeCertificate", true),
    allowOmittedPadding = prefs.getBoolean("allowOmittedPadding", false),
)

private fun saveOptions(prefs: SharedPreferences, options: CodecOptions) {
    prefs.edit().putString("operation", options.operation.name).putString("alphabet", options.alphabet.name)
        .putBoolean("padding", options.padding).putInt("wrapWidth", options.wrapWidth).putString("lineEnding", options.lineEnding.name)
        .putBoolean("finalNewline", options.finalNewline).putBoolean("certificate", options.certificate)
        .putBoolean("ignoreWhitespace", options.ignoreWhitespace).putBoolean("recognizeCertificate", options.recognizeCertificate)
        .putBoolean("allowOmittedPadding", options.allowOmittedPadding).apply()
}

/** Keep all controls in the largest unobstructed display region on folding devices. */
private fun safeHingeInsets(width: Int, height: Int, hinges: List<HingeInfo>): List<Int> {
    var left = 0
    var top = 0
    var right = width
    var bottom = height
    hinges.filter { it.isSeparating || it.isOccluding }.forEach { hinge ->
        val bounds = hinge.bounds
        if (bounds.right >= left && bounds.left <= right && bounds.bottom >= top && bounds.top <= bottom) {
            if (hinge.isVertical) {
                val hingeLeft = bounds.left.toInt().coerceIn(left, right)
                val hingeRight = bounds.right.toInt().coerceIn(left, right)
                if (hingeLeft - left >= right - hingeRight) right = hingeLeft else left = hingeRight
            } else {
                val hingeTop = bounds.top.toInt().coerceIn(top, bottom)
                val hingeBottom = bounds.bottom.toInt().coerceIn(top, bottom)
                if (hingeTop - top >= bottom - hingeBottom) bottom = hingeTop else top = hingeBottom
            }
        }
    }
    return listOf(left, top, width - right, height - bottom)
}

// run() persists its parameter snapshot before creating the output batch directory.
// A failed/cancelled/interrupted batch therefore stays frozen even with zero file attempts.
private val BatchState.hasFrozenOptions: Boolean
    get() = status != BatchStatus.IDLE && status != BatchStatus.READY

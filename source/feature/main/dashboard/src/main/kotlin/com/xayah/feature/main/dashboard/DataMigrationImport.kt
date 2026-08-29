package com.xayah.feature.main.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.DismissState
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.ModalBottomSheet
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.Surface
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.DateUtil
import com.xayah.core.util.maybePopBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageDataMigrationImport(
    viewModel: DataMigrationImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val scope = rememberCoroutineScope()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val isParsing by viewModel.isParsing.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val detailMessage by viewModel.detailMessage.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val importStages by viewModel.importStages.collectAsStateWithLifecycle()
    val importCurrentStage by viewModel.importCurrentStage.collectAsStateWithLifecycle()
    val importStageProgress by viewModel.importStageProgress.collectAsStateWithLifecycle()

    // 解析/导入 共用 4 段（段 0~2 解析阶段：读取 / 校验 / 解析；段 3 导入阶段：解压与恢复）
    val importStageLabels = remember(context) {
        listOf(
            context.getString(R.string.migration_stage_import_segment_downloading),
            context.getString(R.string.migration_stage_import_segment_verifying),
            context.getString(R.string.migration_stage_import_segment_parsing),
            context.getString(R.string.migration_stage_import_segment_extracting),
        )
    }

    // 可选的 SHA-256 校验码：导出端成功页会展示，粘贴到这里即可在导入前强校验完整性
    var checksumInput by rememberSaveable { mutableStateOf("") }
    var showShaHistory by rememberSaveable { mutableStateOf(false) }
    var showShaGuide by rememberSaveable { mutableStateOf(false) }
    val shaHistory by viewModel.shaHistory.collectAsStateWithLifecycle()
    val clouds by viewModel.clouds.collectAsStateWithLifecycle()
    val remotePackages by viewModel.remotePackages.collectAsStateWithLifecycle()

    // 从云端导入：两层选择（云端账号 → 该云端 migration/ 下的迁移包）
    var showCloudSheet by remember { mutableStateOf(false) }
    var showRemoteSheet by remember { mutableStateOf(false) }
    val cloudSheetState = rememberModalBottomSheetState()
    val remoteSheetState = rememberModalBottomSheetState()

    // 解析完成后统一走确认弹窗 → 导入（本地与云端共用）
    fun confirmImport(apps: List<String>) {
        if (apps.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.migration_import_empty)) }
        } else {
            scope.launch {
                val state = dialogState.open(
                    initialState = Unit,
                    title = context.getString(R.string.import_backup),
                    icon = null,
                    dismissText = context.getString(R.string.cancel),
                    confirmText = context.getString(R.string.confirm),
                ) { _ ->
                    Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                        Text(text = context.getString(R.string.migration_import_parsed, apps.size))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
                        ) {
                            items(apps) { app ->
                                Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                                    Text(text = "• ")
                                    Text(
                                        text = app,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Text(text = context.getString(R.string.migration_import_confirm))
                    }
                }.first
                if (state == DismissState.CONFIRM) {
                    viewModel.import(importStageLabels)
                } else {
                    // 用户取消或关闭弹窗，清理临时文件
                    viewModel.cleanupTmpFile()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        scope.launch { viewModel.loadShaHistory() }
        scope.launch { viewModel.loadClouds() }
    }

    // Processing 时根据 importStages 动态显示「第 X / N 步 + 当前段名」
    val (stageTitle, stageDesc) = when (stage) {
        MigrationStage.Processing -> {
            val labels = importStages
            val idx = importCurrentStage.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
            val stepText = if (labels.isNotEmpty()) {
                context.getString(
                    R.string.migration_stage_segment_format,
                    idx + 1,
                    labels.size,
                )
            } else {
                context.getString(R.string.migration_stage_processing_title)
            }
            stepText to context.getString(R.string.migration_stage_processing_desc)
        }
        MigrationStage.Success -> context.getString(R.string.migration_stage_import_success_title) to
            context.getString(R.string.migration_stage_import_success_desc)
        else -> context.getString(R.string.migration_stage_import_idle_title) to
            context.getString(R.string.migration_stage_import_idle_desc)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                confirmImport(
                    viewModel.parse(
                        uri = it,
                        expectedSha256 = checksumInput.trim().takeIf { s -> s.isNotEmpty() },
                        stageLabels = importStageLabels,
                    )
                )
            }
        }
    }

    LaunchedEffect(success) {
        if (success) {
            snackbarHostState.showSnackbar(context.getString(R.string.migration_import_success))
            viewModel.consumeSuccess()
            viewModel.consumeStage()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.migration_import_failed))
        }
    }

    LaunchedEffect(detailMessage) {
        detailMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeDetailMessage()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.import_backup),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SizeTokens.Level16),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
            ) {
                when (stage) {
                    MigrationStage.Processing -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stageTitle)
                        }
                    }

                    MigrationStage.Success -> {
                        Button(
                            onClick = { navController.maybePopBackStack() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.migration_export_done))
                        }
                    }

                    else -> {
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/zstd", "application/octet-stream", "*/*"))
                            },
                            enabled = isImporting.not(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.import_backup))
                        }
                        // 从云端导入：从已配置云端账号的 migration/ 目录下载迁移包
                        OutlinedButton(
                            onClick = { showCloudSheet = true },
                            enabled = isImporting.not(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(SizeTokens.Level16),
                            )
                            Text(
                                text = stringResource(R.string.migration_import_from_cloud),
                                modifier = Modifier.padding(start = SizeTokens.Level8),
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (stage != MigrationStage.Idle) {
            // 解析中 / 导入中 / 完成：整页只显示进度卡（仿导出页）。内容垂直居中,长内容溢出可滚
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                MigrationStageCard(
                    stage = stage,
                    title = stageTitle,
                    description = stageDesc,
                    stages = importStages,
                    currentStageIndex = importCurrentStage,
                    currentStageProgress = importStageProgress,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .paddingHorizontal(SizeTokens.Level24),
            ) {
                InnerTopSpacer(innerPadding = innerPadding)
                // 未开始时引导用户选择迁移包
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SizeTokens.Level32),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = null,
                            tint = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.Primary.value,
                            modifier = Modifier.padding(SizeTokens.Level8),
                        )
                        TitleLargeText(text = stringResource(R.string.migration_stage_import_idle_title))
                        Text(
                            text = stringResource(R.string.migration_stage_import_idle_desc),
                            color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                            textAlign = TextAlign.Center,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = checksumInput,
                                onValueChange = { checksumInput = it },
                                label = { Text(text = "SHA-256 校验码（可选）") },
                                placeholder = { Text(text = "填入导出端展示的校验码以验证完整性") },
                                singleLine = true,
                                modifier = Modifier.weight(3f),
                            )
                            OutlinedIconButton(
                                onClick = { showShaHistory = showShaHistory.not() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = "历史校验码",
                                )
                            }
                            OutlinedIconButton(
                                onClick = { showShaGuide = showShaGuide.not() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = "校验码使用说明",
                                )
                            }
                        }
                        if (showShaGuide) {
                            Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)) {
                                Text(text = "校验码使用说明")
                                Text(
                                    text = "• 校验码是迁移包内容的指纹：内容相同则校验码必然相同，并非随机编号",
                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                )
                                Text(
                                    text = "• 用法：把导出页展示的校验码发给接收方，导入前粘贴到上方输入框，即可验证传输过程中文件未损坏、未被篡改",
                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                )
                                Text(
                                    text = "• 何时相同：同一设备、相同勾选、备份未更新时重复导出，校验码保持一致",
                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                )
                                Text(
                                    text = "• 何时不同：重新备份、增减应用、应用升级后再导出会变化；不同设备导出不保证一致",
                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                )
                                Text(
                                    text = "• 历史记录仅保存在执行导出的那台设备上",
                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                )
                            }
                        }
                        if (showShaHistory && shaHistory.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)) {
                                Text(
                                    text = "点击任一记录即可填入上方校验码",
                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                )
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp),
                                    verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
                                ) {
                                    items(shaHistory) { record ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    checksumInput = record.sha
                                                    showShaHistory = false
                                                }
                                                .padding(vertical = SizeTokens.Level4),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentCopy,
                                                contentDescription = null,
                                                tint = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${DateUtil.formatTimestamp(record.time, "yyyy-MM-dd HH:mm")} · ${record.apps} 个应用",
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = record.sha,
                                                    color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }

    // 第一步：选择云端账号
    if (showCloudSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCloudSheet = false },
            sheetState = cloudSheetState,
        ) {
            TitleLargeText(
                text = stringResource(R.string.migration_select_cloud),
                modifier = Modifier.paddingHorizontal(SizeTokens.Level24),
            )
            if (clouds.isEmpty()) {
                Text(
                    text = stringResource(R.string.migration_no_cloud),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SizeTokens.Level24),
                    textAlign = TextAlign.Center,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            } else {
                clouds.forEach { cloud ->
                    Surface(onClick = {
                        showCloudSheet = false
                        scope.launch {
                            viewModel.loadRemotePackages(cloud.name)
                            showRemoteSheet = true
                        }
                    }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .paddingHorizontal(SizeTokens.Level24)
                                .padding(vertical = SizeTokens.Level12),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                        ) {
                            TitleLargeText(text = cloud.name, maxLines = 1)
                            Text(
                                text = "${cloud.host} (${cloud.type.name})",
                                color = ThemedColorSchemeKeyTokens.Outline.value,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            InnerBottomSpacer(innerPadding = PaddingValues(SizeTokens.Level8))
        }
    }

    // 第二步：选择该云端的迁移包（migration/ 目录下）
    if (showRemoteSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRemoteSheet = false },
            sheetState = remoteSheetState,
        ) {
            TitleLargeText(
                text = stringResource(R.string.migration_select_remote_package),
                modifier = Modifier.paddingHorizontal(SizeTokens.Level24),
            )
            if (remotePackages.isEmpty()) {
                Text(
                    text = stringResource(R.string.migration_no_remote_package),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SizeTokens.Level24),
                    textAlign = TextAlign.Center,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            } else {
                remotePackages.forEach { pkg ->
                    Surface(onClick = {
                        showRemoteSheet = false
                        scope.launch {
                            confirmImport(
                                viewModel.parseFromCloud(
                                    cloudName = pkg.cloudName,
                                    remotePath = pkg.remotePath,
                                    expectedSha256 = checksumInput.trim().takeIf { s -> s.isNotEmpty() },
                                    stageLabels = importStageLabels,
                                )
                            )
                        }
                    }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .paddingHorizontal(SizeTokens.Level24)
                                .padding(vertical = SizeTokens.Level12),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileDownload,
                                contentDescription = null,
                                tint = ThemedColorSchemeKeyTokens.Primary.value,
                                modifier = Modifier.size(SizeTokens.Level20),
                            )
                            Text(
                                text = pkg.fileName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            InnerBottomSpacer(innerPadding = PaddingValues(SizeTokens.Level8))
        }
    }
}
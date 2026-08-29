package com.xayah.feature.main.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.SortType
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.util.formatSize
import kotlinx.coroutines.Dispatchers
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Divider
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.ModalBottomSheet
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.RadioButtons
import com.xayah.core.ui.component.SearchBar
import com.xayah.core.ui.component.SecondaryTopBar
import com.xayah.core.ui.component.SortDirectionRow
import com.xayah.core.ui.component.Surface
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.DateUtil
import com.xayah.core.util.maybePopBackStack
import kotlinx.coroutines.launch
@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageDataMigrationExport(
    viewModel: DataMigrationExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val clipboard = LocalClipboardManager.current

    val allItems by viewModel.allItems.collectAsStateWithLifecycle()
    val userList by viewModel.userList.collectAsStateWithLifecycle()
    val userMap by viewModel.userMap.collectAsStateWithLifecycle()
    val userIndex by viewModel.userIndex.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortIndex by viewModel.sortIndex.collectAsStateWithLifecycle()
    val sortType by viewModel.sortType.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val lastSha256 by viewModel.lastSha256.collectAsStateWithLifecycle()
    val clouds by viewModel.clouds.collectAsStateWithLifecycle()
    val exportStages by viewModel.exportStages.collectAsStateWithLifecycle()
    val exportCurrentStage by viewModel.exportCurrentStage.collectAsStateWithLifecycle()
    val exportStageProgress by viewModel.exportStageProgress.collectAsStateWithLifecycle()

    // 阶段卡：Processing 时根据 currentStage 动态显示「第 X / N 步 + 当前段名（带 Shimmer）」
    val (stageTitle, stageDesc) = when (stage) {
        MigrationStage.Processing -> {
            val labels = exportStages
            val idx = exportCurrentStage.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
            val stepText = if (labels.isNotEmpty()) {
                context.getString(
                    R.string.migration_stage_segment_format,
                    idx + 1,
                    labels.size,
                )
            } else {
                context.getString(R.string.migration_stage_processing_title)
            }
            // description 给 Card 的 BodyLargeText（无 Shimmer，中性文案）；Shimmer 文字由 Card 内部根据 stages[currentStageIndex] 渲染
            stepText to context.getString(R.string.migration_stage_processing_desc)
        }
        MigrationStage.Success -> context.getString(R.string.migration_stage_success_title) to
            context.getString(R.string.migration_stage_success_desc)
        else -> context.getString(R.string.migration_stage_idle_title) to
            context.getString(R.string.migration_stage_idle_desc)
    }

    val exportStageLabels = remember(context) {
        listOf(
            context.getString(R.string.migration_stage_segment_validating),
            context.getString(R.string.migration_stage_segment_packing),
            context.getString(R.string.migration_stage_segment_hashing),
            context.getString(R.string.migration_stage_segment_uploading),
        )
    }

    val displayItems = remember(allItems, userList, userIndex, searchQuery, sortIndex, sortType) {
        viewModel.displayItems()
    }
    val totalCount = allItems.sumOf { it.versions.size }
    val selectedCount = selectedKeys.size

    var showSortSheet by remember { mutableStateOf(false) }
    val sortSheetState = rememberModalBottomSheetState()
    var showCloudSheet by remember { mutableStateOf(false) }
    val cloudSheetState = rememberModalBottomSheetState()
    var expandedKeys by remember { mutableStateOf(setOf<String>()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zstd")
    ) { uri ->
        uri?.let {
            // 先选好保存位置，再弹「加份保险」提示（引导用户自行用文件管理器打包备份目录）
            scope.launch {
                dialogState.open(
                    initialState = Unit,
                    title = context.getString(R.string.insurance_title),
                    dismissText = context.getString(R.string.insurance_skip),
                    confirmText = context.getString(R.string.insurance_confirm),
                ) { _ ->
                    Text(text = context.getString(R.string.insurance_desc))
                }.first
                // 导出含打包+压缩+复制大文件，必须在 IO 线程执行，否则主线程阻塞 → 卡顿/ANR
                viewModel.export(it, exportStageLabels)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.migration_export_failed))
        }
    }

    LaunchedEffect(success) {
        if (success) {
            snackbarHostState.showSnackbar(context.getString(R.string.migration_export_success))
            viewModel.consumeSuccess()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()).nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                SecondaryTopBar(
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
                    title = stringResource(R.string.export_backup),
                    subtitle = if (totalCount > 0 && stage == MigrationStage.Idle) "(${selectedCount}/${totalCount})" else null,
                    actions = {
                        // 导出中/完成后隐藏筛选、刷新、全选（这些操作在导出过程中无意义）
                        if (stage == MigrationStage.Idle) {
                            IconButton(icon = Icons.Outlined.FilterList) {
                                showSortSheet = true
                            }
                            IconButton(icon = Icons.Rounded.Refresh) {
                                scope.launch { viewModel.load() }
                            }
                            if (allItems.isNotEmpty()) {
                                TextButton(onClick = {
                                    if (selectedCount == totalCount) viewModel.unselectAll() else viewModel.selectAll()
                                }) {
                                    Text(
                                        text = stringResource(
                                            if (selectedCount == totalCount) R.string.unselect_all else R.string.select_all
                                        )
                                    )
                                }
                            }
                        }
                    },
                )
                // 选择阶段显示搜索与用户 Tab；导出中/完成后隐藏
                if (stage == MigrationStage.Idle) {
                    SearchBar(
                        modifier = Modifier
                            .paddingHorizontal(SizeTokens.Level16)
                            .paddingVertical(SizeTokens.Level8),
                        enabled = true,
                        placeholder = stringResource(R.string.search_bar_hint_packages),
                        onTextChange = viewModel::search,
                    )
                    if (userList.isNotEmpty()) {
                        UserTabs(
                            selected = userIndex,
                            userList = userList,
                            usersMap = userMap,
                            onTabClick = viewModel::setUser,
                        )
                    } else {
                        Divider(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SizeTokens.Level16),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
            ) {
                when (stage) {
                    MigrationStage.Idle -> {
                        Text(
                            text = stringResource(R.string.migration_selected_count, selectedCount),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                // 直接弹系统文件选择器选保存位置（保险弹窗在选好位置后弹出）
                                exportLauncher.launch("DataBackup_迁移包_${DateUtil.formatTimestamp(DateUtil.getTimestamp(), "yyyyMMdd_HHmmss")}.tar.zst")
                            },
                            enabled = selectedCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.export_backup))
                        }
                        // 导出到云端：选云端账号后直接打包上传（受保护版本勾选与本地导出完全一致）
                        OutlinedButton(
                            onClick = { showCloudSheet = true },
                            enabled = selectedCount > 0 && stage == MigrationStage.Idle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(SizeTokens.Level16),
                            )
                            Text(
                                text = stringResource(R.string.migration_export_to_cloud),
                                modifier = Modifier.padding(start = SizeTokens.Level8),
                            )
                        }
                    }

                    MigrationStage.Processing -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.migration_stage_processing_title))
                        }
                    }

                    MigrationStage.Success -> {
                        Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                            lastSha256?.let {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
                                ) {
                                    Text(
                                        text = "SHA-256：$it",
                                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = {
                                        clipboard.setText(AnnotatedString(it))
                                        scope.launch { snackbarHostState.showSnackbar("校验码已复制") }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = "复制校验码",
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = { navController.maybePopBackStack() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.migration_export_done))
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    ) { innerPadding ->
        if (stage != MigrationStage.Idle) {
            // 导出中/完成：整页只显示进度卡（仿处理页）。内容垂直居中,长内容溢出可滚
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                MigrationStageCard(
                    stage = stage,
                    title = stageTitle,
                    description = stageDesc,
                    stages = exportStages,
                    currentStageIndex = exportCurrentStage,
                    currentStageProgress = exportStageProgress,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                InnerTopSpacer(innerPadding = innerPadding)
            }
            when {
                isLoading -> {
                    item {
                        Text(
                            text = stringResource(R.string.loading),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SizeTokens.Level24),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                displayItems.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.migration_no_backup),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SizeTokens.Level24),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    items(items = displayItems, key = { it.key }) { item ->
                        val expanded = expandedKeys.contains(item.key)
                        val mainVersion = remember(item) { item.versions.firstOrNull { it.preserveId == 0L } }
                        val mainVersionKey = remember(item, mainVersion) { mainVersion?.let { item.versionFullKey(it) } }
                        ExportAppItemRow(
                            item = item,
                            checked = mainVersionKey != null && selectedKeys.contains(mainVersionKey),
                            expanded = expanded,
                            onToggleExpand = {
                                expandedKeys = if (expanded) expandedKeys - item.key else expandedKeys + item.key
                            },
                            onToggle = { checked -> mainVersionKey?.let { viewModel.toggleVersion(it, checked) } },
                        )
                        if (expanded) {
                            item.versions.filter { it.preserveId != 0L }.forEach { version ->
                                ExportVersionRow(
                                    version = version,
                                    timeText = DateUtil.formatPreserveTimestamp(version.preserveId),
                                    checked = selectedKeys.contains(item.versionFullKey(version)),
                                    onToggle = { checked ->
                                        viewModel.toggleVersion(item.versionFullKey(version), checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                InnerBottomSpacer(innerPadding = innerPadding)
            }
            }
        }
    }

    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState = sortSheetState,
        ) {
            SortDirectionRow(
                text = stringResource(R.string.sort),
                sortType = sortType,
                onClick = viewModel::toggleSortType,
            )
            RadioButtons(
                selected = sortIndex,
                items = stringArrayResource(R.array.backup_sort_type_items_apps).toList(),
                onSelect = viewModel::setSortIndex,
            )
        }
    }

    // 导出到云端：选择目标云端账号
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
                        scope.launch { viewModel.exportToCloud(cloud.name, exportStageLabels) }
                    }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .paddingHorizontal(SizeTokens.Level24)
                                .padding(vertical = SizeTokens.Level12),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                        ) {
                            TitleLargeText(text = cloud.name, maxLines = 1)
                            BodyMediumText(
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
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UserTabs(selected: Int, userList: List<UserInfo>, usersMap: Map<Int, Long>, onTabClick: (index: Int) -> Unit) {
    if (userList.isNotEmpty()) {
        // 防御：删除应用后 userList 会缩短，外部传入的 selected 若未同步校正，
        // TabRow 内部 tabPositions[selected] 将越界闪退（同恢复列表页），统一钳制。
        val safeSelected = selected.coerceIn(0, userList.size - 1)
        PrimaryScrollableTabRow(
            selectedTabIndex = safeSelected,
            edgePadding = SizeTokens.Level0,
            indicator = @Composable {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(safeSelected, matchContentSize = true),
                    shape = CircleShape
                )
            },
            divider = {
                Divider(modifier = Modifier.fillMaxWidth())
            }
        ) {
            userList.forEachIndexed { index, user ->
                Tab(
                    selected = safeSelected == index,
                    onClick = {
                        onTabClick(index)
                    },
                    text = {
                        BadgedBox(
                            modifier = Modifier.fillMaxSize(),
                            badge = {
                                if (usersMap.containsKey(user.id)) {
                                    Badge { Text(text = usersMap[user.id].toString()) }
                                }
                            }
                        ) {
                            Text(text = "${user.name} (${user.id})", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExportAppItemRow(
    item: DataMigrationExportItem,
    checked: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(onClick = { onToggle(!checked) }) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            PackageIconImage(packageName = item.packageName, size = SizeTokens.Level32)

            Column(modifier = Modifier.weight(1f)) {
                TitleLargeText(text = item.label.ifEmpty { stringResource(R.string.unknown) }, maxLines = 1)
                BodyMediumText(text = item.packageName, color = ThemedColorSchemeKeyTokens.Outline.value, maxLines = 1)
                if (item.hasPreserved) {
                    BodyMediumText(
                        text = stringResource(R.string.migration_preserved),
                        color = ThemedColorSchemeKeyTokens.YellowPrimary.value,
                        maxLines = 1,
                    )
                }
            }

            Text(
                text = item.totalSizeBytes.formatSize(),
                color = ThemedColorSchemeKeyTokens.Outline.value,
            )

            if (item.hasPreserved) {
                IconButton(icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore) {
                    onToggleExpand()
                }
            }

            Checkbox(
                checked = checked,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun ExportVersionRow(
    version: DataMigrationVersionItem,
    timeText: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = SizeTokens.Level68, end = SizeTokens.Level16, top = SizeTokens.Level8, bottom = SizeTokens.Level8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
    ) {
        Box(modifier = Modifier.height(SizeTokens.Level32), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = ThemedColorSchemeKeyTokens.YellowPrimary.value,
            )
            Text(
                text = version.preserveIndex.toString(),
                color = ThemedColorSchemeKeyTokens.YellowPrimary.value,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            BodyMediumText(
                text = timeText,
                color = ThemedColorSchemeKeyTokens.YellowPrimary.value,
                maxLines = 1,
            )
        }

        Text(
            text = version.sizeBytes.formatSize(),
            color = ThemedColorSchemeKeyTokens.Outline.value,
        )

        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
        )
    }
}

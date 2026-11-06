package com.xayah.feature.main.dashboard

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.CheckBox
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Section
import com.xayah.core.ui.component.select
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.ifEmptyEncodeURLWithSpace
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import kotlinx.coroutines.launch

@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageDataMigration(viewModel: DataMigrationViewModel = hiltViewModel()) {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val clouds by viewModel.clouds.collectAsStateWithLifecycle(initialValue = emptyList())
    // 一键恢复可选的三类内容，默认全选；每次打开入口都从全选开始，不记住上次的临时选择
    var includeApps by remember { mutableStateOf(true) }
    var includeFiles by remember { mutableStateOf(true) }
    var includeSystemData by remember { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.backup_and_migration),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24),
        ) {
            InnerTopSpacer(innerPadding = innerPadding)
            Section(title = stringResource(R.string.data_migration)) {
                Clickable(
                    title = stringResource(R.string.export_backup),
                    value = stringResource(R.string.export_backup_desc),
                    leadingIcon = Icons.Rounded.FileUpload,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    navController.navigateSingle(MainRoutes.DataMigrationExport.route)
                }
                Clickable(
                    title = stringResource(R.string.import_backup),
                    value = stringResource(R.string.import_backup_desc),
                    leadingIcon = Icons.Rounded.FileDownload,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    navController.navigateSingle(MainRoutes.DataMigrationImport.route)
                }
            }
            Section(title = stringResource(R.string.one_click_backup)) {
                Clickable(
                    title = stringResource(R.string.one_click_backup),
                    value = stringResource(R.string.one_click_backup_desc),
                    leadingIcon = Icons.Rounded.PlayArrow,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    // 全选应用+文件后进入备份设置页；应用备份完成后自动接续文件备份
                    viewModel.oneClickBackup(
                        onReady = {
                            navController.navigateSingle(MainRoutes.PackagesBackupProcessingGraph.getRoute(chainFileBackup = true))
                        },
                        onError = { reason ->
                            Toast.makeText(context, context.getString(R.string.one_click_error, reason), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            Section(title = stringResource(R.string.one_click_restore)) {
                Clickable(
                    title = stringResource(R.string.one_click_restore),
                    value = stringResource(R.string.one_click_restore_desc),
                    leadingIcon = Icons.Rounded.PlayArrow,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    scope.launch {
                        // 每次进入都从「三类全选」开始，不沿用上次的临时选择
                        includeApps = true
                        includeFiles = true
                        includeSystemData = true
                        val sources = buildList {
                            add(DialogRadioItem<String>(title = context.getString(R.string.local)))
                            clouds.forEach { add(DialogRadioItem<String>(title = it.name)) }
                        }
                        val (pickState, pickIndex) = dialogState.select(
                            title = context.getString(R.string.one_click_restore_source),
                            defIndex = 0,
                            items = sources,
                        )
                        if (pickState.isConfirm.not() || pickIndex !in sources.indices) return@launch
                        val cloudEntity = if (pickIndex == 0) null else clouds.getOrNull(pickIndex - 1)
                        val cloudName = cloudEntity?.name.orEmpty()
                        val backupDir = cloudEntity?.remote ?: context.localBackupSaveDir()

                        // 全选恢复会覆盖本机数据，进流程前必须明确确认一次
                        val (confirmState, _) = dialogState.open(
                            initialState = Unit,
                            title = context.getString(R.string.one_click_restore_confirm),
                            confirmText = context.getString(R.string.confirm),
                            dismissText = context.getString(R.string.cancel),
                        ) { _ ->
                            Column {
                                Text(text = context.getString(R.string.one_click_restore_confirm_desc))
                                CheckBox(
                                    checked = includeApps,
                                    text = context.getString(R.string.apps),
                                    onValueChange = { includeApps = it },
                                )
                                CheckBox(
                                    checked = includeFiles,
                                    text = context.getString(R.string.files),
                                    onValueChange = { includeFiles = it },
                                )
                                CheckBox(
                                    checked = includeSystemData,
                                    text = context.getString(R.string.system_data),
                                    onValueChange = { includeSystemData = it },
                                )
                            }
                        }
                        if (confirmState.isConfirm.not()) return@launch
                        if (includeApps.not() && includeFiles.not() && includeSystemData.not()) return@launch

                        // 系统数据只从云端恢复：列差异确认绝不自动跳过
                        val pending = if (includeSystemData) viewModel.prepareOneClickRestore(cloudName) else emptyList()
                        if (pending.isNotEmpty()) {
                            val gapCount = pending.sumOf { it.missingColumns.size }
                            val (gapState, _) = dialogState.open(
                                initialState = Unit,
                                title = context.getString(R.string.one_click_restore_gap),
                                confirmText = context.getString(R.string.confirm),
                                dismissText = context.getString(R.string.cancel),
                            ) { _ ->
                                Text(text = context.getString(R.string.one_click_restore_gap_desc, gapCount))
                            }
                            if (gapState.isConfirm) {
                                viewModel.confirmSystemDataPending(pending)
                            } else {
                                viewModel.cancelSystemData()
                                return@launch
                            }
                        }

                        val error = viewModel.finishOneClickRestore(cloudName, backupDir, includeApps, includeFiles)
                        if (error != null) {
                            Toast.makeText(context, context.getString(R.string.one_click_error, error), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        // 没勾应用就直接进文件恢复处理页，避免空的「应用恢复」流程卡住
                        val route = if (includeApps) {
                            MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                cloudName = cloudName.ifEmptyEncodeURLWithSpace(),
                                backupDir = backupDir.ifEmptyEncodeURLWithSpace(),
                                chainFileRestore = includeFiles,
                            )
                        } else {
                            MainRoutes.MediumRestoreProcessingGraph.getRoute(
                                cloudName = cloudName.ifEmptyEncodeURLWithSpace(),
                                backupDir = backupDir.ifEmptyEncodeURLWithSpace(),
                                skipSetup = true,
                            )
                        }
                        navController.navigateSingle(route)
                    }
                }
            }
            Section(title = stringResource(R.string.schedules)) {
                Clickable(
                    title = stringResource(R.string.schedules),
                    value = stringResource(R.string.schedules_desc),
                    leadingIcon = Icons.Rounded.Schedule,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    navController.navigateSingle(MainRoutes.Schedules.route)
                }
            }
            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }
}
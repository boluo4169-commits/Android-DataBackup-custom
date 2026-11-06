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
import androidx.compose.runtime.rememberCoroutineScope
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

                        // 全选恢复会覆盖本机数据：只留一句文字确认，三类内容改在恢复引导页里选/微调
                        val (confirmState, _) = dialogState.open(
                            initialState = Unit,
                            title = context.getString(R.string.one_click_restore_confirm),
                            confirmText = context.getString(R.string.confirm),
                            dismissText = context.getString(R.string.cancel),
                        ) { _ ->
                            Text(text = context.getString(R.string.one_click_restore_confirm_desc))
                        }
                        if (confirmState.isConfirm.not()) return@launch

                        val error = viewModel.finishOneClickRestore(cloudName, backupDir)
                        if (error != null) {
                            Toast.makeText(context, context.getString(R.string.one_click_error, error), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        navController.navigateSingle(
                            MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                cloudName = cloudName.ifEmptyEncodeURLWithSpace(),
                                backupDir = backupDir.ifEmptyEncodeURLWithSpace(),
                                chainFileRestore = true,
                            )
                        )
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
package com.xayah.feature.main.processing.packages.restore

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.datastore.KeyResetRestoreList
import com.xayah.core.datastore.saveRestoreUser
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.ui.component.CheckBox
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.component.select
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.decodeURL
import com.xayah.core.util.ifEmptyEncodeURLWithSpace
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.GetUsers
import com.xayah.feature.main.processing.ProcessingSetupScaffold
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import com.xayah.feature.main.system.SystemDataItem
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PagePackagesRestoreProcessingSetup(localNavController: NavHostController, viewModel: RestoreViewModelImpl) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val mainNavController = LocalNavController.current!!
    val appContext = LocalContext.current
    // 列差异确认弹窗要在点「继续」后、进入处理页之前弹出，因此在页面顶层取一次 dialogSlot
    val gapDialogState = LocalSlotScope.current!!.dialogSlot
    var continuing by remember { mutableStateOf(false) }
    val packages by viewModel.packages.collectAsStateWithLifecycle()
    val packagesSize by viewModel.packagesSize.collectAsStateWithLifecycle()
    val restoreUsers by viewModel.restoreUsers.collectAsStateWithLifecycle()

    LaunchedEffect(null) {
        viewModel.launchOnIO {
            viewModel.emitIntent(SetCloudEntity(""))
            viewModel.emitIntent(GetUsers)
            viewModel.emitIntent(UpdateApps)
        }
    }

    ProcessingSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.setup),
        actions = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level24)
                    .paddingVertical(SizeTokens.Level8),
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12, Alignment.End),
            ) {
                Button(
                    enabled = continuing.not(),
                    onClick = {
                        continuing = true
                        viewModel.launchOnIO {
                            try {
                                // 一键恢复：系统数据按勾选在「继续」时才执行，与备份侧时机一致。
                                // 列差异项必须在这里弹窗确认，确认/取消后才清缓存并进入处理页。
                                if (viewModel.chainFileRestore) {
                                    val pending = viewModel.prepareSystemDataRestore()
                                    if (pending.isNotEmpty()) {
                                        val gapCount = pending.sumOf { it.missingColumns.size }
                                        val (gapState, _) = gapDialogState.open(
                                            initialState = Unit,
                                            title = appContext.getString(R.string.one_click_restore_gap),
                                            confirmText = appContext.getString(R.string.confirm),
                                            dismissText = appContext.getString(R.string.cancel),
                                        ) { _ ->
                                            Text(text = appContext.getString(R.string.one_click_restore_gap_desc, gapCount))
                                        }
                                        if (gapState.isConfirm) {
                                            viewModel.confirmSystemDataPending(pending)
                                        } else {
                                            viewModel.cancelSystemDataPending()
                                        }
                                    }
                                }
                                viewModel.emitIntent(FinishSetup(navController = localNavController))
                            } finally {
                                continuing = false
                            }
                        }
                    }) {
                    Text(text = stringResource(id = R.string._continue))
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
        ) {
            Title(title = stringResource(id = R.string.storage)) {
                val interactionSource = remember { MutableInteractionSource() }
                Clickable(
                    title = stringResource(id = R.string.apps),
                    value = packagesSize,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                    interactionSource = interactionSource,
                    content = {
                        PackageIcons(modifier = Modifier.paddingTop(SizeTokens.Level8), packages = packages)
                    },
                    onClick = {
                        // 必须把当前备份源带给列表页，否则列表按默认空源过滤，会显示本地备份记录
                        // （表现为引导页显示云端应用、点进去却只剩本地的「暗区突围」）。
                        // 复用接续链路的规范化写法：SavedStateHandle 里的参数已被 Navigation decode，
                        // 直接拼回 route 会被路径中的 "/" 切碎（真机实测崩溃）。
                        mainNavController.navigateSingle(
                            MainRoutes.List.getRoute(
                                target = Target.Apps,
                                opType = OpType.RESTORE,
                                cloudName = viewModel.cloudNameArg.decodeURL().trim().ifEmptyEncodeURLWithSpace(),
                                backupDir = viewModel.backupDirArg.decodeURL().ifEmptyEncodeURLWithSpace(),
                                returnToSetup = true,
                            )
                        )
                    },
                )
                // 一键恢复：文件与系统数据两行仅在接续流程显示，与备份引导页对称，均可点进去微调
                if (viewModel.chainFileRestore) {
                    Clickable(
                        title = stringResource(id = R.string.files),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                        onClick = {
                            mainNavController.navigateSingle(
                                MainRoutes.List.getRoute(
                                    target = Target.Files,
                                    opType = OpType.RESTORE,
                                    cloudName = viewModel.cloudNameArg.decodeURL().trim().ifEmptyEncodeURLWithSpace(),
                                    backupDir = viewModel.backupDirArg.decodeURL().ifEmptyEncodeURLWithSpace(),
                                    returnToSetup = true,
                                )
                            )
                        },
                    )
                    val sdSelected by viewModel.systemDataSelected.collectAsStateWithLifecycle()
                    val sdState by viewModel.systemDataRestoreState.collectAsStateWithLifecycle()
                    Clickable(
                        title = stringResource(id = R.string.system_data),
                        value = when {
                            sdState.running -> stringResource(id = R.string.processing)
                            // 列差异待确认（弹窗由「继续」承载，这里只做状态提示）
                            sdState.pending.isNotEmpty() -> stringResource(id = R.string.one_click_restore_gap)
                            sdState.results.isNotEmpty() -> "${sdState.results.count { it.success }} / ${sdState.results.size}"
                            else -> "${sdSelected.size} / ${SystemDataItem.values().size}"
                        },
                        leadingIcon = Icons.Rounded.Storage,
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                        onClick = {
                            // 与「继续」的列差异弹窗共用同一个 dialogSlot，准备阶段禁止再开第二个，
                            // 否则后开的 content 会覆盖前一个，suspendCancellableCoroutine 永不 resume。
                            if (continuing.not()) {
                                // CheckBox 即时写入全局状态，「取消」时还原快照
                                val snapshot = sdSelected.toSet()
                                viewModel.launchOnIO {
                                    val (state, _) = gapDialogState.open(
                                        initialState = Unit,
                                        title = appContext.getString(R.string.system_data),
                                        confirmText = appContext.getString(R.string.confirm),
                                        dismissText = appContext.getString(R.string.cancel),
                                    ) { _ ->
                                        Column {
                                            SystemDataItem.values().forEach { item ->
                                                CheckBox(
                                                    checked = item in sdSelected,
                                                    text = appContext.getString(
                                                        when (item) {
                                                            SystemDataItem.Sms -> R.string.system_data_sms
                                                            SystemDataItem.CallLog -> R.string.system_data_call_log
                                                            SystemDataItem.Wifi -> R.string.system_data_wifi
                                                        }
                                                    ),
                                                    onValueChange = { checked ->
                                                        viewModel.setSystemDataItemSelected(item, checked)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    if (state.isConfirm.not()) viewModel.restoreSystemDataSelection(snapshot)
                                }
                            }
                        },
                    )
                }
            }
            Title(title = stringResource(id = R.string.settings)) {
                Clickable(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_history),
                    title = stringResource(id = R.string.restore_settings),
                ) {
                    localNavController.navigateSingle(MainRoutes.RestoreSettings.route)
                }
                val dialogState = LocalSlotScope.current!!.dialogSlot
                val context = LocalContext.current
                var currentIndex by remember { mutableIntStateOf(0) }
                LaunchedEffect(currentIndex) {
                    viewModel.launchOnIO {
                        var userId = -1
                        if (currentIndex != 0) userId = restoreUsers[currentIndex].title.toIntOrNull() ?: -1
                        context.saveRestoreUser(userId)
                    }
                }
                Selectable(
                    enabled = restoreUsers.size != 1,
                    title = stringResource(id = R.string.restore_user),
                    value = stringResource(id = R.string.restore_user_desc),
                    current = restoreUsers[currentIndex].title,
                ) {
                    viewModel.launchOnIO {
                        val (state, selectedIndex) = dialogState.select(
                            title = context.getString(R.string.restore_user),
                            defIndex = currentIndex,
                            items = restoreUsers
                        )
                        if (state.isConfirm) {
                            currentIndex = selectedIndex
                        }
                    }
                }
                Switchable(
                    key = KeyAutoScreenOff,
                    defValue = false,
                    title = stringResource(id = R.string.auto_screen_off),
                    checkedText = stringResource(id = R.string.auto_screen_off_desc),
                )
                Switchable(
                    key = KeyResetRestoreList,
                    defValue = false,
                    title = stringResource(id = R.string.reset_restore_list),
                    checkedText = stringResource(id = R.string.reset_restore_list_desc),
                )
            }
        }
    }
}

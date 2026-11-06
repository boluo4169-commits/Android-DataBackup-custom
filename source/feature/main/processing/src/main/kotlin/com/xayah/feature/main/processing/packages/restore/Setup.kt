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
                    onClick = {
                        // 一键恢复：系统数据按勾选在「继续」时才执行，与备份侧时机一致
                        if (viewModel.chainFileRestore) viewModel.startSystemDataRestore()
                        viewModel.emitIntentOnIO(FinishSetup(navController = localNavController))
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
                        mainNavController.navigateSingle(MainRoutes.List.getRoute(Target.Apps, OpType.RESTORE, returnToSetup = true))
                    },
                )
                // 一键恢复：文件与系统数据两行仅在接续流程显示，与备份引导页对称，均可点进去微调
                if (viewModel.chainFileRestore) {
                    Clickable(
                        title = stringResource(id = R.string.files),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                        onClick = {
                            mainNavController.navigateSingle(MainRoutes.List.getRoute(Target.Files, OpType.RESTORE, returnToSetup = true))
                        },
                    )
                    val sdSelected by viewModel.systemDataSelected.collectAsStateWithLifecycle()
                    val sdState by viewModel.systemDataRestoreState.collectAsStateWithLifecycle()
                    val sdDialogState = LocalSlotScope.current!!.dialogSlot
                    Clickable(
                        title = stringResource(id = R.string.system_data),
                        value = when {
                            sdState.running -> stringResource(id = R.string.processing)
                            // 列差异项绝不自动覆盖，提示用户到系统数据页手动确认
                            sdState.needsManualConfirm > 0 -> stringResource(id = R.string.one_click_restore_gap)
                            sdState.results.isNotEmpty() -> "${sdState.results.count { it.success }} / ${sdState.results.size}"
                            else -> "${sdSelected.size} / ${SystemDataItem.values().size}"
                        },
                        leadingIcon = Icons.Rounded.Storage,
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                        onClick = {
                            viewModel.launchOnIO {
                                sdDialogState.open(
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

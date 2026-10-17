package com.xayah.feature.main.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LabelOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NewLabel
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.ui.component.AnimatedModalDropdownMenu
import com.xayah.core.ui.component.Divider
import com.xayah.core.ui.component.DropdownMenuItem
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.ModalDropdownMenu
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.edit
import com.xayah.core.ui.component.select
import com.xayah.core.ui.model.DialogCheckBoxItem
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.libpickyou.ui.model.PickerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ListActions(
    viewModel: ListActionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ListActions(uiState, viewModel)
}

@Composable
internal fun ListActions(
    uiState: ListActionsUiState,
    viewModel: ListActionsViewModel,
) {
    if (uiState is ListActionsUiState.Success) {
        val context = LocalContext.current
        val target by remember(uiState) {
            mutableStateOf(
                when (uiState) {
                    is ListActionsUiState.Success.Apps -> Target.Apps
                    is ListActionsUiState.Success.Files -> Target.Files
                }
            )
        }

        FilterAction(viewModel::showFilterSheet)

        ListAction(
            target = target,
            opType = uiState.opType,
            selected = uiState.selected,
            total = uiState.total,
            defaultBackupAll = uiState.defaultBackupAll,
            labels = (uiState as? ListActionsUiState.Success.Apps)?.labels.orEmpty(),
            selectedLabels = (uiState as? ListActionsUiState.Success.Apps)?.selectedLabels.orEmpty(),
            viewModel = viewModel,
        )

        var moreExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            IconButton(icon = Icons.Rounded.MoreVert) {
                moreExpanded = true
            }
            ModalDropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false }
            ) {
                RefreshItem(enabled = uiState.isUpdating.not()) {
                    moreExpanded = false
                    viewModel.refresh()
                }

                when (target) {
                    Target.Apps -> {}

                    Target.Files -> {
                        if (uiState.opType == OpType.BACKUP) {
                            AddItem(enabled = uiState.isUpdating.not()) {
                                moreExpanded = false
                                PickYouLauncher(
                                    checkPermission = true,
                                    title = context.getString(R.string.select_target_directory),
                                    pickerType = PickerType.DIRECTORY,
                                    permissionType = PermissionType.ROOT,
                                ).apply {
                                    launch(context) {
                                        viewModel.addFiles(listOf(it))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterAction(onFilter: () -> Unit) {
    IconButton(icon = Icons.Outlined.FilterList, onClick = onFilter)
}

@Composable
private fun ListAction(
    target: Target,
    opType: OpType,
    selected: Long,
    total: Long,
    defaultBackupAll: Boolean,
    labels: List<LabelEntity>,
    selectedLabels: List<String>,
    viewModel: ListActionsViewModel,
) {
    var checkListExpanded by remember { mutableStateOf(false) }
    var checkListSelectedExpanded by remember { mutableStateOf(false) }
    // 必须在外层 remember：菜单内容在收起时会整体离开组合，若作用域建在菜单里，
    // 点击后 checkListExpanded() 立刻销毁该作用域，launch 出来的对话框协程会被取消
    // （表现为点「添加标签」毫无反应，而同步弹出的「新建标签」正常）。
    val labelDialogScope = rememberCoroutineScope()
    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        IconButton(icon = Icons.Rounded.Checklist) {
            checkListSelectedExpanded = false
            checkListExpanded = true
        }
        AnimatedModalDropdownMenu(
            targetState = checkListSelectedExpanded,
            expanded = checkListExpanded,
            onDismissRequest = { checkListExpanded = false }
        ) {
            if (it.not()) {
                SelectAllItem(selected = selected, total = total) {
                    checkListExpanded = false
                    viewModel.selectAll()
                }
                UnselectAllItem {
                    checkListExpanded = false
                    viewModel.unselectAll()
                }
                // "默认全选"开关：仅备份页显示(恢复页不触发自动预选)。
                // 放在「全部不选」下方,整行可点切换。
                if (opType == OpType.BACKUP) {
                    DefaultBackupAllItem(
                        checked = defaultBackupAll,
                        onCheckedChange = { viewModel.setDefaultBackupAll(it) },
                    )
                }
                ReverseItem {
                    checkListExpanded = false
                    viewModel.reverseAll()
                }
                Divider(modifier = Modifier.fillMaxWidth())
                ForSelectedItem {
                    checkListSelectedExpanded = true
                }
            } else {
                    when (target) {
                        Target.Apps -> {
                            AppsListActions(
                                enabled = selected != 0L,
                                opType = opType,
                                labels = labels,
                                selectedLabels = selectedLabels,
                                checkListExpanded = { checkListExpanded = false },
                                onBlockSelected = viewModel::blockSelected,
                                onSelectDataItems = viewModel::showDataItemsSheet,
                                onDeleteSelected = viewModel::deleteSelected,
                                onApplyLabels = viewModel::applyLabelsToSelected,
                                onRemoveLabels = viewModel::removeLabelsFromSelected,
                                dialogScope = labelDialogScope,
                            )
                        }

                        Target.Files -> {
                            FilesListActions(
                                enabled = selected != 0L,
                                opType = opType,
                                checkListExpanded = { checkListExpanded = false },
                                onBlockSelected = viewModel::blockSelected,
                                onDeleteSelected = viewModel::deleteSelected,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun AppsListActions(
    enabled: Boolean,
    opType: OpType,
    labels: List<LabelEntity>,
    selectedLabels: List<String>,
    checkListExpanded: () -> Unit,
    onBlockSelected: () -> Unit,
    onSelectDataItems: () -> Unit,
    onDeleteSelected: () -> Unit,
    onApplyLabels: (List<String>) -> Unit,
    onRemoveLabels: (List<String>) -> Unit,
    dialogScope: CoroutineScope,
) {
    val context = LocalContext.current
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scope = dialogScope

    // 批量打标：勾选本身就是多选动作，用户不必再进每个应用的详情页
    val pickExistingLabel = {
        checkListExpanded()
        if (labels.isEmpty()) {
            scope.launch {
                dialogState.confirm(
                    title = context.getString(R.string.add_label),
                    text = context.getString(R.string.no_labels_available),
                )
            }
        } else {
            scope.launch {
                val items = labels.map { DialogCheckBoxItem(enum = it.label, title = it.label) }
                val (state, selection) = dialogState.select(
                    title = context.getString(R.string.add_label),
                    def = items.map { false },
                    items = items,
                )
                if (state.isConfirm) {
                    val picked = items.filterIndexed { index, _ -> selection[index] }.mapNotNull { it.enum }
                    if (picked.isNotEmpty()) onApplyLabels(picked)
                }
            }
        }
        Unit
    }
    val createLabel = {
        checkListExpanded()
        dialogState.edit(
            title = context.getString(R.string.new_label),
            label = context.getString(R.string.label),
            onConfirm = { if (it.isNotBlank()) onApplyLabels(listOf(it.trim())) },
        )
        Unit
    }
    // 只列出确实挂在这批已选应用上的标签；一个都没有时给提示，而不是弹一个空列表
    val pickLabelToRemove = {
        checkListExpanded()
        if (selectedLabels.isEmpty()) {
            scope.launch {
                dialogState.confirm(
                    title = context.getString(R.string.remove_label),
                    text = context.getString(R.string.no_labels_on_selected),
                )
            }
        } else {
            scope.launch {
                val items = selectedLabels.map { DialogCheckBoxItem(enum = it, title = it) }
                val (state, selection) = dialogState.select(
                    title = context.getString(R.string.remove_label),
                    def = items.map { false },
                    items = items,
                )
                if (state.isConfirm) {
                    val picked = items.filterIndexed { index, _ -> selection[index] }.mapNotNull { it.enum }
                    if (picked.isNotEmpty()) onRemoveLabels(picked)
                }
            }
        }
        Unit
    }

    when (opType) {
        OpType.BACKUP -> {
            LabelActions(
                enabled = enabled,
                onPickExisting = { pickExistingLabel() },
                onCreate = { createLabel() },
                onRemove = { pickLabelToRemove() },
            )
            BlockItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_add_to_blacklist)
                ) {
                    onBlockSelected()
                }
            }
            DetailedDataItem(enabled) {
                checkListExpanded()
                onSelectDataItems()
            }
        }

        OpType.RESTORE -> {
            LabelActions(
                enabled = enabled,
                onPickExisting = { pickExistingLabel() },
                onCreate = { createLabel() },
                onRemove = { pickLabelToRemove() },
            )
            DeleteItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_delete)
                ) {
                    onDeleteSelected()
                }
            }
            DetailedDataItem(enabled) {
                checkListExpanded()
                onSelectDataItems()
            }
        }
    }
}

@Composable
private fun FilesListActions(
    enabled: Boolean,
    opType: OpType,
    checkListExpanded: () -> Unit,
    onBlockSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val context = LocalContext.current
    val dialogState = LocalSlotScope.current!!.dialogSlot

    when (opType) {
        OpType.BACKUP -> {
            BlockItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_add_to_blacklist)
                ) {
                    onBlockSelected()
                }
            }
            DeleteItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_delete)
                ) {
                    onDeleteSelected()
                }
            }
        }

        OpType.RESTORE -> {
            DeleteItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_delete)
                ) {
                    onDeleteSelected()
                }
            }
        }
    }
}

/**
 * "选择全部"菜单项：leadingIcon 反映**真实选中状态**（全选 / 部分选中 / 未选 三态）。
 * 原实现写死 Icons.Rounded.CheckBox，图标恒为「已勾选」，即使一个应用都没选也亮着，
 * 且紧邻的「默认全选」用的是真状态图标（check_circle / radio_button_unchecked），
 * 两者并排更容易被误读成「已经全部选中了」。
 */
@Composable
private fun SelectAllItem(selected: Long, total: Long, onClick: () -> Unit) {
    val leadingIcon = when {
        selected <= 0L || total <= 0L -> Icons.Rounded.CheckBoxOutlineBlank
        selected >= total -> Icons.Rounded.CheckBox
        else -> Icons.Rounded.IndeterminateCheckBox
    }
    DropdownMenuItem(
        text = stringResource(id = R.string.select_all),
        leadingIcon = leadingIcon,
        onClick = onClick,
    )
}

/**
 * "全部不选"菜单项：改用中性**动作**图标（Deselect）。
 * 原来用空心勾选框，与上面「选择全部」的三态图标并列时会被读成"当前未选中"的状态指示。
 */
@Composable
private fun UnselectAllItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.unselect_all),
        leadingIcon = Icons.Rounded.Deselect,
        onClick = onClick,
    )
}

/**
 * "默认全选"开关菜单项：DropdownMenuItem 整行可点切换。
 * 替代原弹窗 Switch 设计,点行内任意位置触发 onCheckedChange。
 * leadingIcon 根据 checked 状态切换(check_circle vs check_circle_outline),
 * 视觉上给用户「开/关」的明确反馈。
 */
@Composable
private fun DefaultBackupAllItem(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.default_backup_all),
        leadingIcon = if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
private fun ReverseItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.reverse_selection),
        leadingIcon = Icons.Rounded.RestartAlt,
        onClick = onClick,
    )
}

@Composable
private fun ForSelectedItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.for_selected),
        leadingIcon = Icons.Rounded.MoreVert,
        trailingIcon = Icons.Rounded.ArrowRight,
        onClick = onClick,
    )
}

@Composable
private fun LabelActions(
    enabled: Boolean,
    onPickExisting: () -> Unit,
    onCreate: () -> Unit,
    onRemove: () -> Unit,
) {
    // 三项常驻：任何一项都不随数据状态隐藏，否则用户会以为功能没做。
    // 不可用的状态交给点击后的提示说明。
    AddLabelItem(enabled, onPickExisting)
    NewLabelItem(enabled, onCreate)
    RemoveLabelItem(enabled, onRemove)
}

@Composable
private fun AddLabelItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.add_label),
        leadingIcon = Icons.Rounded.Label,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun RemoveLabelItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.remove_label),
        leadingIcon = Icons.Rounded.LabelOff,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun NewLabelItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.new_label),
        leadingIcon = Icons.Rounded.NewLabel,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun BlockItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.block),
        leadingIcon = Icons.Rounded.Block,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun DetailedDataItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.detailed_data_items),
        leadingIcon = Icons.Rounded.Rule,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun DeleteItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.delete),
        leadingIcon = Icons.Rounded.Delete,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun RefreshItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.refresh),
        leadingIcon = Icons.Rounded.Refresh,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun AddItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.add),
        leadingIcon = Icons.Rounded.Add,
        onClick = onClick,
        enabled = enabled,
    )
}

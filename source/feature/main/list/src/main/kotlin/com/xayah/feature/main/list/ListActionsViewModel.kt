package com.xayah.feature.main.list

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.App
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.launchOnDefault
import com.xayah.core.util.withMainContext
import com.xayah.core.work.WorkManagerInitializer
import com.xayah.feature.main.list.ListActionsUiState.Loading
import com.xayah.feature.main.list.ListActionsUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ListActionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
) : ViewModel() {
    private val target: Target = Target.valueOf(savedStateHandle.get<String>(MainRoutes.ARG_TARGET)!!.decodeURL().trim())
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE)?.decodeURL()?.trim())
    private val cloudName: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
    private val backupDir: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE)?.decodeURL()?.trim() ?: ""

    val uiState: StateFlow<ListActionsUiState> = when (target) {
        Target.Apps -> combine(
            listDataRepo.getListData(),
            listDataRepo.getAppList(),
            listDataRepo.defaultBackupAll,
            // 标签与关联合成一个流：combine 上限 5 个，这里还要留位置给「已选集合」
            combine(labelsRepo.getLabelsFlow(), labelsRepo.getAppRefsFlow()) { labels, refs -> labels to refs },
            // 已选集合取全库（恢复页取当前备份源），与顶部计数同口径。
            // 不能用 appList：那是筛选后的可见集合，会漏掉被搜索/筛选隐藏的已选应用。
            when (opType) {
                OpType.RESTORE -> appsRepo.getActivatedAppsFlow(opType, cloudName, backupDir)
                else -> appsRepo.getActivatedAppsFlow(opType)
            },
        ) { lData, aList, defaultBackupAll, labelsAndRefs, selectedApps ->
            val listData = lData.castTo<ListData.Apps>()
            val (labels, refs) = labelsAndRefs
            val selectedKeys = selectedApps.map { AppKey(it.packageName, it.userId, it.preserveId) }
            val selectedKeySet = selectedKeys.toSet()
            Success.Apps(
                opType = opType,
                selected = listData.selected,
                total = listData.total,
                isUpdating = listData.isUpdating,
                appList = aList,
                defaultBackupAll = defaultBackupAll,
                labels = labels,
                selectedKeys = selectedKeys,
                // 「移除标签」只列出确实挂在这批已选应用上的标签
                selectedLabels = labels
                    .filter { label -> refs.any { it.label == label.label && AppKey(it.packageName, it.userId, it.preserveId) in selectedKeySet } }
                    .map { it.label },
            )
        }

        Target.Files -> combine(
            listDataRepo.getListData(),
            listDataRepo.getFileList(),
            listDataRepo.defaultBackupAll,
        ) { lData, fList, defaultBackupAll ->
            val listData = lData.castTo<ListData.Files>()
            Success.Files(
                opType = opType,
                selected = listData.selected,
                total = listData.total,
                isUpdating = listData.isUpdating,
                fileList = fList,
                defaultBackupAll = defaultBackupAll,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun refresh() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    when (opType) {
                        OpType.BACKUP -> {
                            WorkManagerInitializer.fullInitializeAndUpdateApps(context)
                        }

                        OpType.RESTORE -> {
                            WorkManagerInitializer.loadAppBackups(context, cloudName, backupDir)
                        }
                    }
                }

                is Success.Files -> {
                    when (opType) {
                        OpType.BACKUP -> {
                            WorkManagerInitializer.fastInitializeAndUpdateFiles(context)
                        }

                        OpType.RESTORE -> {
                            WorkManagerInitializer.loadFileBackups(context, cloudName, backupDir)
                        }
                    }
                }

                else -> {}
            }
        }
    }

    fun showFilterSheet() {
        viewModelScope.launchOnDefault {
            listDataRepo.setShowFilterSheet(true)
        }
    }

    fun selectAll() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    // 与 activateAllForBackup 同口径：批量选择跳过系统应用（单个手动勾选不受影响），
                    // 否则「加载系统应用」开着时一次全选就是几百个系统应用。
                    appsRepo.selectAll(state.appList.filter { it.isSystemApp.not() }.map { it.id })
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.selectAll(state.fileList.map { it.id })
                }

                else -> {}
            }

        }
    }

    fun unselectAll() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    appsRepo.unselectAll(state.appList.map { it.id })
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.unselectAll(state.fileList.map { it.id })
                }

                else -> {}
            }

        }
    }

    fun reverseAll() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    appsRepo.reverseAll(state.appList.map { it.id })
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.reverseAll(state.fileList.map { it.id })
                }

                else -> {}
            }

        }
    }

    fun blockSelected() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    appsRepo.blockSelected(state.appList.filter { it.selected }.map { it.id })
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.blockSelected(state.fileList.filter { it.selected }.map { it.id })
                }

                else -> {}
            }

        }
    }

    /**
     * 给当前勾选的应用批量打标签。
     *
     * 刻意不改动勾选状态：用户打完标签通常紧接着就要备份，这里顺手清空选择就变成
     * 「做一件事必须破坏另一件事」——正是此前「备份一个应用把整批勾选冲掉」的老问题。
     * 同一批重复打标是幂等的（关联表主键 upsert）。
     */
    fun applyLabelsToSelected(labels: List<String>) {
        viewModelScope.launchOnDefault {
            val state = uiState.value as? Success.Apps ?: return@launchOnDefault
            val keys = state.selectedKeys
            if (keys.isEmpty() || labels.isEmpty()) return@launchOnDefault
            val refs = labels.flatMap { label ->
                keys.map { key ->
                    LabelAppCrossRefEntity(
                        label = label,
                        packageName = key.packageName,
                        userId = key.userId,
                        preserveId = key.preserveId,
                    )
                }
            }
            labelsRepo.attachLabels(labels = labels, refs = refs)
            withMainContext {
                Toast.makeText(
                    context,
                    context.getString(R.string.label_applied, keys.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 从当前勾选的应用上批量移除标签。只解除这批关联，标签本身保留——别的应用可能还在用。
     * 同样不改动勾选状态。
     */
    fun removeLabelsFromSelected(labels: List<String>) {
        viewModelScope.launchOnDefault {
            val state = uiState.value as? Success.Apps ?: return@launchOnDefault
            val keys = state.selectedKeys
            if (keys.isEmpty() || labels.isEmpty()) return@launchOnDefault
            val refs = labels.flatMap { label ->
                keys.map { key ->
                    LabelAppCrossRefEntity(
                        label = label,
                        packageName = key.packageName,
                        userId = key.userId,
                        preserveId = key.preserveId,
                    )
                }
            }
            labelsRepo.detachLabels(refs)
            withMainContext {
                Toast.makeText(
                    context,
                    context.getString(R.string.label_removed, keys.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun showDataItemsSheet() {
        viewModelScope.launchOnDefault {
            listDataRepo.setShowDataItemsSheet(true)
        }
    }

    fun deleteSelected() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    appsRepo.deleteSelected(state.appList.filter { it.selected }.map { it.id })
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    when (opType) {
                        OpType.BACKUP -> {
                            filesRepo.delete(state.fileList.filter { it.selected }.map { it.id })
                        }

                        OpType.RESTORE -> {
                            filesRepo.deleteSelected(state.fileList.filter { it.selected }.map { it.id })
                        }
                    }
                }

                else -> {}
            }

        }
    }

    fun addFiles(pathList: List<String>) {
        viewModelScope.launchOnDefault {
            filesRepo.addFiles(pathList)
        }
    }

    fun setDefaultBackupAll(value: Boolean) {
        viewModelScope.launchOnDefault {
            listDataRepo.setDefaultBackupAll(value)
        }
    }
}

sealed interface ListActionsUiState {
    data object Loading : ListActionsUiState
    sealed class Success(
        open val opType: OpType,
        open val selected: Long,
        open val total: Long,
        open val isUpdating: Boolean,
        open val defaultBackupAll: Boolean,
    ) : ListActionsUiState {
        data class Apps(
            override val opType: OpType,
            override val selected: Long,
            override val total: Long,
            override val isUpdating: Boolean,
            override val defaultBackupAll: Boolean,
            val appList: List<App>,
            val labels: List<LabelEntity>,
            val selectedKeys: List<AppKey>,
            val selectedLabels: List<String>,
        ) : Success(opType, selected, total, isUpdating, defaultBackupAll)

        data class Files(
            override val opType: OpType,
            override val selected: Long,
            override val total: Long,
            override val isUpdating: Boolean,
            override val defaultBackupAll: Boolean,
            val fileList: List<File>,
        ) : Success(opType, selected, total, isUpdating, defaultBackupAll)
    }
}

/** 已选应用的身份（标签关联表主键去标签部分），与列表是否可见无关。 */
data class AppKey(
    val packageName: String,
    val userId: Int,
    val preserveId: Long,
)

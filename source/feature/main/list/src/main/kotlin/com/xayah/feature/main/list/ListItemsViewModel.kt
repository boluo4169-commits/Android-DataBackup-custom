package com.xayah.feature.main.list

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.datastore.readListShowLabels
import com.xayah.core.model.App
import com.xayah.core.model.DataState
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.AppKey
import com.xayah.core.model.util.buildAppLabelMap
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.launchOnDefault
import com.xayah.feature.main.list.ListItemsUiState.Loading
import com.xayah.feature.main.list.ListItemsUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    listDataRepo: ListDataRepo,
    private val labelsRepo: LabelsRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
) : ViewModel() {
    private val target: Target = Target.valueOf(savedStateHandle.get<String>(MainRoutes.ARG_TARGET)!!.decodeURL().trim())
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE)?.decodeURL()?.trim())

    /**
     * 标签展示数据独立于主列表流，原因：
     * - 开关默认关，关闭时完全不订阅标签表，不付额外 Room 查询与聚合开销；
     * - 任一路查询抛异常时降级为空标签，绝不让可选的展示项拖垮应用列表；
     * - 全量聚合在 Default 线程，不占主线程；
     * - StateFlow 带空 map 初值，主列表不必等标签查询首帧即可显示。
     *
     * 必须用全量 `getAppRefsFlow()`，不能用 `ListDataRepo.labelAppRefs`（那是按当前筛选标签
     * 过滤后的，筛选开启时只剩被选中的标签，无法展示「这个应用打了哪些标签」）。
     */
    private val labelMap: StateFlow<Map<AppKey, List<String>>> = context.readListShowLabels()
        // DataStore 自身异常时按「开关关」降级；且任何 preference 写入都会让 DataStore 发射，
        // 去重可避免无关设置变更反复取消、重建标签订阅。
        .catch { emit(false) }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(emptyMap())
            } else {
                combine(
                    labelsRepo.getAppRefsFlow(),
                    labelsRepo.getLabelsFlow(),
                ) { refs, labels -> buildAppLabelMap(refs, labels) }
                    .flowOn(Dispatchers.Default)
                    .catch { emit(emptyMap()) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyMap(),
            started = SharingStarted.WhileSubscribed(5_000),
        )

    val uiState: StateFlow<ListItemsUiState> = when (target) {
        Target.Apps -> combine(
            listDataRepo.getAppList(),
            labelMap,
        ) { apps, labels ->
            Success.Apps(
                opType = opType,
                appList = apps,
                labelMap = labels,
            )
        }

        Target.Files -> listDataRepo.getFileList().map {
            Success.Files(
                opType = opType,
                fileList = it,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun onSelectedChanged(id: Long, selected: Boolean) {
        viewModelScope.launchOnDefault {
            when (target) {
                Target.Apps -> appsRepo.selectApp(id, selected)
                Target.Files -> filesRepo.selectFile(id, selected)
            }
        }
    }

    fun onChangeFlag(id: Long, flag: Int) {
        viewModelScope.launchOnDefault {
            when (flag) {
                PackageEntity.FLAG_APK -> {
                    appsRepo.selectDataItems(
                        id = id,
                        apk = DataState.NotSelected,
                        user = DataState.Selected,
                        userDe = DataState.Selected,
                        data = DataState.Selected,
                        obb = DataState.Selected,
                        media = DataState.Selected,
                    )
                }

                PackageEntity.FLAG_ALL -> {
                    appsRepo.selectDataItems(
                        id = id,
                        apk = DataState.Selected,
                        user = DataState.NotSelected,
                        userDe = DataState.NotSelected,
                        data = DataState.NotSelected,
                        obb = DataState.NotSelected,
                        media = DataState.NotSelected,
                    )
                }

                else -> {
                    appsRepo.selectDataItems(
                        id = id,
                        apk = DataState.Selected,
                        user = DataState.Selected,
                        userDe = DataState.Selected,
                        data = DataState.Selected,
                        obb = DataState.Selected,
                        media = DataState.Selected,
                    )
                }
            }
        }
    }
}

sealed interface ListItemsUiState {
    data object Loading : ListItemsUiState
    sealed class Success(
        open val opType: OpType,
    ) : ListItemsUiState {
        data class Apps(
            override val opType: OpType,
            val appList: List<App>,
            val labelMap: Map<AppKey, List<String>> = emptyMap(),
        ) : Success(opType)

        data class Files(
            override val opType: OpType,
            val fileList: List<File>,
        ) : Success(opType)
    }
}

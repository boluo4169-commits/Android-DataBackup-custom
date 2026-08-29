package com.xayah.feature.main.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.ui.route.MainRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DataMigrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
) : ViewModel() {
    /**
     * 一键备份：全选应用 + 全选「文件备份」目录，进入备份设置页。
     * 路由带 chainFileBackup 标记：应用备份完成后自动接续文件备份（文件待备份列表在文件备份处理页可见）。
     * onReady 切回主线程（navigateSingle 只能主线程调）。
     */
    fun oneClickBackup(onReady: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                appsRepo.activateAllForBackup()
                filesRepo.activateAllForBackup()
            }
            withContext(Dispatchers.Main) {
                onReady()
            }
        }
    }
}

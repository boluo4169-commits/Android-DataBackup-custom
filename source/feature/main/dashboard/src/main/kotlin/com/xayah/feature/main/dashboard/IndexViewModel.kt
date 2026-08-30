package com.xayah.feature.main.dashboard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.datastore.readLastBackupTime
import com.xayah.core.datastore.readLastUpdateNotifyVersion
import com.xayah.core.datastore.saveLastUpdateNotifyVersion
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.network.model.Release
import com.xayah.core.network.retrofit.GitHubRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.LogUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.toBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class IndexUiState(
    val latestRelease: Release? = null,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object Update : IndexUiIntent()
    data class ToBrowser(val context: Context, val url: String) : IndexUiIntent()
}

// 应用版本 tag 形如 "v3.7.2"；配套工具等其他 release（如 companion-v1.0）不匹配
private val appVersionTag = Regex("^v\\d")

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directoryRepo: DirectoryRepository,
    private val githubRepo: GitHubRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(latestRelease = null)) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Update -> {
                // 目录统计走 root IPC，root service bind 偶发永不回调时会长时间挂起；
                // 绝不能排在更新检查前面堵死整条链 —— 改为后台并行执行，失败只留日志
                launchOnIO {
                    runCatching { directoryRepo.updateSelected() }
                        .onFailure { LogUtil.log { "IndexViewModel" to "updateSelected failed: ${it.message}" } }
                }

                runCatching {
                    // 只在应用版本（tag 以 v 开头，如 v3.6.6）中找最新版；
                    // 排除配套工具等其他 release（如 companion-v1.0），避免手机端收到无关更新通知
                    // 国内直连 api.github.com 经常被重置，失败重试一次；再失败只留日志（角标消失属于静默降级）
                    val releases = runCatching { githubRepo.getReleases() }
                        .recoverCatching { githubRepo.getReleases() }
                        .onFailure { LogUtil.log { "IndexViewModel" to "Update check failed: ${it.message}" } }
                        .getOrDefault(emptyList())
                    // 注意：String.matches() 是「整串匹配」，不是「查找」。
                    // 用 matches(Regex("^v\\d+")) 匹配 "v3.7.2" 会失败（\d+ 吃掉 3 后还剩 ".7.2"），
                    // 导致所有 release 被过滤掉、更新提示永远不出现 —— 这里必须用 containsMatchIn
                    val release = releases.firstOrNull { appVersionTag.containsMatchIn(it.tagName) }
                    LogUtil.log { "IndexViewModel" to "update check: size=${releases.size}, matched=${release?.tagName ?: "null"}" }
                    // tag 形如 "v3.0.0"，versionName 形如 "3.0.0"，去掉 v 前缀后对比
                    if (release != null && release.tagName.removePrefix("v") != BuildConfigUtil.VERSION_NAME) {
                        emitState(state.copy(latestRelease = release))

                        // 发现新版本时发一次系统通知（每个版本只发一次；顶栏角标仍常驻）。
                        // 之前只有角标，用户极易错过——「收不到更新推送」的反馈即源于此
                        val notified = context.readLastUpdateNotifyVersion().first()
                        if (notified != release.tagName) {
                            context.saveLastUpdateNotifyVersion(release.tagName)
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.assets.firstOrNull { it.url.contains(BuildConfigUtil.FLAVOR_feature) && it.url.contains(BuildConfigUtil.FLAVOR_abi) }?.url ?: release.url))
                            val builder = NotificationUtil.getProgressNotificationBuilder(context).setContentIntent(
                                PendingIntent.getActivity(context, 0, browserIntent, PendingIntent.FLAG_IMMUTABLE)
                            )
                            NotificationUtil.notify(context = context, builder = builder, title = context.getString(R.string.update_available), content = release.name, ongoing = false)
                            LogUtil.log { "IndexViewModel" to "update check: notification posted." }
                        }
                    } else {
                        emitState(state.copy(latestRelease = null))
                    }
                }.onFailure {
                    // 外层兜底：之前异常在这里被静默吞掉，排查时无任何痕迹
                    LogUtil.log { "IndexViewModel" to "Update check crashed: ${it.message}" }
                }
            }

            is IndexUiIntent.ToBrowser -> {
                runCatching { intent.context.toBrowser(intent.url) }.onFailure { emitEffect(IndexUiEffect.ShowSnackbar(message = context.getString(R.string.no_browser))) }
            }
        }
    }

    private val _lastBackupTime: Flow<Long> = context.readLastBackupTime().flowOnIO()
    val lastBackupTimeState: StateFlow<Long> = _lastBackupTime.stateInScope(0)

    private val _directory: Flow<DirectoryEntity?> = directoryRepo.querySelectedByDirectoryTypeFlow().flowOnIO()
    val directoryState: StateFlow<DirectoryEntity?> = _directory.stateInScope(null)
}

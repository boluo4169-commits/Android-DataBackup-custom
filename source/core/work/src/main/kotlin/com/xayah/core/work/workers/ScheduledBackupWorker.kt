package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.ScheduleRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.ScheduleScope
import com.xayah.core.model.database.ScheduleEntity
import com.xayah.core.model.includeApps
import com.xayah.core.model.includeFiles
import com.xayah.core.service.medium.backup.ProcessingServiceProxyLocalImpl as MediumBackupProxy
import com.xayah.core.service.packages.backup.ProcessingServiceProxyLocalImpl as PackagesBackupProxy
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.withLog
import com.xayah.core.work.R
import com.xayah.core.work.WorkManagerInitializer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 定时备份执行器。两种模式：
 * - 计划模式（默认）：按 scheduleId 读库执行，无论成败都自我重排下一次（否则计划静默死掉）。
 * - 一键模式（KEY_ONE_SHOT）：范围由 inputData 指定，不读库、不重排。
 * 执行条件（充电/电量/非计费网络）由 WorkManager Constraints 表达，条件不满足时系统自动顺延。
 * 与手动备份串行：检测到处理中任务时 Result.retry()（走退避策略）。
 */
@HiltWorker
internal class ScheduledBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val scheduleRepo: ScheduleRepository,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val taskRepo: TaskRepository,
    private val packagesBackupProxy: PackagesBackupProxy,
    private val mediumBackupProxy: MediumBackupProxy,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)
        var scope: ScheduleScope
        var schedule: ScheduleEntity? = null
        if (oneShot) {
            scope = inputData.getString(KEY_SCOPE)?.let { runCatching { ScheduleScope.valueOf(it) }.getOrNull() }
                ?: ScheduleScope.APPS_ALL_FILES
        } else {
            val scheduleId = inputData.getLong(KEY_SCHEDULE_ID, -1L)
            if (scheduleId < 0L) return Result.failure()
            schedule = scheduleRepo.query(scheduleId) ?: return Result.failure()
            if (schedule.enabled.not()) return Result.failure()
            scope = schedule.scope
        }
        // 与手动备份/其他计划串行：退避重试，不丢任务
        if (taskRepo.hasProcessingTask()) return Result.retry()

        val result = runCatching {
            if (scope.includeApps) appsRepo.activateAllForBackup()
            if (scope.includeFiles) filesRepo.activateAllForBackup()

            if (scope.includeApps) {
                packagesBackupProxy.initialize()
                packagesBackupProxy.preprocessing()
                packagesBackupProxy.processing()
                packagesBackupProxy.postProcessing()
                packagesBackupProxy.destroyService(true)
            }
            if (scope.includeFiles) {
                mediumBackupProxy.initialize()
                mediumBackupProxy.preprocessing()
                mediumBackupProxy.processing()
                mediumBackupProxy.postProcessing()
                mediumBackupProxy.destroyService(true)
            }
        }.withLog()

        // 计划模式：无条件重排下一次（schedule.nextTriggerAt 已被 markTriggered 刷新）
        if (oneShot.not()) {
            scheduleRepo.markTriggered(schedule!!.id)
            scheduleRepo.query(schedule.id)?.let { WorkManagerInitializer.scheduleBackup(appContext, it) }
        }

        val title = if (result.isSuccess) appContext.getString(R.string.schedule_done) else appContext.getString(R.string.schedule_failed)
        val content = schedule?.name ?: appContext.getString(R.string.one_click_backup)
        val builder = NotificationUtil.getProgressNotificationBuilder(appContext)
        NotificationUtil.notify(
            context = appContext,
            builder = builder,
            title = title,
            content = content,
            ongoing = false,
        )

        return if (result.isSuccess) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_SCHEDULE_ID = "scheduleId"
        const val KEY_ONE_SHOT = "oneShot"
        const val KEY_SCOPE = "scope"
    }
}

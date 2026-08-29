package com.xayah.core.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.xayah.core.data.repository.FAST_INIT_AND_UPDATE_APPS_WORK_NAME
import com.xayah.core.data.repository.FAST_INIT_AND_UPDATE_FILES_WORK_NAME
import com.xayah.core.data.repository.FULL_INIT_AND_UPDATE_APPS_WORK_NAME
import com.xayah.core.data.repository.FULL_INIT_WORK_NAME
import com.xayah.core.data.repository.LOAD_APP_BACKUPS_WORK_NAME
import com.xayah.core.data.repository.LOAD_FILE_BACKUPS_WORK_NAME
import com.xayah.core.model.ScheduleScope
import com.xayah.core.model.database.ScheduleEntity
import com.xayah.core.work.workers.AppsFastInitWorker
import com.xayah.core.work.workers.AppsFastUpdateWorker
import com.xayah.core.work.workers.AppsInitWorker
import com.xayah.core.work.workers.AppsLoadWorker
import com.xayah.core.work.workers.AppsUpdateWorker
import com.xayah.core.work.workers.FilesLoadWorker
import com.xayah.core.work.workers.FilesUpdateWorker
import com.xayah.core.work.workers.ScheduledBackupWorker
import java.util.concurrent.TimeUnit

object WorkManagerInitializer {
    /**
     * Fully initialize at app startup
     */
    fun fullInitialize(context: Context, regular: Boolean = true) {
        WorkManager.getInstance(context)
            .beginUniqueWork(FULL_INIT_WORK_NAME, ExistingWorkPolicy.KEEP, AppsInitWorker.buildRequest())
            .then(AppsUpdateWorker.buildRequest(regular))
            .then(FilesUpdateWorker.buildRequest())
            .then(AppsLoadWorker.buildRequest(null))
            .then(FilesLoadWorker.buildRequest(null))
            .enqueue()
    }

    /**
     * Fully initialize, update apps
     */
    fun fullInitializeAndUpdateApps(context: Context, regular: Boolean = false) {
        WorkManager.getInstance(context)
            .beginUniqueWork(FULL_INIT_AND_UPDATE_APPS_WORK_NAME, ExistingWorkPolicy.KEEP, AppsInitWorker.buildRequest())
            .then(AppsUpdateWorker.buildRequest(regular))
            .enqueue()
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps and update newly installed apps
     */
    fun fastInitializeAndUpdateApps(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(FAST_INIT_AND_UPDATE_APPS_WORK_NAME, ExistingWorkPolicy.KEEP, AppsFastInitWorker.buildRequest())
            .then(AppsFastUpdateWorker.buildRequest())
            .enqueue()
    }

    fun fastInitializeAndUpdateFiles(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(FAST_INIT_AND_UPDATE_FILES_WORK_NAME, ExistingWorkPolicy.KEEP, FilesUpdateWorker.buildRequest())
    }

    fun loadAppBackups(context: Context, cloudName: String, backupDir: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(LOAD_APP_BACKUPS_WORK_NAME, ExistingWorkPolicy.KEEP, AppsLoadWorker.buildRequest(cloudName))
    }

    fun loadFileBackups(context: Context, cloudName: String, backupDir: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(LOAD_FILE_BACKUPS_WORK_NAME, ExistingWorkPolicy.KEEP, FilesLoadWorker.buildRequest(null))
    }

    // -----------------------------------------定时备份-----------------------------------------
    // 计划语义 = OneTimeWorkRequest + Worker 执行后自我重排（见 ScheduledBackupWorker）。
    // 调用方负责保证 schedule.nextTriggerAt 有效（由 ScheduleRepository.computeNext 计算并持久化）。

    private const val SCHEDULE_WORK_PREFIX = "scheduled_backup_"
    private const val ONE_CLICK_BACKUP_WORK_NAME = "one_click_backup"

    fun scheduleBackup(context: Context, schedule: ScheduleEntity, immediate: Boolean = false) {
        val now = System.currentTimeMillis()
        val delay = if (immediate) 0L else (schedule.nextTriggerAt - now).coerceAtLeast(0L)
        val constraints = Constraints.Builder()
            .setRequiresCharging(schedule.requireCharging)
            .setRequiresBatteryNotLow(schedule.requireBatteryNotLow)
            .setRequiredNetworkType(if (schedule.requireUnmetered) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .setInputData(workDataOf(ScheduledBackupWorker.KEY_SCHEDULE_ID to schedule.id))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SCHEDULE_WORK_PREFIX + schedule.id, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSchedule(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(SCHEDULE_WORK_PREFIX + id)
    }

    fun runOneClickBackup(context: Context, scope: ScheduleScope) {
        val request = OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .setInputData(
                workDataOf(
                    ScheduledBackupWorker.KEY_ONE_SHOT to true,
                    ScheduledBackupWorker.KEY_SCOPE to scope.name,
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_CLICK_BACKUP_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 启动对账：App 冷启动时把所有启用中的计划重新入队（force-stop 后 WorkManager 不会自行唤醒，
     * REPLACE 策略天然幂等防重复）；已禁用的顺带取消残留任务。
     */
    fun reconcileSchedules(context: Context, schedules: List<ScheduleEntity>) {
        schedules.forEach { schedule ->
            if (schedule.enabled) scheduleBackup(context, schedule) else cancelSchedule(context, schedule.id)
        }
    }
}

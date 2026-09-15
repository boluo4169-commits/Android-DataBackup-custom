package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.INPUT_DATA_KEY_CLOUD_NAME
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.ForegroundProgressThrottler
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsLoadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(appContext) }
    private var mNotificationInfo: ForegroundInfo? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (mNotificationInfo == null) {
            mNotificationInfo = NotificationUtil.createForegroundInfo(
                appContext,
                mNotificationBuilder,
                appContext.getString(R.string.loading_backups),
                ""
            )
        }

        return mNotificationInfo!!
    }

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        val cloudName = inputData.getString(INPUT_DATA_KEY_CLOUD_NAME)
        val throttler = ForegroundProgressThrottler()
        appsRepo.load(cloudName) { cur, max, content ->
            // 逐包刷新前台通知会把主线程占满（实测 47 个应用 291 次 notify 全在主线程），这里限速，最后一条必发
            if (throttler.shouldEmit(cur, max)) {
                mNotificationInfo = NotificationUtil.createForegroundInfo(
                    appContext,
                    mNotificationBuilder,
                    appContext.getString(R.string.loading_backups),
                    content,
                    max,
                    cur
                )
                setForegroundSafely(
                    mNotificationInfo!!
                )
            }
        }
        Result.success()
    }

    companion object {
        fun buildRequest(cloudName: String?) = OneTimeWorkRequestBuilder<AppsLoadWorker>()
            .setInputData(
                workDataOf(
                    INPUT_DATA_KEY_CLOUD_NAME to cloudName,
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}

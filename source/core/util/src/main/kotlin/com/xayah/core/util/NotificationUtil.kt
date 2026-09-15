package com.xayah.core.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.Settings.EXTRA_APP_PACKAGE
import android.provider.Settings.EXTRA_CHANNEL_ID
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo


object NotificationUtil {
    private const val ForegroundServiceChannelId = "ForegroundServiceChannel"
    private const val ForegroundServiceChannelName = "ForegroundService"
    private const val ForegroundServiceChannelDesc = "For foreground service"
    private var progressNotificationId = 0

    fun checkPermission(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun requestPermissions(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(context.getActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val intent = Intent()
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(EXTRA_APP_PACKAGE, context.packageName)
                intent.putExtra(EXTRA_CHANNEL_ID, context.applicationInfo.uid)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.grant_ntfy_perm_manually), Toast.LENGTH_SHORT).show()
            }
        } else {
            runCatching {
                val intent = Intent().apply {
                    setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setData(Uri.parse("package:${context.packageName}"))
                }
                context.startActivity(intent)
            }
        }
    }

    fun getForegroundNotification(context: Context) = run {
        val pendingIntent: PendingIntent = context.packageManager.getLaunchIntentForPackage(context.packageName).let { intent ->
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        createChannelIfNecessary(context)
        NotificationCompat.Builder(context, ForegroundServiceChannelId).setContentIntent(pendingIntent).build()
    }

    fun getProgressNotificationBuilder(context: Context) =
        NotificationCompat.Builder(context, ForegroundServiceChannelId).setSmallIcon(R.mipmap.ic_launcher)

    fun notify(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        content: String,
        max: Int = 0,
        progress: Int = 0,
        indeterminate: Boolean = false,
        ongoing: Boolean = true,
    ) {
        builder.setContentTitle(title).setContentText(content).setProgress(max, progress, indeterminate).setOngoing(ongoing)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(progressNotificationId, builder.build())
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(progressNotificationId)
    }


    private fun createChannelIfNecessary(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ForegroundServiceChannelId,
                ForegroundServiceChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ForegroundServiceChannelDesc
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createForegroundInfo(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        content: String,
        max: Int = 0,
        progress: Int = 0,
        indeterminate: Boolean = false,
        ongoing: Boolean = true,
    ): ForegroundInfo {
        createChannelIfNecessary(context)
        val notification = builder.setContentTitle(title).setContentText(content).setProgress(max, progress, indeterminate).setOngoing(ongoing)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(progressNotificationId, notification.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(progressNotificationId, notification.build())
        }
    }

    fun createForegroundInfo(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        content: String,
        ongoing: Boolean = true,
    ): ForegroundInfo {
        createChannelIfNecessary(context)
        val notification = builder.setContentTitle(title).setContentText(content).setOngoing(ongoing)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(progressNotificationId, notification.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(progressNotificationId, notification.build())
        }
    }
}

/**
 * 前台进度通知节流器。
 *
 * WorkManager 的 [androidx.work.CoroutineWorker.setForeground] 最终会在**主线程**执行
 * NotificationManager.notify()：每条通知都要构造 Notification 对象、发起一次同步 Binder 调用，
 * 再由 system_server 重建通知（ColorOS 等 ROM 每条还要额外做数次 fixAppIcon 修复）。
 *
 * 实测（OPD2413 / Android 16 / 47 个第三方应用）：逐包回调 setForeground 在 0.79 秒内产生了
 * **291 次 notify，全部落在主线程 tid=5057**，速率约 366 次/秒。应用数量上百时主线程会被连续
 * 占满直至触发 ANR，这就是「应用多了扫描就卡」的直接原因。
 *
 * 这里按固定间隔合帧：间隔内的中间进度直接丢弃，首尾两条保证发出（保证用户看到开始与结束）。
 *
 * @param minIntervalMs 两次通知之间的最小间隔。
 * @param clock 时间源，默认 [SystemClock.elapsedRealtime]（单调时钟，不受系统时间调整影响）。
 *   暴露出来是为了能在纯 JVM 单测里注入可控时钟——见 ForegroundProgressThrottlerTest。
 */
class ForegroundProgressThrottler(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** null 表示「本次运行尚未发出过」。用哨兵而不是 0，避免时间源起点恰好为 0 时首条被误吞。 */
    private var lastEmitAtMs: Long? = null

    /**
     * @param isLast 本次回调是否为最后一个进度（最后一条必定发出，避免停留在旧进度上）。
     */
    @Synchronized
    fun shouldEmit(isLast: Boolean = false): Boolean {
        val now = clock()
        val last = lastEmitAtMs
        return if (isLast || last == null || now - last >= minIntervalMs) {
            lastEmitAtMs = now
            true
        } else {
            false
        }
    }

    /**
     * 便捷重载，直接吃 Repo 的进度回调。
     *
     * 注意：AppsRepo / FilesRepo 内部一律用 `forEachIndexed { index, _ -> }` 回调，
     * 即 **cur 是 0-based**，取值范围 0 … max-1。这里按 0-based 判定是否到达末尾。
     */
    fun shouldEmit(cur: Int, max: Int): Boolean = shouldEmit(isLast = max > 0 && cur >= max - 1)

    companion object {
        /**
         * 250ms：肉眼已足够跟得上进度（≈4 帧/秒），同时把 notify 次数压到原先的约 1/10 量级。
         */
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }
}

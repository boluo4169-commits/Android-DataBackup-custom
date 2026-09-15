package com.xayah.core.work.workers

import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo

/**
 * [CoroutineWorker.setForeground] 的安全包装。
 *
 * App 处于后台时，系统会拒绝 `startForegroundService()`
 * （`android.app.ForegroundServiceStartNotAllowedException`，Android 12+ 对后台启动前台服务的限制），
 * 该异常会顺着 [CoroutineWorker.doWork] 冒泡，让整个 Worker 以 failure 收场，业务逻辑被中断。
 *
 * 实测（OPD2413 / Android 16 / v3.12.0，后台冷启动）：
 * `AppsLoadWorker` 因此直接 FAILED，恢复页的备份列表加载不出来：
 * ```
 * WM-WorkerWrapper: Work [ tags={...AppsLoadWorker} ] failed because it threw an exception
 * android.app.ForegroundServiceStartNotAllowedException:
 *   startForegroundService() not allowed due to mAllowStartForeground false
 * ```
 *
 * 前台通知只是进度展示，属于附属功能，不该让业务失败——所以这里吞掉异常继续执行。
 * 代价：首次 setForeground 失败时 Worker 不具备前台优先级，长任务仍可能被系统限流或杀死，
 * 这是可接受的降级（数据可重跑，总好过整个任务直接失败）。
 *
 * 7 个 Worker 共有 7 处调用，容错与说明集中在此，避免各写一遍。
 */
internal suspend fun CoroutineWorker.setForegroundSafely(info: ForegroundInfo) {
    runCatching { setForeground(info) }
}

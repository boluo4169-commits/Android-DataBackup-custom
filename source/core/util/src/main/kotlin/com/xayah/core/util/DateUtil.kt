package com.xayah.core.util

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object DateUtil {
    private const val SECOND_IN_MILLIS: Long = 1000
    private const val MINUTE_IN_MILLIS = SECOND_IN_MILLIS * 60
    private const val HOUR_IN_MILLIS = MINUTE_IN_MILLIS * 60
    private const val DAY_IN_MILLIS = HOUR_IN_MILLIS * 24
    private const val WEEK_IN_MILLIS = DAY_IN_MILLIS * 7
    private const val PATTERN_DEFAULT = "yyyy-MM-dd HH:mm:ss.SSS"
    const val PATTERN_YMD = "yyyy-MM-dd"
    const val PATTERN_YMD_HMS = "yyyy-MM-dd HH:mm:ss"
    const val PATTERN_FINISH = "MMMM dd yyyy HH:mm a"

    fun getTimestamp(): Long = System.currentTimeMillis()

    /**
     * 生成可读的 preserveId（yyyyMMddHHmmss，如 20250813234526），用于备份目录名。
     * 秒级精度，避免同一分钟内多次备份导致目录名冲突。
     */
    fun getPreserveTimestamp(): Long = runCatching {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date()).toLong()
    }.getOrDefault(getTimestamp())

    /**
     * 把 preserveId 格式化成可读时间。兼容 yyyyMMddHHmmss（秒）、yyyyMMddHHmm（分钟，旧）和毫秒时间戳（旧备份）。
     */
    fun formatPreserveTimestamp(preserveId: Long): String = runCatching {
        val s = preserveId.toString()
        when (s.length) {
            14 -> "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}:${s.substring(12, 14)}"
            12 -> "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}"
            else -> formatTimestamp(preserveId, PATTERN_YMD_HMS)
        }
    }.getOrDefault(preserveId.toString())

    /**
     * Format given [timestamp] as date.
     */
    fun formatTimestamp(timestamp: Long?, pattern: String = PATTERN_DEFAULT): String = runCatching {
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp!!))
    }.getOrDefault("Unknown")

    /**
     * @see <a href="https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:packages/apps/Messaging/src/com/android/messaging/util/Dates.java;l=238">packages/apps/Messaging/src/com/android/messaging/util/Dates.java</a>
     */
    fun getShortRelativeTimeSpanString(context: Context, time1: Long, time2: Long): String {
        val duration = abs(time2 - time1)
        val resId: Int
        val count: Long
        if (duration < MINUTE_IN_MILLIS) {
            count = duration / SECOND_IN_MILLIS
            resId = R.plurals.num_seconds_ago
        } else if (duration < HOUR_IN_MILLIS) {
            count = duration / MINUTE_IN_MILLIS
            resId = R.plurals.num_minutes_ago
        } else if (duration < DAY_IN_MILLIS) {
            count = duration / HOUR_IN_MILLIS
            resId = R.plurals.num_hours_ago
        } else if (duration < WEEK_IN_MILLIS) {
            count = getNumberOfDaysPassed(time1, time2)
            resId = R.plurals.num_days_ago
        } else {
            // Although we won't be showing a time, there is a bug on some devices that use
            // the passed in context. On these devices, passing in a {@code null} context
            // here will generate an NPE. See b/5657035.
            return DateUtils.formatDateRange(
                context, time1, time2,
                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_ABBREV_RELATIVE
            )
        }
        val format = context.resources.getQuantityString(resId, count.toInt())
        return String.format(format, count)
    }

    fun getNumberOfDaysPassed(date1: Long, date2: Long): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getNumberOfDaysPassedApi26(date1, date2)
        } else {
            getNumberOfDaysPassedApi24(date1, date2)
        }
    }

    private fun getNumberOfDaysPassedApi24(date1: Long, date2: Long) = TimeUnit.MILLISECONDS.toDays(abs(date2 - date1))

    /**
     * @see <a href="https://github.com/ArrowOS/android_packages_apps_Messaging/blob/6e561f4b715764f292ae8d774af6a090578e83d8/src/com/android/messaging/util/Dates.java#L271">packages/apps/Messaging/src/com/android/messaging/util/Dates.java</a>
     */
    @TargetApi(Build.VERSION_CODES.O)
    private fun getNumberOfDaysPassedApi26(date1: Long, date2: Long): Long {
        val dateTime1 = LocalDateTime.ofInstant(Instant.ofEpochMilli(date1), ZoneId.systemDefault())
        val dateTime2 = LocalDateTime.ofInstant(Instant.ofEpochMilli(date2), ZoneId.systemDefault())
        return abs(ChronoUnit.DAYS.between(dateTime2, dateTime1))
    }
}

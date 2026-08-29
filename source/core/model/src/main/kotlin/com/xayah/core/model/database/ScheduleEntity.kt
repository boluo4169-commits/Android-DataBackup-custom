package com.xayah.core.model.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xayah.core.model.ScheduleFrequency
import com.xayah.core.model.ScheduleScope
import com.xayah.core.model.includeApps
import com.xayah.core.model.includeFiles
import kotlinx.serialization.Serializable

/**
 * 定时备份计划。调度语义见 ScheduleRepository.computeNext（OneTimeWorkRequest + 执行后自我重排，
 * 不用 PeriodicWorkRequest：月长不一，周期任务表达不了「每月 X 日」的日历语义）。
 */
@Serializable
@Entity
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val frequency: ScheduleFrequency,
    val hour: Int,
    val minute: Int,
    /** 1=周一 .. 7=周日，WEEKLY 时有效 */
    val dayOfWeek: Int? = null,
    /** 1-31，MONTHLY 时有效；目标月缺日（如 31 号遇 2 月）自动取当月最后一天 */
    val dayOfMonth: Int? = null,
    val scope: ScheduleScope,
    val requireCharging: Boolean = false,
    val requireBatteryNotLow: Boolean = false,
    val requireUnmetered: Boolean = false,
    val enabled: Boolean = true,
    val lastTriggeredAt: Long = 0,
    /** 冗余存下一次触发时间（epoch ms），UI 直接显示、调度直接用 */
    val nextTriggerAt: Long = 0,
    val createdAt: Long = 0,
) {
    val includeApps: Boolean get() = scope.includeApps
    val includeFiles: Boolean get() = scope.includeFiles
}

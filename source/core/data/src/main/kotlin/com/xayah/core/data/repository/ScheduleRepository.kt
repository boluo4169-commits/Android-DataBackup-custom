package com.xayah.core.data.repository

import com.xayah.core.database.dao.ScheduleDao
import com.xayah.core.model.ScheduleFrequency
import com.xayah.core.model.database.ScheduleEntity
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

class ScheduleRepository @Inject constructor(
    private val scheduleDao: ScheduleDao,
) {
    fun queryAllFlow(): Flow<List<ScheduleEntity>> = scheduleDao.queryAllFlow()

    suspend fun query(id: Long): ScheduleEntity? = scheduleDao.query(id)

    suspend fun queryEnabled(): List<ScheduleEntity> = scheduleDao.queryEnabled()

    suspend fun upsert(schedule: ScheduleEntity): Long = scheduleDao.upsert(schedule)

    suspend fun delete(id: Long) = scheduleDao.delete(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = scheduleDao.setEnabled(id, enabled)

    suspend fun deleteAllBy(ids: List<Long>) = ids.forEach { scheduleDao.delete(it) }

    /**
     * 记录本次触发时间并计算下一次触发时间（持久化）。
     * 计划语义 = OneTimeWorkRequest + 执行后自我重排，重排必须无条件进行（失败也要排下一次，否则计划死掉）。
     */
    suspend fun markTriggered(id: Long, now: LocalDateTime = LocalDateTime.now()) {
        val schedule = scheduleDao.query(id) ?: return
        scheduleDao.updateTriggerTime(
            id = id,
            lastTriggeredAt = System.currentTimeMillis(),
            nextTriggerAt = computeNext(schedule, now),
        )
    }

    /**
     * 计算下一次触发时间（纯函数，可测）。
     * MONTHLY 的 dayOfMonth 落在缺日月份（29/30/31）时收敛到当月最后一天。
     */
    fun computeNext(schedule: ScheduleEntity, now: LocalDateTime = LocalDateTime.now()): Long {
        val hour = schedule.hour
        val minute = schedule.minute
        val next: LocalDateTime = when (schedule.frequency) {
            ScheduleFrequency.DAILY -> {
                var t = now.toLocalDate().atTime(hour, minute)
                if (t.isAfter(now).not()) t = t.plusDays(1)
                t
            }

            ScheduleFrequency.WEEKLY -> {
                val target = DayOfWeek.of((schedule.dayOfWeek ?: 1).coerceIn(1, 7))
                var t = now.toLocalDate().atTime(hour, minute)
                while (t.dayOfWeek != target || t.isAfter(now).not()) t = t.plusDays(1)
                t
            }

            ScheduleFrequency.MONTHLY -> {
                var ym = YearMonth.from(now)
                var t = ym.atDay(minOf(schedule.dayOfMonth ?: 1, ym.lengthOfMonth())).atTime(hour, minute)
                while (t.isAfter(now).not()) {
                    ym = ym.plusMonths(1)
                    t = ym.atDay(minOf(schedule.dayOfMonth ?: 1, ym.lengthOfMonth())).atTime(hour, minute)
                }
                t
            }
        }
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

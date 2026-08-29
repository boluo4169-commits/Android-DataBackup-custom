package com.xayah.feature.main.settings.schedules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.ScheduleRepository
import com.xayah.core.model.database.ScheduleEntity
import com.xayah.core.util.launchOnDefault
import com.xayah.core.work.WorkManagerInitializer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SchedulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleRepo: ScheduleRepository,
) : ViewModel() {
    val schedules: StateFlow<List<ScheduleEntity>> = scheduleRepo.queryAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新增/编辑：计算并持久化 nextTriggerAt 后入队（REPLACE 幂等）。 */
    fun save(schedule: ScheduleEntity) {
        viewModelScope.launchOnDefault {
            val next = scheduleRepo.computeNext(schedule)
            val saved = schedule.copy(id = schedule.id, nextTriggerAt = next)
            val id = scheduleRepo.upsert(saved)
            WorkManagerInitializer.scheduleBackup(context, saved.copy(id = if (saved.id == 0L) id else saved.id))
        }
    }

    fun delete(schedule: ScheduleEntity) {
        viewModelScope.launchOnDefault {
            scheduleRepo.delete(schedule.id)
            WorkManagerInitializer.cancelSchedule(context, schedule.id)
        }
    }

    /** 启停：停用取消任务；重新启用按当前时刻重算下一次，避免拿陈旧的 past 时间点立即误触发。 */
    fun setEnabled(schedule: ScheduleEntity, enabled: Boolean) {
        viewModelScope.launchOnDefault {
            scheduleRepo.setEnabled(schedule.id, enabled)
            if (enabled) {
                val fresh = scheduleRepo.query(schedule.id) ?: return@launchOnDefault
                val next = scheduleRepo.computeNext(fresh)
                scheduleRepo.upsert(fresh.copy(nextTriggerAt = next))
                WorkManagerInitializer.scheduleBackup(context, fresh.copy(nextTriggerAt = next))
            } else {
                WorkManagerInitializer.cancelSchedule(context, schedule.id)
            }
        }
    }

    /** 立即执行一次：延迟 0 入队；Worker 内部仍会照常重排下一次。 */
    fun runOnce(schedule: ScheduleEntity) {
        viewModelScope.launchOnDefault {
            WorkManagerInitializer.scheduleBackup(context, schedule, immediate = true)
        }
    }
}

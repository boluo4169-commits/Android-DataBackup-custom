package com.xayah.feature.main.processing

import com.xayah.feature.main.system.SystemDataCoordinator
import com.xayah.feature.main.system.SystemDataItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 完成页直接读取「一键流程」的系统数据结果，不为此改动任何 ViewModel / 服务代理 / Room 模型。
 * [SystemDataCoordinator] 是 @Singleton，结果在引导页 → 处理页切换后依然存活。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemDataEntryPoint {
    fun coordinator(): SystemDataCoordinator
}

fun SystemDataItem.labelResId(): Int = when (this) {
    SystemDataItem.Sms -> R.string.system_data_sms
    SystemDataItem.CallLog -> R.string.system_data_call_log
    SystemDataItem.Wifi -> R.string.system_data_wifi
}

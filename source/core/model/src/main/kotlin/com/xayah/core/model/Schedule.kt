package com.xayah.core.model

enum class ScheduleFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
}

enum class ScheduleScope {
    /** 全部应用 */
    APPS_ALL,

    /** 全部应用 + 文件备份 */
    APPS_ALL_FILES,

    /** 仅文件备份 */
    FILES_ONLY,
}

val ScheduleScope.includeApps: Boolean get() = this != ScheduleScope.FILES_ONLY
val ScheduleScope.includeFiles: Boolean get() = this != ScheduleScope.APPS_ALL

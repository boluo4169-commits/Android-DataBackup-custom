package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.data.R
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.model.OpType
import com.xayah.core.model.UserInfo
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UsersRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val rootService: RemoteRootService,
    private val appsDao: PackageDao,
) {
    fun getUsers(opType: OpType): Flow<List<UserInfo>> = when (opType) {
        OpType.BACKUP -> flow {
            emit(
                rootService.getUsers().map { UserInfo(it.id, it.name) }
            )
        }.flowOn(defaultDispatcher)

        // B: 备份记录里的 userId 可能已不存在于系统（如「炼妖壶」创建的空间被删除后，
        // 备份目录仍在盘上、记录被孤儿清扫保留）。给这类用户加「已删除」标记，让恢复列表
        // 可见且可分辨——否则它既"看不到"又仍被勾选、仍参与恢复，用户无从察觉。
        // systemUserIds 为空 = 读取系统用户失败（root service 未就绪），此时不做判定，避免误标全部用户。
        OpType.RESTORE -> appsDao.queryUserIdsFlow(opType).map { userIds ->
            val systemUserIds = rootService.getUsers().map { it.id }.toSet()
            userIds.map { u ->
                val deleted = systemUserIds.isNotEmpty() && u !in systemUserIds
                UserInfo(u, context.getString(if (deleted) R.string.user_deleted else R.string.user))
            }
        }.flowOn(defaultDispatcher)
    }

    fun getUsersMap(opType: OpType, cloud: String, backupDir: String): Flow<Map<Int, Long>> = appsDao.countUsersMapFlow(opType = opType, blocked = false, cloud = cloud, backupDir = backupDir)
}

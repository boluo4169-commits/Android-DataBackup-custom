package com.xayah.core.data.repository

import com.xayah.core.database.dao.LabelDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class LabelsRepo @Inject constructor(
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val labelDao: LabelDao,
) {
    // 列表页 / 筛选面板 / 详情页会各自订阅关联表；Room Flow 每次订阅都查一次库，
    // 且数据库无关写入也会重复发射。先做去重 + 切到默认调度器，避免重复刷新列表。
    // （进一步共享成 StateFlow 需要 Repo 单例与作用域设计，等真机能验证标签即时刷新后再做。）
    fun getLabelsFlow(): Flow<List<LabelEntity>> = labelDao.queryLabelsFlow().distinctUntilChanged().flowOn(defaultDispatcher)
    fun getAppRefsFlow(): Flow<List<LabelAppCrossRefEntity>> = labelDao.queryAppRefsFlow().distinctUntilChanged().flowOn(defaultDispatcher)
    fun getFileRefsFlow(): Flow<List<LabelFileCrossRefEntity>> = labelDao.queryFileRefsFlow().distinctUntilChanged().flowOn(defaultDispatcher)
    suspend fun getLabels(): List<LabelEntity> = labelDao.queryLabels()
    suspend fun getAppRefs(labelIds: Set<String>): List<LabelAppCrossRefEntity> = labelDao.queryAppRefs(labelIds)
    suspend fun getAppRefs(): List<LabelAppCrossRefEntity> = labelDao.queryAppRefs()
    suspend fun getFileRefs(labelIds: Set<String>): List<LabelFileCrossRefEntity> = labelDao.queryFileRefs(labelIds)
    suspend fun getFileRefs(): List<LabelFileCrossRefEntity> = labelDao.queryFileRefs()

    /**
     * Add a unique label.
     *
     * 调用方不一定会 trim（详情页做了，别的入口未必），空串或纯空格落库会变成一个
     * 看不见的标签。这里统一规范化；空白名返回 -1L，与 upsert 冲突时的返回值同义，
     * 调用方现有的 `== -1L` 失败判断不用改。
     */
    suspend fun addLabel(rawLabel: String): Long {
        val label = rawLabel.trim()
        if (label.isEmpty()) return -1L
        return labelDao.upsert(LabelEntity(label = label))
    }

    suspend fun addLabels(items: List<LabelEntity>) = labelDao.upsertLabels(items)

    /**
     * Add a unique label app cross ref
     */
    suspend fun addLabelAppCrossRef(item: LabelAppCrossRefEntity) = labelDao.upsert(item)

    suspend fun addLabelAppCrossRefs(items: List<LabelAppCrossRefEntity>) = labelDao.upsertAppRefs(items)

    /**
     * Add a unique label file cross ref
     */
    suspend fun addLabelFileCrossRef(item: LabelFileCrossRefEntity) = labelDao.upsert(item)

    suspend fun addLabelFileCrossRefs(items: List<LabelFileCrossRefEntity>) = labelDao.upsertFileRefs(items)


    /**
     * 批量打标：建标签 + 写应用关联，一个事务完成。
     *
     * 同批重复打标是幂等的（关联表主键 upsert），不需要调用方先去重；
     * 标签名去重、去空白在这里做，避免空标签落库。
     */
    suspend fun attachLabels(labels: List<String>, refs: List<LabelAppCrossRefEntity>) {
        val names = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty() || refs.isEmpty()) return
        // 关联里的标签名必须与落库的标签名同源，否则会写出指向不存在标签的孤儿记录
        // （表现为「标签存在但筛选不出任何应用」）。调用方传不传 trim 过的不做假设。
        val normalized = refs.map { it.copy(label = it.label.trim()) }.filter { it.label.isNotEmpty() }
        if (normalized.isEmpty()) return
        labelDao.attachLabels(labels = names.map { LabelEntity(label = it) }, refs = normalized)
    }

    suspend fun deleteLabel(label: String) {
        labelDao.deleteLabelCascade(label)
    }

    /**
     * 批量移除标签：只解除这批关联，标签本身保留（其他应用可能还在用）。
     */
    suspend fun detachLabels(refs: List<LabelAppCrossRefEntity>) {
        if (refs.isEmpty()) return
        labelDao.detachLabels(refs = refs)
    }

    suspend fun deleteLabelAppCrossRef(item: LabelAppCrossRefEntity) {
        labelDao.deleteAppRef(item)
    }

    suspend fun deleteLabelFileCrossRef(item: LabelFileCrossRefEntity) {
        labelDao.deleteFileRef(item)
    }
}

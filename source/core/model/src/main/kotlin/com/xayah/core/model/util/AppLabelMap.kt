package com.xayah.core.model.util

import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity

/**
 * 应用身份：等于标签关联表主键去掉 label 后的部分。
 *
 * 列表标签展示与「已选应用」集合共用同一个定义，避免两处各写一份、将来加字段时只改一边
 * 导致标签串到别的应用上。
 */
data class AppKey(
    val packageName: String,
    val userId: Int,
    val preserveId: Long,
)

/**
 * 把全量标签关联聚合成「应用 → 标签列表」，供列表项直接展示。
 *
 * 只保留仍存在的标签，过滤掉删标签后留下的孤儿关联（v3.15.0 修过「删标签留孤儿记录」）。
 *
 * 与详情页的已知差异：这里对标签名做 trim 并按名称排序，保证显示顺序稳定；详情页
 * `DetailsViewModel` 直接用库里的原始标签名、按查询顺序展示。正常路径下
 * `LabelsRepo.attachLabels` 已 trim 后落库，两者一致；仅历史脏数据（addLabel 入口未 trim）
 * 会出现文字或顺序差别。
 */
fun buildAppLabelMap(
    refs: List<LabelAppCrossRefEntity>,
    labels: List<LabelEntity>,
): Map<AppKey, List<String>> {
    if (refs.isEmpty()) return emptyMap()
    val validLabels = labels.mapTo(HashSet(labels.size)) { it.label.trim() }
    val grouped = HashMap<AppKey, MutableList<String>>()
    for (ref in refs) {
        val name = ref.label.trim()
        if (name.isEmpty() || name !in validLabels) continue
        val key = AppKey(ref.packageName, ref.userId, ref.preserveId)
        grouped.getOrPut(key) { mutableListOf() }.add(name)
    }
    return grouped.mapValues { (_, names) -> names.distinct().sorted() }
}

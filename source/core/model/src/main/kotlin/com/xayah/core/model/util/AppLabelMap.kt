package com.xayah.core.model.util

import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity

/** 列表展示用的应用身份，等于标签关联表主键去掉 label 后的部分。 */
data class AppLabelKey(
    val packageName: String,
    val userId: Int,
    val preserveId: Long,
)

/**
 * 把全量标签关联聚合成「应用 → 标签列表」，供列表项直接展示。
 *
 * 口径与详情页一致：只保留仍存在的标签，过滤掉删标签后留下的孤儿关联
 * （v3.15.0 修过「删标签留孤儿记录」）。标签名去重并排序，保证每次显示顺序一致。
 */
fun buildAppLabelMap(
    refs: List<LabelAppCrossRefEntity>,
    labels: List<LabelEntity>,
): Map<AppLabelKey, List<String>> {
    if (refs.isEmpty()) return emptyMap()
    val validLabels = labels.mapTo(HashSet(labels.size)) { it.label.trim() }
    val grouped = HashMap<AppLabelKey, MutableList<String>>()
    for (ref in refs) {
        val name = ref.label.trim()
        if (name.isEmpty() || name !in validLabels) continue
        val key = AppLabelKey(ref.packageName, ref.userId, ref.preserveId)
        grouped.getOrPut(key) { mutableListOf() }.add(name)
    }
    return grouped.mapValues { (_, names) -> names.distinct().sorted() }
}

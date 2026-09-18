package com.xayah.core.model

import com.xayah.core.model.util.CLOUD_SPLIT_HINT_MARK
import com.xayah.core.model.util.CLOUD_SPLIT_SUGGEST_BYTES
import com.xayah.core.model.util.cloudSplitSuggestion
import com.xayah.core.model.util.shouldSuggestCloudSplit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端备份"文件过大、建议开分卷"的提醒判定。
 *
 * 背景：部分网盘（WebDAV 等）有单文件大小限制，超限时上传会失败；而用户不一定知道
 * 「云端分卷大小」这个开关。所以在备份到云端时，如果归档超过阈值（见 CLOUD_SPLIT_SUGGEST_BYTES）
 * 且没开分卷，就在任务日志里提示一次。
 *
 * 这里锁住三条契约：只有"未开分卷 + 超阈值"才提示、刚好等于阈值不提示（避免边界噪音）、
 * 文案里必须带上标记串（服务端靠它判断"本次任务是否已提示过"）。
 */
class CloudSplitSuggestionTest {
    @Test
    fun `suggests only when split is off and archive exceeds the threshold`() {
        assertTrue(shouldSuggestCloudSplit(CloudSplitSize.DISABLED, CLOUD_SPLIT_SUGGEST_BYTES + 1))
        // 刚好等于阈值不提示
        assertFalse(shouldSuggestCloudSplit(CloudSplitSize.DISABLED, CLOUD_SPLIT_SUGGEST_BYTES))
        // 已经开了分卷就不再提示
        assertFalse(shouldSuggestCloudSplit(CloudSplitSize.SIZE_2G, CLOUD_SPLIT_SUGGEST_BYTES * 4))
        assertFalse(shouldSuggestCloudSplit(CloudSplitSize.SIZE_1G, CLOUD_SPLIT_SUGGEST_BYTES * 4))
        // 小文件不提示
        assertFalse(shouldSuggestCloudSplit(CloudSplitSize.DISABLED, 100L))
    }

    @Test
    fun `hint text carries the dedup marker`() {
        val hint = cloudSplitSuggestion("5.0 GB")
        assertTrue(hint.contains(CLOUD_SPLIT_HINT_MARK))
        assertTrue(hint.contains("5.0 GB"))
    }
}

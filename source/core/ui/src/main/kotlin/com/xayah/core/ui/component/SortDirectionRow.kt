package com.xayah.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xayah.core.model.SortType
import com.xayah.core.ui.R
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

/**
 * 排序方向行：标题 + 当前方向文字(正序/倒序) + 折叠箭头,整行可点击切换。
 * 方向文案用「正序 / 倒序」明示,比单箭头图标明显。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SortDirectionRow(
    text: String,
    sortType: SortType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .paddingHorizontal(SizeTokens.Level24)
            .padding(vertical = SizeTokens.Level4),
    ) {
        Row(
            modifier = Modifier.padding(vertical = SizeTokens.Level8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
        ) {
            TitleLargeText(text = text)
            Text(
                text = when (sortType) {
                    SortType.ASCENDING -> stringResource(id = R.string.sort_asc)
                    SortType.DESCENDING -> stringResource(id = R.string.sort_desc)
                },
                color = ThemedColorSchemeKeyTokens.Primary.value,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                tint = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                modifier = Modifier.size(SizeTokens.Level20),
            )
        }
    }
}

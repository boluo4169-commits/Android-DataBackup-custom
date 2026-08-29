package com.xayah.feature.main.dashboard

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.Section
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle

@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageDataMigration(viewModel: DataMigrationViewModel = hiltViewModel()) {
    val navController = LocalNavController.current!!
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.backup_and_migration),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24),
        ) {
            InnerTopSpacer(innerPadding = innerPadding)
            Section(title = stringResource(R.string.data_migration)) {
                Clickable(
                    title = stringResource(R.string.export_backup),
                    value = stringResource(R.string.export_backup_desc),
                    leadingIcon = Icons.Rounded.FileUpload,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    navController.navigateSingle(MainRoutes.DataMigrationExport.route)
                }
                Clickable(
                    title = stringResource(R.string.import_backup),
                    value = stringResource(R.string.import_backup_desc),
                    leadingIcon = Icons.Rounded.FileDownload,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    navController.navigateSingle(MainRoutes.DataMigrationImport.route)
                }
            }
            Section(title = stringResource(R.string.one_click_backup)) {
                Clickable(
                    title = stringResource(R.string.one_click_backup),
                    value = stringResource(R.string.one_click_backup_desc),
                    leadingIcon = Icons.Rounded.PlayArrow,
                    trailingIcon = Icons.Rounded.KeyboardArrowRight,
                ) {
                    // 全选应用+文件后进入备份设置页；应用备份完成后自动接续文件备份
                    viewModel.oneClickBackup {
                        navController.navigateSingle(MainRoutes.PackagesBackupProcessingGraph.getRoute(chainFileBackup = true))
                    }
                }
            }
            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }
}
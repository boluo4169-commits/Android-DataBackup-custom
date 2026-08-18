package com.xayah.feature.main.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.DismissState
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.Section
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import kotlinx.coroutines.launch

@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageDataMigration(
    viewModel: DataMigrationImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val scope = rememberCoroutineScope()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val apps = viewModel.parse(it)
                if (apps.isEmpty()) {
                    snackbarHostState.showSnackbar(context.getString(R.string.migration_import_empty))
                } else {
                    val state = dialogState.open(
                        initialState = Unit,
                        title = context.getString(R.string.import_backup),
                        icon = null,
                        dismissText = context.getString(R.string.cancel),
                        confirmText = context.getString(R.string.confirm),
                    ) { _ ->
                        Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                            Text(text = context.getString(R.string.migration_import_parsed, apps.size))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
                            ) {
                                items(apps) { app ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                                        Text(text = "• ")
                                        Text(
                                            text = app,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Text(text = context.getString(R.string.migration_import_confirm))
                        }
                    }.first
                    if (state == DismissState.CONFIRM) {
                        viewModel.import()
                    } else {
                        // 用户取消或关闭弹窗，清理临时文件
                        viewModel.cleanupTmpFile()
                    }
                }
            }
        }
    }

    LaunchedEffect(success) {
        if (success) {
            snackbarHostState.showSnackbar(context.getString(R.string.migration_import_success))
            viewModel.consumeSuccess()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.migration_import_failed))
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.data_migration),
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
                    enabled = isImporting.not(),
                ) {
                    importLauncher.launch(arrayOf("application/zstd", "application/octet-stream", "*/*"))
                }
            }
            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }
}

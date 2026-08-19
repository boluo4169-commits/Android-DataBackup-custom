package com.xayah.feature.main.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.DismissState
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.Surface
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.maybePopBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageDataMigrationImport(
    viewModel: DataMigrationImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val scope = rememberCoroutineScope()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val isParsing by viewModel.isParsing.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()

    // 解析中 / 导入中 / 完成 三态文案（区分导出文案）
    val stageTitle = when {
        stage == MigrationStage.Processing && isParsing -> context.getString(R.string.migration_stage_parsing_title)
        stage == MigrationStage.Processing && isImporting -> context.getString(R.string.migration_stage_importing_title)
        stage == MigrationStage.Success -> context.getString(R.string.migration_stage_import_success_title)
        else -> context.getString(R.string.migration_stage_import_idle_title)
    }
    val stageDesc = when {
        stage == MigrationStage.Processing && isParsing -> context.getString(R.string.migration_stage_parsing_desc)
        stage == MigrationStage.Processing && isImporting -> context.getString(R.string.migration_stage_importing_desc)
        stage == MigrationStage.Success -> context.getString(R.string.migration_stage_import_success_desc)
        else -> context.getString(R.string.migration_stage_import_idle_desc)
    }

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
            viewModel.consumeStage()
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
                title = stringResource(R.string.import_backup),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SizeTokens.Level16),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
            ) {
                when (stage) {
                    MigrationStage.Processing -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stageTitle)
                        }
                    }

                    MigrationStage.Success -> {
                        Button(
                            onClick = { navController.maybePopBackStack() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.migration_export_done))
                        }
                    }

                    else -> {
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/zstd", "application/octet-stream", "*/*"))
                            },
                            enabled = isImporting.not(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.import_backup))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .paddingHorizontal(SizeTokens.Level24),
        ) {
            InnerTopSpacer(innerPadding = innerPadding)
            if (stage != MigrationStage.Idle) {
                MigrationStageCard(
                    stage = stage,
                    title = stageTitle,
                    description = stageDesc,
                )
            } else {
                // 未开始时引导用户选择迁移包
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SizeTokens.Level32),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = null,
                            tint = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.Primary.value,
                            modifier = Modifier.padding(SizeTokens.Level8),
                        )
                        TitleLargeText(text = stringResource(R.string.migration_stage_import_idle_title))
                        Text(
                            text = stringResource(R.string.migration_stage_import_idle_desc),
                            color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }
}
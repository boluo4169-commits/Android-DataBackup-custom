package com.xayah.feature.main.system

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.ModalBottomSheet
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.Surface
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.TitleSmallText
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.rememberDialogState
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

@Composable
fun SystemDataRoute(
    viewModel: SystemDataViewModel = hiltViewModel(),
) {
    val uiState: SystemDataUiState by viewModel.uiState.collectAsStateWithLifecycle()
    SystemDataScreen(uiState = uiState, viewModel = viewModel)
}

// system_data_schema_warning_desc 在本模块是 ids.xml 里的空占位符（本模块依赖不到 :app，
// 靠 app 模块同名资源在运行时覆盖）。lint 只看得到那个空串，于是误报「给不含格式符的串传参」，
// 抑制掉；同类写法在 core/service 里也有（AbstractProcessingService / AbstractBackupService）。
@SuppressLint("StringFormatInvalid")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SystemDataScreen(
    uiState: SystemDataUiState,
    viewModel: SystemDataViewModel,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    val dialogState = rememberDialogState()

    // 记住是"哪一项"，文件管理器回调回来后才知道该处理哪个数据
    var pendingBackup by remember { mutableStateOf<SystemDataItem?>(null) }
    var pendingRestore by remember { mutableStateOf<SystemDataItem?>(null) }
    // 非 null 时弹出「本地 / 云端」选择面板
    var pickerItem by remember { mutableStateOf<SystemDataItem?>(null) }
    var pickerForBackup by remember { mutableStateOf(true) }

    val backupLauncher = rememberLauncherForActivityResult(
        // 用 */* 而不是具体 MIME：选具体类型（如 application/octet-stream）时系统会强制
        // 追加它对应的扩展名，把建议的 mmssms.db 变成 mmssms.bin；*/* 会保留我们给的文件名。
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val item = pendingBackup
        pendingBackup = null
        if (uri != null && item != null) {
            viewModel.backupToLocal(item = item, uri = uri)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val item = pendingRestore
        pendingRestore = null
        if (uri != null && item != null) {
            viewModel.restoreFromLocal(item = item, uri = uri)
        }
    }

    val startLocal: (SystemDataItem) -> Unit = { item ->
        pickerItem = null
        if (pickerForBackup) {
            pendingBackup = item
            backupLauncher.launch(item.suggestedName)
        } else {
            pendingRestore = item
            restoreLauncher.launch(arrayOf("*/*"))
        }
    }
    val startCloud: (SystemDataItem, String) -> Unit = { item, cloudName ->
        pickerItem = null
        if (pickerForBackup) {
            viewModel.backupToCloud(item = item, cloudName = cloudName)
        } else {
            viewModel.restoreFromCloud(item = item, cloudName = cloudName)
        }
    }

    // 用自增序号触发：同一个文案连续出现两次（例如连着两次失败）也要能弹出来
    LaunchedEffect(uiState.messageSeq) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(message = it)
            viewModel.consumeMessage()
        }
    }

    // 备份文件相对本机数据库缺少字段时（典型场景：跨品牌恢复）先让用户知情再动手
    LaunchedEffect(uiState.pendingRestore) {
        val pending = uiState.pendingRestore ?: return@LaunchedEffect
        val (dismissState, _) = dialogState.open(
            initialState = Unit,
            title = context.getString(R.string.system_data_schema_warning),
            icon = Icons.Outlined.Warning,
            confirmText = context.getString(R.string.confirm),
            dismissText = context.getString(R.string.cancel),
        ) { _ ->
            Text(
                text = context.getString(
                    R.string.system_data_schema_warning_desc,
                    pending.missingColumns.size,
                    pending.missingColumns.take(6).joinToString(separator = "、"),
                ),
            )
        }
        if (dismissState.isConfirm) viewModel.confirmRestore() else viewModel.cancelRestore()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(id = R.string.system_data),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            InnerTopSpacer(innerPadding = innerPadding)
            Title(title = stringResource(id = R.string.sms)) {
                SystemDataActions(
                    enabled = uiState.busy.not(),
                    desc = stringResource(id = R.string.sms_desc),
                    onBackup = {
                        pickerForBackup = true
                        pickerItem = SystemDataItem.Sms
                    },
                    onRestore = {
                        pickerForBackup = false
                        pickerItem = SystemDataItem.Sms
                    },
                )
            }
            Title(title = stringResource(id = R.string.call_log)) {
                SystemDataActions(
                    enabled = uiState.busy.not(),
                    desc = stringResource(id = R.string.call_log_desc),
                    onBackup = {
                        pickerForBackup = true
                        pickerItem = SystemDataItem.CallLog
                    },
                    onRestore = {
                        pickerForBackup = false
                        pickerItem = SystemDataItem.CallLog
                    },
                )
            }
            Title(title = stringResource(id = R.string.wifi_networks)) {
                SystemDataActions(
                    enabled = uiState.busy.not(),
                    desc = stringResource(id = R.string.wifi_desc),
                    onBackup = {
                        pickerForBackup = true
                        pickerItem = SystemDataItem.Wifi
                    },
                    onRestore = {
                        pickerForBackup = false
                        pickerItem = SystemDataItem.Wifi
                    },
                )
                // 备份出来的 WifiConfigStore.xml 里密码是明文，必须让用户知道，别随手转发
                TitleSmallText(
                    modifier = Modifier
                        .padding(horizontal = SizeTokens.Level24)
                        .padding(bottom = SizeTokens.Level16),
                    text = stringResource(id = R.string.system_data_wifi_plain_text),
                    color = ThemedColorSchemeKeyTokens.Error.value,
                )
            }
            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }

    // 「本地 / 云端」二选一：本地走系统文件管理器，云端直接传到已配置的云账号
    pickerItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { pickerItem = null },
            sheetState = sheetState,
        ) {
            TitleLargeText(
                text = stringResource(if (pickerForBackup) R.string.backup else R.string.restore),
                modifier = Modifier.paddingHorizontal(SizeTokens.Level24),
            )
            Surface(onClick = { startLocal(item) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24)
                        .padding(vertical = SizeTokens.Level12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                ) {
                    TitleLargeText(text = stringResource(id = R.string.system_data_local), maxLines = 1)
                    BodyMediumText(
                        text = stringResource(id = R.string.system_data_local_desc),
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        maxLines = 1,
                    )
                }
            }
            if (uiState.clouds.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.migration_no_cloud),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SizeTokens.Level24),
                    textAlign = TextAlign.Center,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            } else {
                uiState.clouds.forEach { cloud ->
                    Surface(onClick = { startCloud(item, cloud.name) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .paddingHorizontal(SizeTokens.Level24)
                                .padding(vertical = SizeTokens.Level12),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                        ) {
                            TitleLargeText(text = cloud.name, maxLines = 1)
                            BodyMediumText(
                                text = "${cloud.host} (${cloud.type.name})",
                                color = ThemedColorSchemeKeyTokens.Outline.value,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(SizeTokens.Level24))
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun SystemDataActions(
    enabled: Boolean,
    desc: String,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    Clickable(
        enabled = enabled,
        icon = Icons.Rounded.Backup,
        title = stringResource(id = R.string.backup),
        value = desc,
    ) {
        onBackup()
    }
    Clickable(
        enabled = enabled,
        icon = Icons.Rounded.Restore,
        title = stringResource(id = R.string.restore),
    ) {
        onRestore()
    }
}

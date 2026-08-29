package com.xayah.feature.main.settings.restore

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.KeyCleanRestoring
import com.xayah.core.datastore.KeyClearDeviceFingerprint
import com.xayah.core.datastore.KeyFixDataOwnership
import com.xayah.core.datastore.KeyRandomizeGaid
import com.xayah.core.datastore.KeyRandomizeSsaid
import com.xayah.core.datastore.KeyRestorePermissions
import com.xayah.core.datastore.KeyRestoreSsaid
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.saveKillAppOption
import com.xayah.core.datastore.saveRandomizeSsaid
import com.xayah.core.datastore.saveRestoreSsaid
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.util.indexOf
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.LabelMediumText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.select
import com.xayah.core.ui.material3.CircularProgressIndicator
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.command.Ownership
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import kotlinx.coroutines.launch

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageRestoreSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.restore_settings),
        actions = {}
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Column {
                val items = stringArrayResource(id = R.array.kill_app_options)
                val dialogItems by remember(items) {
                    mutableStateOf(items.mapIndexed { index, s ->
                        DialogRadioItem(enum = KillAppOption.indexOf(index), title = s, desc = null)
                    })
                }
                val currentOption by context.readKillAppOption().collectAsStateWithLifecycle(initialValue = KillAppOption.OPTION_II)
                val currentIndex by remember(currentOption) { mutableIntStateOf(currentOption.ordinal) }
                Selectable(
                    title = stringResource(id = R.string.kill_app_options),
                    value = stringResource(id = R.string.kill_app_options_desc),
                    current = items[currentIndex]
                ) {
                    val (state, selectedIndex) = dialogState.select(
                        title = context.getString(R.string.kill_app_options),
                        defIndex = currentIndex,
                        items = dialogItems
                    )
                    if (state.isConfirm) {
                        context.saveKillAppOption(dialogItems[selectedIndex].enum!!)
                    }
                }

                Switchable(
                    key = KeyCleanRestoring,
                    defValue = false,
                    title = stringResource(id = R.string.clean_restoring),
                    checkedText = stringResource(id = R.string.clean_restoring_desc),
                )

                Switchable(
                    key = KeyRestorePermissions,
                    defValue = true,
                    title = stringResource(id = R.string.restore_permissions),
                    checkedText = stringResource(id = R.string.restore_permissions_desc),
                    titleTrailingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SizeTokens.Level16)
                                .clickable {
                                    scope.launch {
                                        dialogState.open(
                                            initialState = Unit,
                                            title = context.getString(R.string.restore_permissions),
                                            icon = Icons.Outlined.Info,
                                            confirmText = context.getString(R.string.got_it),
                                            dismissText = context.getString(R.string.cancel),
                                        ) { _ ->
                                            Text(text = context.getString(R.string.restore_permissions_help))
                                        }
                                    }
                                },
                        )
                    },
                )
            }
            Title(title = stringResource(id = R.string.device_identity)) {
                Switchable(
                    key = KeyRestoreSsaid,
                    defValue = true,
                    title = stringResource(id = R.string.restore_ssaid),
                    checkedText = stringResource(id = R.string.restore_ssaid_desc),
                    onCheckedChange = { checked ->
                        // 与「随机化 Android id」互斥：开启本项则关闭随机化
                        if (checked) scope.launch { context.saveRandomizeSsaid(false) }
                    }
                )

                Switchable(
                    key = KeyRandomizeSsaid,
                    defValue = false,
                    title = stringResource(id = R.string.randomize_ssaid),
                    checkedText = stringResource(id = R.string.randomize_ssaid_desc),
                    onCheckedChange = { checked ->
                        // 与「恢复 Android id」互斥：开启本项则关闭恢复旧值
                        if (checked) scope.launch { context.saveRestoreSsaid(false) }
                    }
                )

                Switchable(
                    key = KeyRandomizeGaid,
                    defValue = false,
                    title = stringResource(id = R.string.randomize_gaid),
                    checkedText = stringResource(id = R.string.randomize_gaid_desc),
                )
            }
            Title(title = stringResource(id = R.string.data_repair)) {
                var fixing by remember { mutableStateOf(false) }

                Switchable(
                    key = KeyFixDataOwnership,
                    defValue = true,
                    title = stringResource(id = R.string.fix_data_ownership),
                    checkedText = stringResource(id = R.string.fix_data_ownership_desc),
                    titleTrailingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SizeTokens.Level16)
                                .clickable {
                                    scope.launch {
                                        dialogState.open(
                                            initialState = Unit,
                                            title = context.getString(R.string.fix_data_ownership),
                                            icon = Icons.Outlined.Info,
                                            confirmText = context.getString(R.string.got_it),
                                        ) { _ ->
                                            Text(text = context.getString(R.string.fix_data_ownership_help))
                                        }
                                    }
                                },
                        )
                    },
                )

                Selectable(
                    enabled = fixing.not(),
                    title = stringResource(id = R.string.fix_ownership_run),
                    desc = stringResource(id = R.string.fix_ownership_run_desc),
                    current = if (fixing) {
                        context.getString(R.string.fix_ownership_running)
                    } else {
                        context.getString(R.string.fix_ownership_run_btn)
                    },
                    trailingExtra = if (fixing) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(SizeTokens.Level18),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    } else null,
                    onClick = suspend {
                        fixing = true
                        val report = try {
                            Ownership.scanAndFixAll()
                        } finally {
                            fixing = false
                        }
                        dialogState.open(
                            initialState = Unit,
                            title = context.getString(R.string.fix_ownership_done),
                            icon = Icons.Outlined.Info,
                            confirmText = context.getString(R.string.got_it),
                        ) { _ ->
                            Text(
                                text = buildString {
                                    appendLine(context.getString(R.string.fix_ownership_report, report.scanned, report.fixed.size, report.failed.size))
                                    report.fixed.forEach { appendLine("✓ $it") }
                                    report.failed.forEach { appendLine("✗ $it") }
                                }.trim()
                            )
                        }
                        Unit
                    }
                )
            }
            Title(title = stringResource(id = R.string.clear_device_fingerprint)) {
                Switchable(
                    key = KeyClearDeviceFingerprint,
                    defValue = false,
                    title = stringResource(id = R.string.clear_device_fingerprint),
                    checkedText = stringResource(id = R.string.clear_device_fingerprint_desc),
                    titleTrailingContent = {
                        LabelMediumText(
                            modifier = Modifier
                                .clip(RoundedCornerShape(SizeTokens.Level4))
                                .background(ThemedColorSchemeKeyTokens.PrimaryContainer.value)
                                .padding(horizontal = SizeTokens.Level8, vertical = SizeTokens.Level2),
                            text = stringResource(id = R.string.experimental),
                            color = ThemedColorSchemeKeyTokens.OnPrimaryContainer.value,
                        )
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SizeTokens.Level16)
                                .clickable {
                                    scope.launch {
                                        dialogState.open(
                                            initialState = Unit,
                                            title = context.getString(R.string.clear_device_fingerprint),
                                            icon = Icons.Outlined.Info,
                                            confirmText = context.getString(R.string.got_it),
                                            dismissText = context.getString(R.string.cancel),
                                        ) { _ ->
                                            Text(text = context.getString(R.string.clear_device_fingerprint_help))
                                        }
                                    }
                                },
                        )
                    },
                )
            }
            InnerBottomSpacer(innerPadding = it)
        }
    }
}

package com.xayah.feature.main.settings.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.ScheduleFrequency
import com.xayah.core.model.ScheduleScope
import com.xayah.core.model.database.ScheduleEntity
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.material3.Card
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@ExperimentalAnimationApi
@ExperimentalLayoutApi
@ExperimentalMaterial3Api
@Composable
fun PageSchedules(viewModel: SchedulesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduleEntity?>(null) }

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.schedules),
        actions = {}
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            InnerTopSpacer(innerPadding = innerPadding)

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    editing = null
                    showSheet = true
                },
                content = {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                    Text(text = stringResource(id = R.string.add_schedule))
                }
            )

            if (schedules.isEmpty()) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(id = R.string.schedule_empty),
                    textAlign = TextAlign.Center,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value
                )
            }

            schedules.forEach { schedule ->
                ScheduleCard(
                    schedule = schedule,
                    onToggle = { viewModel.setEnabled(schedule, it) },
                    onRunOnce = { viewModel.runOnce(schedule) },
                    onEdit = {
                        editing = schedule
                        showSheet = true
                    },
                    onDelete = { deleting = schedule }
                )
            }

            InnerBottomSpacer(innerPadding = innerPadding)
        }
    }

    if (showSheet) {
        ScheduleFormSheet(
            initial = editing,
            onDismiss = { showSheet = false },
            onConfirm = { schedule ->
                viewModel.save(schedule)
                showSheet = false
            }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(text = stringResource(id = R.string.confirm_delete_schedule)) },
            text = { Text(text = target.name) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    deleting = null
                }) {
                    Text(text = stringResource(id = R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun summaryOf(schedule: ScheduleEntity): String {
    val context = LocalContext.current
    return buildScheduleName(context, schedule)
}

private fun buildScheduleName(context: android.content.Context, schedule: ScheduleEntity): String {
    val time = "%02d:%02d".format(schedule.hour, schedule.minute)
    val freq = when (schedule.frequency) {
        ScheduleFrequency.DAILY -> context.getString(R.string.schedule_daily)
        ScheduleFrequency.WEEKLY -> {
            val day = schedule.dayOfWeek?.coerceIn(1, 7) ?: 1
            val dayName = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
            "${context.getString(R.string.schedule_weekly)} $dayName"
        }
        ScheduleFrequency.MONTHLY -> "${context.getString(R.string.schedule_monthly)} ${schedule.dayOfMonth ?: 1}"
    }
    return "$freq $time"
}

@Composable
private fun scopeLabel(schedule: ScheduleEntity): String {
    val context = LocalContext.current
    return when (schedule.scope) {
        ScheduleScope.APPS_ALL -> context.getString(R.string.schedule_scope_apps)
        ScheduleScope.APPS_ALL_FILES -> context.getString(R.string.schedule_scope_apps_files)
        ScheduleScope.FILES_ONLY -> context.getString(R.string.schedule_scope_files)
    }
}

@ExperimentalMaterial3Api
@Composable
private fun ScheduleCard(
    schedule: ScheduleEntity,
    onToggle: (Boolean) -> Unit,
    onRunOnce: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Column(
            modifier = Modifier.padding(SizeTokens.Level16),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = summaryOf(schedule), fontWeight = FontWeight.Bold)
                    Text(
                        text = scopeLabel(schedule),
                        color = ThemedColorSchemeKeyTokens.Outline.value
                    )
                }
                Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            }
            Text(
                text = if (schedule.nextTriggerAt > 0) {
                    val fmt = remember(schedule.nextTriggerAt) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    stringResource(id = R.string.next_trigger_at, fmt.format(Date(schedule.nextTriggerAt)))
                } else {
                    stringResource(id = R.string.schedule_never_ran)
                },
                color = ThemedColorSchemeKeyTokens.Outline.value
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level4)) {
                IconButton(onClick = onRunOnce) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = stringResource(id = R.string.run_once))
                }
                IconButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Rounded.Edit, contentDescription = stringResource(id = R.string.edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Rounded.Delete, contentDescription = stringResource(id = R.string.delete))
                }
            }
        }
    }
}

@ExperimentalMaterial3Api
@Composable
private fun ScheduleFormSheet(
    initial: ScheduleEntity?,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleEntity) -> Unit,
) {
    var frequency by remember { mutableStateOf(initial?.frequency ?: ScheduleFrequency.DAILY) }
    val timeState = rememberTimePickerState(initialHour = initial?.hour ?: 2, initialMinute = initial?.minute ?: 0, is24Hour = true)
    var dayOfWeek by remember { mutableStateOf(initial?.dayOfWeek ?: 1) }
    var dayOfMonth by remember { mutableStateOf(initial?.dayOfMonth ?: 1) }
    var scope by remember { mutableStateOf(initial?.scope ?: ScheduleScope.APPS_ALL) }
    var requireCharging by remember { mutableStateOf(initial?.requireCharging ?: false) }
    var requireBatteryNotLow by remember { mutableStateOf(initial?.requireBatteryNotLow ?: false) }
    var requireUnmetered by remember { mutableStateOf(initial?.requireUnmetered ?: false) }
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SizeTokens.Level24),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level12)
        ) {
            Text(text = stringResource(id = R.string.schedule_frequency), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                FilterChip(
                    selected = frequency == ScheduleFrequency.DAILY,
                    onClick = { frequency = ScheduleFrequency.DAILY },
                    label = { Text(text = stringResource(id = R.string.schedule_daily)) }
                )
                FilterChip(
                    selected = frequency == ScheduleFrequency.WEEKLY,
                    onClick = { frequency = ScheduleFrequency.WEEKLY },
                    label = { Text(text = stringResource(id = R.string.schedule_weekly)) }
                )
                FilterChip(
                    selected = frequency == ScheduleFrequency.MONTHLY,
                    onClick = { frequency = ScheduleFrequency.MONTHLY },
                    label = { Text(text = stringResource(id = R.string.schedule_monthly)) }
                )
            }

            if (frequency == ScheduleFrequency.WEEKLY) {
                Text(text = stringResource(id = R.string.schedule_day_of_week), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level4)) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = dayOfWeek == day,
                            onClick = { dayOfWeek = day },
                            label = {
                                Text(text = DayOfWeek.of(day).getDisplayName(TextStyle.NARROW, Locale.getDefault()))
                            }
                        )
                    }
                }
            }

            if (frequency == ScheduleFrequency.MONTHLY) {
                Text(
                    text = "${stringResource(id = R.string.schedule_day_of_month)}: $dayOfMonth",
                    fontWeight = FontWeight.Bold
                )
                androidx.compose.material3.Slider(
                    value = dayOfMonth.toFloat(),
                    onValueChange = { dayOfMonth = it.toInt().coerceIn(1, 31) },
                    valueRange = 1f..31f,
                    steps = 29
                )
            }

            Text(text = stringResource(id = R.string.schedule_time), fontWeight = FontWeight.Bold)
            TimePicker(state = timeState)

            Text(text = stringResource(id = R.string.schedule_scope), fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)) {
                FilterChip(
                    selected = scope == ScheduleScope.APPS_ALL,
                    onClick = { scope = ScheduleScope.APPS_ALL },
                    label = { Text(text = stringResource(id = R.string.schedule_scope_apps)) }
                )
                FilterChip(
                    selected = scope == ScheduleScope.APPS_ALL_FILES,
                    onClick = { scope = ScheduleScope.APPS_ALL_FILES },
                    label = { Text(text = stringResource(id = R.string.schedule_scope_apps_files)) }
                )
                FilterChip(
                    selected = scope == ScheduleScope.FILES_ONLY,
                    onClick = { scope = ScheduleScope.FILES_ONLY },
                    label = { Text(text = stringResource(id = R.string.schedule_scope_files)) }
                )
            }

            Text(text = stringResource(id = R.string.schedule_conditions), fontWeight = FontWeight.Bold)
            ConditionRow(label = stringResource(id = R.string.require_charging), checked = requireCharging) { requireCharging = it }
            ConditionRow(label = stringResource(id = R.string.require_battery_not_low), checked = requireBatteryNotLow) { requireBatteryNotLow = it }
            ConditionRow(label = stringResource(id = R.string.require_unmetered), checked = requireUnmetered) { requireUnmetered = it }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SizeTokens.Level16),
                onClick = {
                    val base = initial ?: ScheduleEntity(name = "", frequency = frequency, hour = 0, minute = 0, scope = scope)
                    val edited = base.copy(
                        frequency = frequency,
                        hour = timeState.hour,
                        minute = timeState.minute,
                        dayOfWeek = if (frequency == ScheduleFrequency.WEEKLY) dayOfWeek else null,
                        dayOfMonth = if (frequency == ScheduleFrequency.MONTHLY) dayOfMonth else null,
                        scope = scope,
                        requireCharging = requireCharging,
                        requireBatteryNotLow = requireBatteryNotLow,
                        requireUnmetered = requireUnmetered,
                    )
                    // 名字不可自定义，始终按最新配置重新生成（编辑时刻/频率后名字同步刷新）
                    onConfirm(edited.copy(name = buildScheduleName(context, edited)))
                },
                content = {
                    Text(text = stringResource(id = R.string.confirm))
                }
            )
        }
    }
}

@Composable
private fun ConditionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(modifier = Modifier.weight(1f), text = label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

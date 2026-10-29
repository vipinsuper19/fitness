package com.vipin.fitness.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.designsystem.NotificationSettingsTags as Tags

@Composable
fun NotificationSettingsRoute(onBack: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    NotificationSettingsScreen(
        s,
        onBack = onBack,
        onRetryLoad = viewModel::load,
        onEnabled = viewModel::setEnabled,
        onType = viewModel::setType,
        onQuietEnabled = viewModel::setQuietEnabled,
        onQuietStart = viewModel::setQuietStart,
        onQuietEnd = viewModel::setQuietEnd,
        onSave = viewModel::save,
        onDiscard = viewModel::discard,
    )
}

private enum class Picking { START, END }

@Composable
internal fun NotificationSettingsScreen(
    s: NotificationSettingsState,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onType: (NotificationType, Boolean) -> Unit,
    onQuietEnabled: (Boolean) -> Unit,
    onQuietStart: (String) -> Unit,
    onQuietEnd: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val res = LocalContext.current.resources
    val p = s.draft
    when {
        s.loading -> Box(Modifier.fillMaxSize().testTag(Tags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        p == null -> Column(
            Modifier.fillMaxSize().padding(24.dp).testTag(Tags.LOAD_ERROR),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(NotificationFormat.error(res, s.loadError ?: NotificationError.FAILED))
            Button(onClick = onRetryLoad, modifier = Modifier.padding(top = 12.dp).testTag(Tags.RETRY)) { Text(stringResource(R.string.notif_retry)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.notif_back)) }
        }
        else -> {
            var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(Tags.SCREEN),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.notif_back)) }
                    Text(stringResource(R.string.notif_title), style = MaterialTheme.typography.headlineMedium)
                }
                SwitchRow(stringResource(R.string.notif_master), p.enabled, !s.saving, Tags.MASTER, onEnabled)
                if (!p.enabled) Text(stringResource(R.string.notif_master_off_note), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(stringResource(R.string.notif_types_title), style = MaterialTheme.typography.titleSmall)
                Text(NotificationFormat.typesOn(res, p), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.TYPES_SUMMARY))
                // Only the types this app knows; unknown server types were dropped when decoding.
                p.types.forEach { (t, on) ->
                    SwitchRow(NotificationFormat.label(res, t), on, p.enabled && !s.saving, Tags.type(t.wire)) { onType(t, it) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.notif_quiet_title), style = MaterialTheme.typography.titleSmall)
                SwitchRow(stringResource(R.string.notif_quiet_enabled), p.quietHours.enabled, !s.saving, Tags.QUIET_ENABLED, onQuietEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val editable = p.quietHours.enabled && !s.saving
                    OutlinedButton(onClick = { picking = Picking.START }, enabled = editable, modifier = Modifier.testTag(Tags.QUIET_START)) {
                        Text(stringResource(R.string.notif_quiet_start_at, p.quietHours.start))
                    }
                    OutlinedButton(onClick = { picking = Picking.END }, enabled = editable, modifier = Modifier.testTag(Tags.QUIET_END)) {
                        Text(stringResource(R.string.notif_quiet_end_at, p.quietHours.end))
                    }
                }
                if (!s.sameTimes) {
                    Text(NotificationFormat.window(res, p.quietHours), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.QUIET_WINDOW))
                }
                Text(stringResource(R.string.notif_timezone, p.timezone), modifier = Modifier.testTag(Tags.TIMEZONE))
                Text(stringResource(R.string.notif_timezone_note), style = MaterialTheme.typography.bodySmall)
                s.saveError?.let { e ->
                    Text(NotificationFormat.error(res, e), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SAVE_ERROR))
                    if (e == NotificationError.OFFLINE || e == NotificationError.FAILED || e == NotificationError.RATE_LIMITED) {
                        TextButton(onClick = onSave, enabled = !s.saving, modifier = Modifier.testTag(Tags.SAVE_RETRY)) { Text(stringResource(R.string.notif_retry)) }
                    }
                }
                if (s.savedOk && !s.dirty) Text(stringResource(R.string.notif_saved), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(Tags.SAVED))
                Button(onClick = onSave, enabled = s.dirty && !s.saving, modifier = Modifier.fillMaxWidth().testTag(Tags.SAVE)) {
                    Text(stringResource(R.string.notif_save))
                }
                if (s.dirty) {
                    TextButton(onClick = onDiscard, enabled = !s.saving, modifier = Modifier.fillMaxWidth().testTag(Tags.DISCARD)) {
                        Text(stringResource(R.string.notif_discard))
                    }
                }
            }
            picking?.let { which ->
                val current = if (which == Picking.START) p.quietHours.start else p.quietHours.end
                TimeDialog(current, onDismiss = { picking = null }) { hhmm ->
                    picking = null
                    if (which == Picking.START) onQuietStart(hhmm) else onQuietEnd(hhmm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(current: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val (h, m) = NotificationFormat.parse(current) ?: (0 to 0)
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state, modifier = Modifier.testTag(Tags.TIME_PICKER)) },
        confirmButton = {
            TextButton(onClick = { onPicked(NotificationFormat.time(state.hour, state.minute)) }, modifier = Modifier.testTag(Tags.TIME_PICKER_OK)) {
                Text(stringResource(R.string.notif_pick_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notif_pick_cancel)) } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

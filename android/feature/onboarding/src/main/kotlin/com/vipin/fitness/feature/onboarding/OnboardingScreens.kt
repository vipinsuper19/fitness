package com.vipin.fitness.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import com.vipin.fitness.core.designsystem.AccountEntryTags
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.designsystem.UiText

private fun OnboardingStep.title() = when (this) {
    OnboardingStep.ABOUT -> R.string.onb_step_about
    OnboardingStep.BODY -> R.string.onb_step_body
    OnboardingStep.ACTIVITY -> R.string.onb_step_activity
    OnboardingStep.GOALS -> R.string.onb_step_goals
}

/** "Why we ask" copy shown under each step's title. */
internal fun OnboardingStep.why() = when (this) {
    OnboardingStep.ABOUT -> R.string.onb_why_about
    OnboardingStep.BODY -> R.string.onb_why_body
    OnboardingStep.ACTIVITY -> R.string.onb_why_activity
    OnboardingStep.GOALS -> R.string.onb_why_goals
}

@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.finished) { if (s.finished) onFinished() }
    when {
        s.loading -> Loading()
        s.loadError != null -> LoadError(s.loadError!!, viewModel::load)
        else -> Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val total = OnboardingStep.entries.size
            LinearProgressIndicator(progress = { (s.step.ordinal + 1f) / total }, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
            Text(stringResource(R.string.onb_step_of, s.step.ordinal + 1, total), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(s.step.title()), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(s.step.why()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("onboarding_why"))
            Text(stringResource(R.string.onb_optional_note), style = MaterialTheme.typography.bodyMedium)
            ProfileFields(s.step.fields, s.form, s.errors, viewModel::onForm, viewModel::onUnits)
            s.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.step.ordinal > 0) TextButton(onClick = viewModel::back, enabled = !s.saving) { Text(stringResource(R.string.onb_back)) }
                Box(Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::skip, enabled = !s.saving) { Text(stringResource(R.string.onb_skip)) }
                Button(onClick = viewModel::next, enabled = !s.saving) {
                    Text(stringResource(if (s.step.isLast) R.string.onb_finish else R.string.onb_next))
                }
            }
        }
    }
}

@Composable
fun ProfileEditRoute(
    onSignedOut: () -> Unit,
    viewModel: ProfileEditViewModel = hiltViewModel(),
    onDeleteAccount: (() -> Unit)? = null,
    onExportData: (() -> Unit)? = null,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.signedOut) { if (s.signedOut) onSignedOut() }
    when {
        s.loading -> Loading()
        s.loadError != null -> LoadError(s.loadError!!, viewModel::load)
        else -> Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineMedium)
            ProfileFields(ProfileField.entries.toSet(), s.form, s.errors, viewModel::onForm, viewModel::onUnits)
            s.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            if (s.saved) Text(stringResource(R.string.profile_saved), color = MaterialTheme.colorScheme.primary)
            Button(onClick = viewModel::save, enabled = !s.saving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_save))
            }
            OutlinedButton(onClick = viewModel::signOut, enabled = !s.signingOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_sign_out))
            }
            if (onExportData != null) {
                OutlinedButton(onClick = onExportData, enabled = !s.signingOut, modifier = Modifier.fillMaxWidth().testTag(AccountEntryTags.OPEN_EXPORT)) {
                    Text(stringResource(R.string.profile_export_data))
                }
            }
            if (onDeleteAccount != null) {
                TextButton(onClick = onDeleteAccount, enabled = !s.signingOut, modifier = Modifier.fillMaxWidth().testTag(AccountEntryTags.OPEN_DELETE_ACCOUNT)) {
                    Text(stringResource(R.string.profile_delete_account), color = MaterialTheme.colorScheme.error)
                }
            }
            SettingsLinks()
        }
    }
    if (s.unsyncedOnSignOut > 0) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSignOut,
            title = { Text(stringResource(R.string.sign_out_unsynced_title)) },
            text = { Text(pluralStringResource(R.plurals.sign_out_unsynced, s.unsyncedOnSignOut, s.unsyncedOnSignOut)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSignOut, modifier = Modifier.testTag("sign_out_anyway")) {
                    Text(stringResource(R.string.sign_out_anyway), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::cancelSignOut) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = viewModel::signOut, modifier = Modifier.testTag("sign_out_sync")) { Text(stringResource(R.string.sync_now)) }
                }
            },
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun LoadError(error: UiText, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(error.resolve())
        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
    }
}

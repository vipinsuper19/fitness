package com.vipin.fitness.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.designsystem.DeleteAccountTags

@Composable
fun DeleteAccountRoute(onDeleted: () -> Unit, onCancel: () -> Unit, viewModel: DeleteAccountViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    // Both end signed out on sign-in: after a delete, or to check whether one went through.
    LaunchedEffect(s.deleted, s.signedOutToCheck) { if (s.deleted || s.signedOutToCheck) onDeleted() }
    DeleteAccountContent(s, viewModel::onPassword, viewModel::delete, onCancel, viewModel::signInToCheck)
}

@Composable
fun DeleteAccountContent(
    s: DeleteAccountState,
    onPassword: (String) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onSignInToCheck: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(DeleteAccountTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.delete_account_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.delete_account_warning), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("delete_account_warning"))
        Text(stringResource(R.string.delete_account_unsynced), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = s.password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.delete_account_password)) },
            singleLine = true,
            enabled = !s.submitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = s.error == DeleteAccountError.WRONG_PASSWORD,
            modifier = Modifier.fillMaxWidth().testTag(DeleteAccountTags.PASSWORD),
        )
        s.error?.let {
            Text(
                stringResource(
                    when (it) {
                        DeleteAccountError.WRONG_PASSWORD -> R.string.delete_account_wrong_password
                        DeleteAccountError.OFFLINE -> R.string.delete_account_offline
                        DeleteAccountError.SERVER -> R.string.delete_account_server
                        DeleteAccountError.RATE_LIMITED -> R.string.auth_error_rate_limited
                        DeleteAccountError.FAILED -> R.string.auth_error_generic
                        DeleteAccountError.MAYBE_DELETED -> R.string.delete_account_maybe_deleted
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(DeleteAccountTags.ERROR),
            )
            if (it == DeleteAccountError.MAYBE_DELETED) {
                Button(onClick = onSignInToCheck, enabled = !s.signedOutToCheck, modifier = Modifier.fillMaxWidth().testTag(DeleteAccountTags.SIGN_IN_TO_CHECK)) {
                    Text(stringResource(R.string.delete_account_sign_in_to_check))
                }
            }
        }
        Button(
            onClick = onDelete,
            enabled = s.canConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().testTag(DeleteAccountTags.CONFIRM),
        ) {
            if (s.submitting) CircularProgressIndicator(Modifier.padding(2.dp).testTag(DeleteAccountTags.PROGRESS), strokeWidth = 2.dp) else Text(stringResource(R.string.delete_account_confirm))
        }
        TextButton(onClick = onCancel, enabled = !s.submitting, modifier = Modifier.fillMaxWidth().testTag(DeleteAccountTags.CANCEL)) {
            Text(stringResource(R.string.delete_account_cancel))
        }
    }
}

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

@Composable
fun DeleteAccountRoute(onDeleted: () -> Unit, onCancel: () -> Unit, viewModel: DeleteAccountViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.deleted) { if (s.deleted) onDeleted() }
    DeleteAccountContent(s, viewModel::onPassword, viewModel::delete, onCancel)
}

@Composable
fun DeleteAccountContent(s: DeleteAccountState, onPassword: (String) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
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
            isError = s.error == DeleteAccountError.WRONG_PASSWORD || s.error == DeleteAccountError.PASSWORD_REQUIRED,
            modifier = Modifier.fillMaxWidth().testTag("delete_account_password"),
        )
        s.error?.let {
            Text(
                stringResource(
                    when (it) {
                        DeleteAccountError.PASSWORD_REQUIRED -> R.string.auth_error_password_required
                        DeleteAccountError.WRONG_PASSWORD -> R.string.delete_account_wrong_password
                        DeleteAccountError.OFFLINE -> R.string.delete_account_offline
                        DeleteAccountError.RATE_LIMITED -> R.string.auth_error_rate_limited
                        DeleteAccountError.FAILED -> R.string.auth_error_generic
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("delete_account_error"),
            )
        }
        Button(
            onClick = onDelete,
            enabled = !s.submitting,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().testTag("delete_account_confirm"),
        ) {
            if (s.submitting) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.delete_account_confirm))
        }
        TextButton(onClick = onCancel, enabled = !s.submitting, modifier = Modifier.fillMaxWidth().testTag("delete_account_cancel")) {
            Text(stringResource(R.string.delete_account_cancel))
        }
    }
}

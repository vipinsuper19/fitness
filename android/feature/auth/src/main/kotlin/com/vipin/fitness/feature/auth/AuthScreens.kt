package com.vipin.fitness.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.vipin.fitness.core.designsystem.UiText

@Composable
fun SignInRoute(
    prefillEmail: String?,
    sessionExpired: Boolean,
    onSignedIn: () -> Unit,
    onRegister: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(prefillEmail) { viewModel.prefill(prefillEmail) }
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
    AuthForm(title = stringResource(R.string.auth_sign_in_title)) {
        if (sessionExpired) {
            Text(stringResource(R.string.auth_session_expired), color = MaterialTheme.colorScheme.error)
        }
        EmailField(state.email, state.emailError, viewModel::onEmail)
        PasswordField(state.password, state.passwordError, null, viewModel::onPassword)
        state.error?.let { ErrorText(it) }
        SubmitButton(stringResource(R.string.auth_sign_in), state.submitting, viewModel::submit)
        TextButton(onClick = onRegister) { Text(stringResource(R.string.auth_go_register)) }
    }
}

@Composable
fun RegisterRoute(
    onRegistered: () -> Unit,
    onSignInInstead: (email: String) -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.registered) { if (state.registered) onRegistered() }
    AuthForm(title = stringResource(R.string.auth_register_title)) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayName,
            label = { Text(stringResource(R.string.auth_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        EmailField(state.email, state.emailError, viewModel::onEmail)
        PasswordField(state.password, state.passwordError, stringResource(R.string.auth_password_hint), viewModel::onPassword)
        state.error?.let { ErrorText(it) }
        if (state.emailTaken) {
            OutlinedButton(
                onClick = { onSignInInstead(state.email.trim()) },
                modifier = Modifier.fillMaxWidth().testTag("sign_in_instead"),
            ) { Text(stringResource(R.string.auth_sign_in_instead)) }
        }
        SubmitButton(stringResource(R.string.auth_create_account), state.submitting, viewModel::submit)
        TextButton(onClick = { onSignInInstead(state.email.trim()) }) { Text(stringResource(R.string.auth_go_sign_in)) }
    }
}

@Composable
private fun AuthForm(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 48.dp, bottom = 12.dp))
        content()
    }
}

@Composable
private fun EmailField(value: String, error: UiText?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.auth_email)) },
        isError = error != null,
        supportingText = error?.let { { Text(it.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, error: UiText?, hint: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.auth_password)) },
        isError = error != null,
        supportingText = (error?.resolve() ?: hint)?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(text: UiText) {
    Text(text.resolve(), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun SubmitButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(label)
    }
}

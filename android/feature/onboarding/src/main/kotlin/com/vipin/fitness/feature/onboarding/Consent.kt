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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.ConsentRepository
import com.vipin.fitness.core.designsystem.ConsentTags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** null = still reading the device store. */
data class ConsentState(val consented: Boolean? = null, val saving: Boolean = false, val saveFailed: Boolean = false)

@HiltViewModel
class ConsentViewModel @Inject constructor(private val consent: ConsentRepository) : ViewModel() {
    private val _state = MutableStateFlow(ConsentState())
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(consented = consent.hasConsented()) } }
    }

    fun accept() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            // False: the account couldn't be identified (e.g. offline right after updating), so
            // nothing was saved. Stay here and say so rather than letting the user through.
            val saved = consent.accept()
            _state.update { it.copy(consented = saved, saving = false, saveFailed = !saved) }
        }
    }
}

/** Shows the consent screen until this user has agreed on this device, then [content]. */
@Composable
fun ConsentGate(viewModel: ConsentViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    when (s.consented) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        true -> content()
        false -> ConsentContent(saving = s.saving, onContinue = viewModel::accept, saveFailed = s.saveFailed)
    }
}

@Composable
fun ConsentContent(saving: Boolean, onContinue: () -> Unit, saveFailed: Boolean = false) {
    val uri = LocalUriHandler.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(ConsentTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 32.dp))
        Text(stringResource(R.string.consent_why_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.consent_why_body))
        Text(stringResource(R.string.consent_control_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.consent_control_body))
        Text(stringResource(R.string.consent_not_medical), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { uri.openUri(LegalLinks.privacy) }, modifier = Modifier.testTag(ConsentTags.PRIVACY)) {
                Text(stringResource(R.string.legal_privacy))
            }
            TextButton(onClick = { uri.openUri(LegalLinks.terms) }, modifier = Modifier.testTag(ConsentTags.TERMS)) {
                Text(stringResource(R.string.legal_terms))
            }
        }
        Row(
            Modifier.fillMaxWidth().toggleable(agreed, role = Role.Checkbox, onValueChange = { agreed = it }).testTag(ConsentTags.AGREE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = agreed, onCheckedChange = null)
            Text(stringResource(R.string.consent_agree), modifier = Modifier.padding(start = 8.dp))
        }
        if (saveFailed) {
            Text(stringResource(R.string.consent_save_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ConsentTags.ERROR))
        }
        Button(
            onClick = onContinue,
            enabled = agreed && !saving,
            modifier = Modifier.fillMaxWidth().testTag(ConsentTags.CONTINUE),
        ) { Text(stringResource(R.string.consent_continue)) }
    }
}

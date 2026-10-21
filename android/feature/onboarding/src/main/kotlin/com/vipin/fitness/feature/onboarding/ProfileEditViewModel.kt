package com.vipin.fitness.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.designsystem.UiText
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Year
import javax.inject.Inject

data class ProfileEditState(
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val form: ProfileForm = ProfileForm(),
    val errors: Map<ProfileField, FieldError> = emptyMap(),
    val saving: Boolean = false,
    val error: UiText? = null,
    val saved: Boolean = false,
    val signingOut: Boolean = false,
    val signedOut: Boolean = false,
)

/** Edit every profile field on one screen. Save sends only the fields that changed. */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val session: SessionRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    private var initial = ProfileForm()
    private var pending: Pair<ProfileChanges, String>? = null
    internal var newKey: () -> String = ::newIdempotencyKey

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            when (val r = profiles.get()) {
                is ApiResult.Success -> {
                    initial = ProfileForm.from(r.value)
                    _state.update { it.copy(loading = false, form = initial) }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    fun onForm(edit: (ProfileForm) -> ProfileForm) = _state.update { s ->
        val f = edit(s.form)
        s.copy(form = f, errors = s.errors.filterKeys { it !in changedKeys(s.form, f) }, error = null, saved = false)
    }

    fun onUnits(units: UnitSystem) = onForm { it.withUnits(units) }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val year = Year.now(clock).value
        val all = ProfileField.entries.toSet()
        val errors = s.form.errors(all, year)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        val changes = s.form.changes(all, initial, year)
        if (changes == ProfileChanges()) {
            _state.update { it.copy(saved = true) }
            return
        }
        val key = pending?.takeIf { it.first == changes }?.second ?: newKey()
        pending = changes to key
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = profiles.update(changes, key)) {
                is ApiResult.Success -> {
                    pending = null
                    initial = ProfileForm.from(r.value)
                    _state.update { it.copy(saving = false, saved = true, form = initial) }
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    fun signOut() {
        val s = _state.value
        if (s.signedOut || s.signingOut) return
        _state.update { it.copy(signingOut = true) } // set before launch: a double tap signs out once
        viewModelScope.launch {
            session.signOut()
            _state.update { it.copy(signingOut = false, signedOut = true) }
        }
    }
}

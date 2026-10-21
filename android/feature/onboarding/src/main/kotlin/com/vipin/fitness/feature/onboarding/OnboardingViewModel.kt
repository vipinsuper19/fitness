package com.vipin.fitness.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.ProfileRepository
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

enum class OnboardingStep(val fields: Set<ProfileField>) {
    ABOUT(setOf(ProfileField.NAME, ProfileField.BIRTH_YEAR, ProfileField.SEX)),
    BODY(setOf(ProfileField.UNITS, ProfileField.HEIGHT, ProfileField.WEIGHT)),
    ACTIVITY(setOf(ProfileField.ACTIVITY, ProfileField.EXPERIENCE)),
    GOALS(setOf(ProfileField.DIET, ProfileField.CALORIES, ProfileField.WATER)),
    ;

    val isLast get() = this == entries.last()

    /** True when the user answered nothing on this step yet (units always has a value, so it doesn't count). */
    fun isUnanswered(p: Profile): Boolean = when (this) {
        ABOUT -> p.displayName == null && p.birthYear == null && p.sex == null
        BODY -> p.heightCm == null && p.weightKg == null
        ACTIVITY -> p.activityLevel == null && p.experienceLevel == null
        GOALS -> p.dietPreference == null && p.dailyCalorieTargetKcal == null && p.dailyWaterTargetMl == null
    }

    companion object {
        /** Resume at the first step with no answers; if all have some, the last one. */
        fun resumeAt(p: Profile): OnboardingStep = entries.firstOrNull { it.isUnanswered(p) } ?: entries.last()
    }
}

data class OnboardingState(
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val step: OnboardingStep = OnboardingStep.ABOUT,
    val form: ProfileForm = ProfileForm(),
    val errors: Map<ProfileField, FieldError> = emptyMap(),
    val saving: Boolean = false,
    val error: UiText? = null,
    val finished: Boolean = false,
)

/**
 * Four skippable steps. Each "Next" saves only what the user changed on that step, so
 * progress survives the app being killed: on return, onboarding resumes at the first step with
 * no answers (or the step saved in [SavedStateHandle] after process death). Finishing or skipping
 * the last step sets `onboardingCompleted`.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

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
                    val p = r.value
                    if (p.onboardingCompleted) {
                        _state.update { it.copy(loading = false, finished = true) }
                        return@launch
                    }
                    initial = ProfileForm.from(p)
                    val step = saved.get<String>(KEY_STEP)?.let { s -> OnboardingStep.entries.firstOrNull { it.name == s } }
                        ?: OnboardingStep.resumeAt(p)
                    setStep(step)
                    _state.update { it.copy(loading = false, form = initial) }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    fun onForm(edit: (ProfileForm) -> ProfileForm) = _state.update { s ->
        val f = edit(s.form)
        s.copy(form = f, errors = s.errors.filterKeys { it !in changedKeys(s.form, f) }, error = null)
    }

    fun onUnits(units: UnitSystem) = onForm { it.withUnits(units) }

    fun next() = advance(skip = false)
    fun skip() = advance(skip = true)

    fun back() {
        val s = _state.value
        if (s.saving || s.step.ordinal == 0) return
        setStep(OnboardingStep.entries[s.step.ordinal - 1])
        _state.update { it.copy(errors = emptyMap(), error = null) }
    }

    private fun advance(skip: Boolean) {
        val s = _state.value
        if (s.saving || s.loading) return
        val year = Year.now(clock).value
        val changes = if (skip) {
            ProfileChanges()
        } else {
            val errors = s.form.errors(s.step.fields, year)
            if (errors.isNotEmpty()) {
                _state.update { it.copy(errors = errors) }
                return
            }
            s.form.changes(s.step.fields, initial, year)
        }.let { if (s.step.isLast) it.copy(onboardingCompleted = true) else it }

        if (changes == ProfileChanges()) { // nothing to send
            moveOn(s.step, keepForm = skip)
            return
        }
        val key = pending?.takeIf { it.first == changes }?.second ?: newKey()
        pending = changes to key
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = profiles.update(changes, key)) {
                is ApiResult.Success -> {
                    pending = null
                    if (!skip) initial = _state.value.form // what's on the server now
                    _state.update { it.copy(saving = false) }
                    moveOn(s.step, keepForm = skip)
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    /** After a skip, unsaved edits on that step are dropped so the form matches the server. */
    private fun moveOn(from: OnboardingStep, keepForm: Boolean) {
        if (keepForm) _state.update { it.copy(form = revert(it.form, from.fields)) }
        if (from.isLast) {
            saved.remove<String>(KEY_STEP)
            _state.update { it.copy(finished = true) }
        } else {
            setStep(OnboardingStep.entries[from.ordinal + 1])
            _state.update { it.copy(errors = emptyMap()) }
        }
    }

    private fun revert(form: ProfileForm, fields: Set<ProfileField>): ProfileForm {
        val base = initial.withUnits(form.units)
        var f = form
        for (k in fields) f = when (k) {
            ProfileField.NAME -> f.copy(displayName = base.displayName)
            ProfileField.BIRTH_YEAR -> f.copy(birthYear = base.birthYear)
            ProfileField.SEX -> f.copy(sex = base.sex)
            ProfileField.UNITS -> f.withUnits(initial.units)
            ProfileField.HEIGHT -> f.copy(heightCm = base.heightCm, heightFt = base.heightFt, heightIn = base.heightIn)
            ProfileField.WEIGHT -> f.copy(weight = base.weight)
            ProfileField.ACTIVITY -> f.copy(activity = base.activity)
            ProfileField.EXPERIENCE -> f.copy(experience = base.experience)
            ProfileField.DIET -> f.copy(diet = base.diet)
            ProfileField.CALORIES -> f.copy(calories = base.calories)
            ProfileField.WATER -> f.copy(water = base.water)
        }
        return f
    }

    private fun setStep(step: OnboardingStep) {
        saved[KEY_STEP] = step.name
        _state.update { it.copy(step = step) }
    }

    private companion object {
        const val KEY_STEP = "onboarding_step"
    }
}

internal fun changedKeys(a: ProfileForm, b: ProfileForm): Set<ProfileField> = buildSet {
    if (a.birthYear != b.birthYear) add(ProfileField.BIRTH_YEAR)
    if (a.heightCm != b.heightCm || a.heightFt != b.heightFt || a.heightIn != b.heightIn || a.units != b.units) add(ProfileField.HEIGHT)
    if (a.weight != b.weight || a.units != b.units) add(ProfileField.WEIGHT)
    if (a.calories != b.calories) add(ProfileField.CALORIES)
    if (a.water != b.water) add(ProfileField.WATER)
}

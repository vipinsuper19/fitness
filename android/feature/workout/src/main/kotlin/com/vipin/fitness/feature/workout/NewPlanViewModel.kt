package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.GoalType
import com.vipin.fitness.core.data.workout.PlanGenerateRequest
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

enum class NewPlanMode { GENERATE, CUSTOM }

data class GenerateForm(
    val goal: GoalType = GoalType.MAINTAIN,
    val experience: ExperienceLevel = ExperienceLevel.BEGINNER,
    val daysPerWeek: Int = 3,
    val sessionMinutes: Int = 45,
    /** Empty = bodyweight only. */
    val equipment: Set<Equipment> = emptySet(),
    val weeks: Int = 4,
)

data class NewPlanState(
    val mode: NewPlanMode = NewPlanMode.GENERATE,
    val form: GenerateForm = GenerateForm(),
    val generating: Boolean = false,
    val error: String? = null,
    val customErrors: Set<CustomPlanError> = emptySet(),
    /** One-shot: navigate to preview, then call [NewPlanViewModel.onNavigatedToPreview]. */
    val openPreview: Boolean = false,
)

/** Start New Plan: rule-based generator form, or the custom builder (state in [PlanBuilderStore]). */
@HiltViewModel
class NewPlanViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val store: PlanBuilderStore,
    profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NewPlanState())
    val state: StateFlow<NewPlanState> = _state.asStateFlow()
    val custom = store.custom

    init {
        // Pre-fill experience from the profile when known; the user can change it.
        viewModelScope.launch {
            val level = (profiles.get() as? ApiResult.Success)?.value?.experienceLevel ?: return@launch
            _state.update { if (it.form == GenerateForm()) it.copy(form = it.form.copy(experience = level)) else it }
        }
    }

    fun setMode(mode: NewPlanMode) = _state.update { it.copy(mode = mode, error = null) }
    fun onForm(edit: (GenerateForm) -> GenerateForm) = _state.update {
        val f = edit(it.form)
        it.copy(
            form = f.copy(daysPerWeek = f.daysPerWeek.coerceIn(1, 7), sessionMinutes = f.sessionMinutes.coerceIn(15, 180), weeks = f.weeks.coerceIn(1, 16)),
            error = null,
        )
    }

    fun toggleEquipment(e: Equipment) = onForm { f -> f.copy(equipment = if (e in f.equipment) f.equipment - e else f.equipment + e) }

    fun generate() {
        val s = _state.value
        if (s.generating) return
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            val f = s.form
            when (val r = repo.generate(PlanGenerateRequest(f.goal, f.experience, f.daysPerWeek, f.sessionMinutes, f.equipment, f.weeks))) {
                is ApiResult.Success -> {
                    store.setPreview(r.value)
                    _state.update { it.copy(generating = false, openPreview = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(generating = false, error = r.error.userMessage()) }
            }
        }
    }

    // Custom builder
    fun setCustomName(name: String) = store.setName(name).also { clearCustomErrors() }
    fun setCustomWeeks(weeks: Int) = store.setWeeks(weeks)
    fun toggleDay(day: DayOfWeek) = store.toggleDay(day, day.fullName()).also { clearCustomErrors() }
    fun renameDay(day: DayOfWeek, name: String) = store.renameDay(day, name).also { clearCustomErrors() }
    fun updateExercise(day: DayOfWeek, index: Int, edit: (CustomExercise) -> CustomExercise) =
        store.updateExercise(day, index, edit).also { clearCustomErrors() }
    fun removeExercise(day: DayOfWeek, index: Int) = store.removeExercise(day, index)
    fun moveExercise(day: DayOfWeek, index: Int, by: Int) = store.moveExercise(day, index, by)

    fun previewCustom() {
        val plan = store.custom.value
        val errors = plan.errors()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(customErrors = errors) }
            return
        }
        store.setPreview(plan.toDraft())
        _state.update { it.copy(openPreview = true, customErrors = emptySet()) }
    }

    fun onNavigatedToPreview() = _state.update { it.copy(openPreview = false) }

    private fun clearCustomErrors() = _state.update { it.copy(customErrors = emptySet()) }
}

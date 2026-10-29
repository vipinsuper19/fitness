package com.vipin.fitness.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.GoalChanges
import com.vipin.fitness.core.data.GoalProgress
import com.vipin.fitness.core.data.GoalProgressService
import com.vipin.fitness.core.data.GoalStatus
import com.vipin.fitness.core.data.GoalType
import com.vipin.fitness.core.data.GoalsRepository
import com.vipin.fitness.core.data.NewGoal
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.isRetryable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vipin.fitness.core.data.UnitPreference
import java.time.Clock
import java.time.ZoneId
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.today
import java.time.LocalDate
import javax.inject.Inject

enum class GoalFormError { TARGET, START, DATE }

/** New-goal form. Weights are typed in the user's units; dates as yyyy-mm-dd. */
data class GoalForm(
    val type: GoalType = GoalType.LOSE_WEIGHT,
    val target: String = "",
    val start: String = "",
    val date: String = "",
    val errors: Set<GoalFormError> = emptySet(),
)

data class GoalsState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<GoalProgress> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
    val form: GoalForm? = null,
    val saving: Boolean = false,
    /** A failed write (create/update/delete), shown above the list. */
    val writeError: String? = null,
) {
    val active get() = items.filter { it.goal.status == GoalStatus.ACTIVE }
    val past get() = items.filter { it.goal.status != GoalStatus.ACTIVE }
}

/** Goals tab: list with on-device progress and milestones, add, mark achieved / abandon / reactivate, delete. */
@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goals: GoalsRepository,
    private val service: GoalProgressService,
    private val unitPref: UnitPreference,
    private val clock: Clock,
    private val profileZone: ProfileZone,
) : ViewModel() {
    /** Day boundary for "not in the past": profile timezone, APP_ZONE until the profile loads. */
    private var zone: ZoneId = APP_ZONE
    private val _state = MutableStateFlow(GoalsState())
    val state: StateFlow<GoalsState> = _state.asStateFlow()
    /** Same key while retrying the same create. */
    private var pendingCreate: Pair<NewGoal, String>? = null

    init {
        viewModelScope.launch { unitPref.units.collect { u -> _state.update { it.copy(units = u) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            zone = profileZone.zone()
            when (val r = service.load(status = null)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, items = r.value) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun openNew() = viewModelScope.launch {
        val units = unitPref.units.first()
        // Start weight defaults to the latest logged weight, when progress knows it.
        val latest = _state.value.items.firstNotNullOfOrNull { p -> p.current.takeIf { p.goal.type.isWeight } }
        val start = latest?.let { ProgressFormat.input(UnitConversion.displayWeight(it, units)!!) }.orEmpty()
        _state.update { it.copy(form = GoalForm(start = start), writeError = null) }
    }

    fun closeForm() = _state.update { it.copy(form = null) }
    fun onForm(edit: (GoalForm) -> GoalForm) = _state.update { s -> s.copy(form = s.form?.let { edit(it).copy(errors = emptySet()) }) }

    fun save() {
        val s = _state.value
        val f = s.form ?: return
        if (s.saving) return
        val (goal, errors) = validate(f, s.units, clock.today(zone))
        if (goal == null) {
            _state.update { it.copy(form = f.copy(errors = errors)) }
            return
        }
        val key = pendingCreate?.takeIf { it.first == goal }?.second ?: newIdempotencyKey()
        pendingCreate = goal to key
        write { goals.create(goal, key).also { if (it is ApiResult.Success) { pendingCreate = null; _state.update { s2 -> s2.copy(form = null) } } } }
    }

    fun setStatus(id: String, status: GoalStatus) = keyed("$id:status:$status") { goals.update(id, GoalChanges(status = status), it) }
    fun delete(id: String) = keyed("$id:delete") { goals.delete(id, it) }

    /** Same Idempotency-Key while retrying the same action after a retryable failure (offline, 429, 5xx). */
    private val actionKeys = mutableMapOf<String, String>()

    private fun keyed(slot: String, call: suspend (String) -> ApiResult<*>) {
        val key = actionKeys.getOrPut(slot) { newIdempotencyKey() }
        write {
            call(key).also { r -> if (!(r is ApiResult.Failure && r.error.isRetryable)) actionKeys.remove(slot) }
        }
    }

    private fun write(block: suspend () -> ApiResult<*>) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, writeError = null) }
        viewModelScope.launch {
            val r = block()
            _state.update { it.copy(saving = false, writeError = (r as? ApiResult.Failure)?.error?.userMessage()) }
            if (r is ApiResult.Success) load()
        }
    }
}

/** Mirrors the server's ranges: weights 20–400 kg, workouts a whole 1–7, target date not in the past. */
internal fun validate(f: GoalForm, units: UnitSystem, today: LocalDate): Pair<NewGoal?, Set<GoalFormError>> {
    val errors = mutableSetOf<GoalFormError>()
    fun weight(text: String, err: GoalFormError, required: Boolean): Double? {
        if (text.isBlank()) { if (required) errors += err; return null }
        val kg = text.toDecimalOrNull()?.let { UnitConversion.weightToKg(it, units) }?.let { com.vipin.fitness.core.data.roundMetric(it) }
        if (kg == null || kg < 20 || kg > 400) errors += err
        return kg
    }
    var target: Double? = null
    var start: Double? = null
    when {
        f.type.isWeight -> {
            target = weight(f.target, GoalFormError.TARGET, required = true)
            if (f.type != GoalType.MAINTAIN) start = weight(f.start, GoalFormError.START, required = true)
        }
        f.type == GoalType.WORKOUTS_PER_WEEK -> {
            target = f.target.trim().toIntOrNull()?.takeIf { it in 1..7 }?.toDouble()
            if (target == null) errors += GoalFormError.TARGET
        }
    }
    val date = if (f.date.isBlank()) null else runCatching { LocalDate.parse(f.date.trim()) }.getOrNull()
    if (f.date.isNotBlank() && (date == null || date < today)) errors += GoalFormError.DATE
    return if (errors.isEmpty()) NewGoal(f.type, target, start, date) to errors else null to errors
}

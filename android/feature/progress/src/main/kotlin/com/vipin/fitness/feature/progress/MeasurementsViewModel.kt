package com.vipin.fitness.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.BodyMeasurement
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.MeasurementChanges
import com.vipin.fitness.core.data.MeasurementKind
import com.vipin.fitness.core.data.NewMeasurement
import com.vipin.fitness.core.data.ProgressRepository
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.designsystem.UiText
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vipin.fitness.core.common.time.today
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Add/edit form. Texts are in the user's units as of opening ([units]). For an edit, only the
 * fields whose text differs from what was shown are sent, so unit round-trips never send noise.
 */
data class MeasurementForm(
    val original: BodyMeasurement? = null,
    val date: LocalDate,
    val units: UnitSystem,
    val texts: Map<MeasurementKind, String>,
    val initialTexts: Map<MeasurementKind, String> = texts,
    val errors: Set<String> = emptySet(),
) {
    val isEdit get() = original != null

    companion object {
        fun new(date: LocalDate, units: UnitSystem) =
            MeasurementForm(date = date, units = units, texts = MeasurementKind.entries.associateWith { "" })

        fun edit(m: BodyMeasurement, units: UnitSystem) = MeasurementForm(
            original = m,
            date = m.date,
            units = units,
            texts = MeasurementKind.entries.associateWith { k ->
                m.values[k]?.let { ProgressFormat.input(toDisplay(k.dimension, it, units), if (units == UnitSystem.METRIC) 2 else 1) }.orEmpty()
            },
        )
    }
}

data class MeasurementsState(
    val today: LocalDate,
    val units: UnitSystem = UnitSystem.METRIC,
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val items: List<BodyMeasurement> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val form: MeasurementForm? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
)

/** Body measurements: list (newest first), add, partial edit and delete. */
@HiltViewModel
class MeasurementsViewModel @Inject constructor(
    private val repo: ProgressRepository,
    units: UnitPreference,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(MeasurementsState(today = clock.today()))
    val state: StateFlow<MeasurementsState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey
    private val keys = RetryKeys { newKey() }
    private var loadJob: Job? = null
    /** Separate from [loadJob], so a reload never strands an in-flight page with loadingMore = true. */
    private var pageJob: Job? = null

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        load()
    }

    fun load() {
        loadJob?.cancel()
        pageJob?.cancel() // the reload replaces the list and cursor; a stale page must not append
        _state.update { it.copy(loading = it.items.isEmpty(), loadError = null, loadingMore = false) }
        loadJob = viewModelScope.launch {
            when (val r = repo.measurements()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, items = r.value.items, nextCursor = r.value.nextCursor) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore || s.loading) return
        _state.update { it.copy(loadingMore = true) }
        pageJob = viewModelScope.launch {
            when (val r = repo.measurements(cursor)) {
                is ApiResult.Success -> _state.update {
                    it.copy(loadingMore = false, items = (it.items + r.value.items).distinctBy { m -> m.id }, nextCursor = r.value.nextCursor)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    /** Called when the screen resumes: a new form still on the old today moves to the new one. */
    fun onResume() {
        val now = clock.today()
        val s = _state.value
        if (now == s.today) return
        _state.update {
            val form = it.form?.takeIf { f -> !f.isEdit && f.date == s.today }?.copy(date = now) ?: it.form
            it.copy(today = now, form = form)
        }
    }

    fun startAdd() {
        val today = clock.today()
        _state.update { it.copy(today = today, form = MeasurementForm.new(today, it.units), error = null) }
    }

    fun startEdit(m: BodyMeasurement) = _state.update { it.copy(today = clock.today(), form = MeasurementForm.edit(m, it.units), error = null) }
    fun cancel() = _state.update { it.copy(form = null) }

    fun onText(kind: MeasurementKind, text: String) = updateForm { it.copy(texts = it.texts + (kind to text.take(9))) }

    fun previousDate() = updateForm { it.copy(date = it.date.minusDays(1)) }
    fun nextDate() {
        val today = clock.today()
        _state.update { it.copy(today = today) }
        updateForm { if (it.date < today) it.copy(date = it.date.plusDays(1)) else it }
    }

    private fun updateForm(change: (MeasurementForm) -> MeasurementForm) =
        _state.update { s -> s.copy(form = s.form?.let(change)?.copy(errors = emptySet())) }

    fun save() {
        val s = _state.value
        val form = s.form ?: return
        if (s.saving) return
        if (form.date > clock.today()) {
            _state.update { it.copy(form = form.copy(errors = setOf(FIELD_DATE))) }
            return
        }
        val original = form.original
        if (original == null) saveNew(form) else saveEdit(form, original)
    }

    private fun saveNew(form: MeasurementForm) {
        val errors = mutableSetOf<String>()
        val values = buildMap {
            form.texts.forEach { (k, t) ->
                if (t.isBlank()) return@forEach
                val v = parse(k, t, form.units)
                if (v != null) put(k, v) else errors.add(k.wire)
            }
        }
        if (errors.isEmpty() && values.isEmpty()) errors += FIELD_NONE
        if (errors.isNotEmpty()) {
            _state.update { it.copy(form = form.copy(errors = errors)) }
            return
        }
        val m = NewMeasurement(form.date, values)
        write(SLOT_ADD, m) { key -> repo.addMeasurement(m, key) }
    }

    private fun saveEdit(form: MeasurementForm, original: BodyMeasurement) {
        val errors = mutableSetOf<String>()
        val changed = buildMap<MeasurementKind, Field<Double>> {
            form.texts.forEach { (k, t) ->
                if (t.trim() == form.initialTexts[k].orEmpty().trim()) return@forEach
                when {
                    t.isBlank() -> if (k in original.values) put(k, Field.Clear)
                    else -> {
                        val v = parse(k, t, form.units)
                        if (v != null) put(k, Field.Set(v)) else errors.add(k.wire)
                    }
                }
            }
        }
        val remaining = original.values.keys - changed.filterValues { it == Field.Clear }.keys + changed.filterValues { it is Field.Set }.keys
        if (errors.isEmpty() && remaining.isEmpty()) errors += FIELD_NONE
        if (errors.isNotEmpty()) {
            _state.update { it.copy(form = form.copy(errors = errors)) }
            return
        }
        val changes = MeasurementChanges(date = form.date.takeIf { it != original.date }, values = changed)
        if (changes.isEmpty) {
            cancel() // nothing changed: no request (the server rejects {})
            return
        }
        write("$SLOT_EDIT-${original.id}", changes) { key -> repo.updateMeasurement(original.id, changes, key) }
    }

    fun delete(id: String) = write("$SLOT_DELETE-$id", id) { key -> repo.deleteMeasurement(id, key) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Parses one field in the form's units, converts to metric and checks the contract range. */
    private fun parse(kind: MeasurementKind, text: String, units: UnitSystem): Double? =
        text.toDecimalOrNull()?.let { toMetric(kind.dimension, it, units) }?.takeIf { kind.inRange(it) }

    /** Runs one write with a retry-stable key, then reloads the list from the server. */
    private fun write(slot: String, signature: Any, call: suspend (String) -> ApiResult<*>) {
        if (_state.value.saving) return
        val key = keys.keyFor(slot, signature)
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = call(key)) {
                is ApiResult.Success -> {
                    keys.done(slot)
                    _state.update { it.copy(saving = false, form = null) }
                    load()
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    companion object {
        const val FIELD_NONE = "none"
        const val FIELD_DATE = "date"
        private const val SLOT_ADD = "add"
        private const val SLOT_EDIT = "edit"
        private const val SLOT_DELETE = "delete"
    }
}

package com.vipin.fitness.feature.onboarding

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.NotificationPreferences
import com.vipin.fitness.core.data.NotificationPreferencesChanges
import com.vipin.fitness.core.data.NotificationPreferencesRepository
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.data.QuietHours
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NotificationError {
    OFFLINE,
    FAILED,
    /** 429: retryable; the retry reuses the same Idempotency-Key. */
    RATE_LIMITED,
    /** 400: terminal; shown, then the screen refetches. */
    INVALID,
    /** Client-side: quiet-hours start == end, never sent. */
    SAME_TIMES,
}

internal fun ApiError.toNotificationError() = when {
    this == ApiError.Network -> NotificationError.OFFLINE
    this is ApiError.Http && status == 400 -> NotificationError.INVALID
    this is ApiError.Http && status == 429 -> NotificationError.RATE_LIMITED
    else -> NotificationError.FAILED
}

data class NotificationSettingsState(
    val loading: Boolean = true,
    val loadError: NotificationError? = null,
    /** Last server truth. */
    val saved: NotificationPreferences? = null,
    /** What the screen shows; differs from [saved] until saved. */
    val draft: NotificationPreferences? = null,
    val saving: Boolean = false,
    val saveError: NotificationError? = null,
    val savedOk: Boolean = false,
) {
    val changes: NotificationPreferencesChanges
        get() = if (saved != null && draft != null) NotificationPreferencesChanges.between(saved, draft) else NotificationPreferencesChanges()
    val dirty get() = !changes.isEmpty
    val sameTimes get() = draft?.quietHours?.let { it.start == it.end } == true
}

/** GET/PATCH /me/notification-preferences. Storage only: no FCM, device registration or permission prompts. */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val repo: NotificationPreferencesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationSettingsState())
    val state: StateFlow<NotificationSettingsState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey
    /** Bumped per load so an older response is dropped. */
    private var loadGen = 0
    /** One key per set of changes: a retry of the same PATCH reuses it. */
    private var pending: Pair<NotificationPreferencesChanges, String>? = null
    private var user = repo.userGeneration.value

    init {
        viewModelScope.launch {
            repo.userGeneration.collect { g ->
                if (g == user) return@collect
                // Another user: forget everything from the previous one.
                user = g
                pending = null
                _state.value = NotificationSettingsState()
                load()
            }
        }
        load()
    }

    fun load() {
        val gen = ++loadGen
        val u = user
        _state.update { it.copy(loading = it.saved == null, loadError = null) }
        viewModelScope.launch {
            val r = repo.get()
            if (gen != loadGen || u != user) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, saved = r.value, draft = r.value)
                    is ApiResult.Failure -> it.copy(loading = false, loadError = r.error.toNotificationError())
                }
            }
        }
    }

    private fun edit(f: (NotificationPreferences) -> NotificationPreferences) = _state.update { s ->
        if (s.draft == null || s.saving) s
        else s.copy(draft = f(s.draft), savedOk = false, saveError = s.saveError?.takeIf { it != NotificationError.SAME_TIMES })
    }

    /** Off keeps every per-type choice. */
    fun setEnabled(on: Boolean) = edit { it.copy(enabled = on) }

    fun setType(type: NotificationType, on: Boolean) = edit { p ->
        if (type in p.types) p.copy(types = p.types + (type to on)) else p
    }

    fun setQuietEnabled(on: Boolean) = edit { it.copy(quietHours = it.quietHours.copy(enabled = on)) }

    fun setQuietStart(hhmm: String) = setWindow { it.copy(start = hhmm) }

    fun setQuietEnd(hhmm: String) = setWindow { it.copy(end = hhmm) }

    private fun setWindow(f: (QuietHours) -> QuietHours) = edit { p ->
        val q = f(p.quietHours)
        if (NotificationFormat.isValidTime(q.start) && NotificationFormat.isValidTime(q.end)) p.copy(quietHours = q) else p
    }

    /** Back to the last saved values. */
    fun discard() = _state.update { it.copy(draft = it.saved, saveError = null) }

    fun save() {
        val s = _state.value
        if (s.saving || s.saved == null) return
        if (s.sameTimes) {
            _state.update { it.copy(saveError = NotificationError.SAME_TIMES) }
            return
        }
        val changes = s.changes
        if (changes.isEmpty) return
        val key = pending?.takeIf { it.first == changes }?.second ?: newKey()
        pending = changes to key
        val u = user
        _state.update { it.copy(saving = true, saveError = null, savedOk = false) }
        viewModelScope.launch {
            val r = repo.update(changes, key)
            if (u != user) return@launch
            when (r) {
                is ApiResult.Success -> {
                    pending = null
                    loadGen++ // a load still in flight is older than this result
                    _state.update {
                        it.copy(saving = false, savedOk = true, loading = false, loadError = null, saved = r.value, draft = r.value)
                    }
                }
                is ApiResult.Failure -> {
                    val e = r.error.toNotificationError()
                    _state.update { it.copy(saving = false, saveError = e) }
                    if (e == NotificationError.INVALID) {
                        // Terminal: never retried with this key; refetch the server's view.
                        pending = null
                        load()
                    }
                }
            }
        }
    }
}

/** Times, durations and labels for the screen. Unit-free; everything else from resources. */
internal object NotificationFormat {
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun isValidTime(s: String) = TIME.matches(s)

    fun time(hour: Int, minute: Int): String {
        require(hour in 0..23 && minute in 0..59) { "bad time $hour:$minute" }
        return "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
    }

    /** (hour, minute), or null when not HH:MM. */
    fun parse(s: String): Pair<Int, Int>? = if (isValidTime(s)) s.substring(0, 2).toInt() to s.substring(3, 5).toInt() else null

    /** Length of `[start, end)` in minutes; crosses midnight when start > end; 0 when equal. */
    fun windowMinutes(start: String, end: String): Int {
        val (sh, sm) = parse(start) ?: return 0
        val (eh, em) = parse(end) ?: return 0
        return Math.floorMod((eh * 60 + em) - (sh * 60 + sm), 24 * 60)
    }

    fun crossesMidnight(start: String, end: String) = start > end

    fun duration(res: Resources, minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val hours = res.getQuantityString(R.plurals.notif_hours, h, h)
        val mins = res.getQuantityString(R.plurals.notif_minutes, m, m)
        return when {
            h == 0 -> mins
            m == 0 -> hours
            else -> res.getString(R.string.notif_duration_hm, hours, mins)
        }
    }

    fun window(res: Resources, q: QuietHours): String {
        val len = duration(res, windowMinutes(q.start, q.end))
        return if (crossesMidnight(q.start, q.end)) res.getString(R.string.notif_quiet_window_overnight, q.start, q.end, len)
        else res.getString(R.string.notif_quiet_window, q.start, q.end, len)
    }

    fun typesOn(res: Resources, p: NotificationPreferences): String {
        val on = p.types.count { it.value }
        return res.getQuantityString(R.plurals.notif_types_on, on, on, p.types.size)
    }

    fun label(res: Resources, t: NotificationType): String = res.getString(
        when (t) {
            NotificationType.WORKOUT_REMINDER -> R.string.notif_type_workout_reminder
            NotificationType.STREAK_REMINDER -> R.string.notif_type_streak_reminder
            NotificationType.WATER_REMINDER -> R.string.notif_type_water_reminder
            NotificationType.FOOD_LOG_REMINDER -> R.string.notif_type_food_log_reminder
            NotificationType.CHALLENGE_UPDATES -> R.string.notif_type_challenge_updates
            NotificationType.BADGE_EARNED -> R.string.notif_type_badge_earned
            NotificationType.PROGRESSION_SUGGESTION -> R.string.notif_type_progression_suggestion
        },
    )

    fun error(res: Resources, e: NotificationError): String = res.getString(
        when (e) {
            NotificationError.OFFLINE -> R.string.notif_error_offline
            NotificationError.FAILED -> R.string.notif_error_failed
            NotificationError.RATE_LIMITED -> R.string.notif_error_rate_limited
            NotificationError.INVALID -> R.string.notif_error_invalid
            NotificationError.SAME_TIMES -> R.string.notif_error_same_times
        },
    )
}

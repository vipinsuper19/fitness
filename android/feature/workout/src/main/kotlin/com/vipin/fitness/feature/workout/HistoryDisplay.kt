package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.zoneOrDefault
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import com.vipin.fitness.core.common.format.IndianNumberFormat
import java.time.ZoneId
import javax.inject.Inject

/** How history shows times and weights: the profile's zone and units. */
data class HistoryDisplay(val zone: ZoneId = APP_ZONE, val units: UnitSystem = UnitSystem.METRIC)

/** Units from the cached preference (live); zone from one profile fetch, falling back to [APP_ZONE]. */
class HistoryDisplaySource @Inject constructor(
    private val profiles: ProfileRepository,
    private val unitPreference: UnitPreference,
) {
    val display: Flow<HistoryDisplay> = combine(
        unitPreference.units,
        flow {
            emit(APP_ZONE)
            val r = profiles.get()
            if (r is ApiResult.Success) emit(r.value.zoneOrDefault())
        },
    ) { units, zone -> HistoryDisplay(zone, units) }
}

/** Shared en-IN formatter (half-up, lakh grouping, never "-0"), at most one decimal. */
internal object HistoryFormat {
    fun number(v: Double): String = IndianNumberFormat.number(v, 1)
}

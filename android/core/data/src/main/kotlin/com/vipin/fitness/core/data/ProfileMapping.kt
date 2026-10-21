package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.network.model.ProfileDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Unknown enum values from a newer server read as "not set" rather than crashing. */
private inline fun <reified E : Enum<E>> String?.toEnum(wire: (E) -> String): E? =
    this?.let { v -> enumValues<E>().firstOrNull { wire(it) == v } }

fun ProfileDto.toDomain() = Profile(
    displayName = displayName,
    birthYear = birthYear,
    sex = sex.toEnum<Sex> { it.wire },
    heightCm = heightCm,
    weightKg = weightKg,
    activityLevel = activityLevel.toEnum<ActivityLevel> { it.wire },
    experienceLevel = experienceLevel.toEnum<ExperienceLevel> { it.wire },
    dietPreference = dietPreference.toEnum<DietPreference> { it.wire },
    unitSystem = if (unitSystem == "imperial") UnitSystem.IMPERIAL else UnitSystem.METRIC,
    dailyCalorieTargetKcal = dailyCalorieTargetKcal,
    dailyWaterTargetMl = dailyWaterTargetMl,
    onboardingCompleted = onboardingCompleted,
)

fun UnitSystem.wire() = if (this == UnitSystem.IMPERIAL) "imperial" else "metric"

/** Builds the PUT body: Unchanged keys are omitted, Clear is JSON null, Set is the value. */
fun ProfileChanges.toJson(): JsonObject = buildJsonObject {
    field("displayName", displayName) { JsonPrimitive(it) }
    field("birthYear", birthYear) { JsonPrimitive(it) }
    field("sex", sex) { JsonPrimitive(it.wire) }
    field("heightCm", heightCm) { JsonPrimitive(it) }
    field("weightKg", weightKg) { JsonPrimitive(it) }
    field("activityLevel", activityLevel) { JsonPrimitive(it.wire) }
    field("experienceLevel", experienceLevel) { JsonPrimitive(it.wire) }
    field("dietPreference", dietPreference) { JsonPrimitive(it.wire) }
    unitSystem?.let { put("unitSystem", JsonPrimitive(it.wire())) }
    field("dailyCalorieTargetKcal", dailyCalorieTargetKcal) { JsonPrimitive(it) }
    field("dailyWaterTargetMl", dailyWaterTargetMl) { JsonPrimitive(it) }
    onboardingCompleted?.let { put("onboardingCompleted", JsonPrimitive(it)) }
}

private fun <T> JsonObjectBuilder.field(key: String, f: Field<T>, encode: (T) -> JsonPrimitive) {
    when (f) {
        Field.Unchanged -> Unit
        Field.Clear -> put(key, JsonNull)
        is Field.Set -> put(key, encode(f.value))
    }
}

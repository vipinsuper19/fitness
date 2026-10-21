package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Plans reference exercises by id. Looks up the names (repository cache first). Missing ones
 * (network error, removed from catalog) are left out; the UI falls back to a placeholder.
 */
internal suspend fun WorkoutRepository.namesFor(ids: Collection<String>): Map<String, String> = coroutineScope {
    ids.distinct().map { id -> async { id to (exercise(id) as? ApiResult.Success)?.value?.name } }
        .awaitAll()
        .mapNotNull { (id, name) -> name?.let { id to it } }
        .toMap()
}

package com.vipin.fitness.feature.achievements

import android.content.res.Resources
import com.vipin.fitness.core.common.format.IndianNumberFormat
import com.vipin.fitness.core.data.achievements.Challenge

/** Test tags for the water lines on challenge cards (active and history). */
object ChallengeTags {
    /** "Goal at join: 2,500 ml (for information only)"; only when the server sent waterGoalMl. */
    fun waterGoal(id: String) = "water_goal_$id"

    /** One line: each day is judged against that day's own water target, else 2000 ml. */
    fun waterRule(id: String) = "water_rule_$id"
}

internal object ChallengeFormat {
    /** The water goal at join in ml (en-IN grouping), or null when there is none. Information only. */
    fun waterGoalAtJoin(res: Resources, c: Challenge): String? =
        c.waterGoalMl?.let { res.getString(R.string.challenge_water_goal_at_join, IndianNumberFormat.integer(it)) }
}

package com.vipin.fitness.ui

/**
 * PROPOSED (test-first). Neither screen is on main yet:
 * - XP ledger: no screen exists. These tag values are a proposal; move them into the feature
 *   module and use them in Modifier.testTag(...). Data: GET /xp/ledger (newest first, cursor
 *   paging, XpTransaction {id, sourceType, sourceId, amount (negative for reversals), reason, createdAt}).
 * - Goals (#76, merged): real tag values used in GoalsScreen/ProgressScreen/HomeScreen.
 *
 * The seeds below stand in for the fakes until the real ones exist. When a screen lands,
 * replace them with @Inject of the real fake (#76: core.testing.FakeGoalsRepository via
 * FakeGoalsModule) and remove the @Ignore.
 */
object XpLedgerTags {
    /** On the existing Home XP card (slot_xp): opens the ledger. */
    const val OPEN = "xp_open_ledger"
    const val SCREEN = "xp_ledger_screen"
    const val LIST = "xp_ledger_list"
    const val EMPTY = "xp_ledger_empty"
    const val ERROR = "xp_ledger_error"
    const val RETRY = "xp_ledger_retry"
    const val BALANCE = "xp_ledger_balance"
    fun row(id: String) = "xp_row_$id"
    fun amount(id: String) = "xp_amount_$id"
}

object GoalsTags {
    const val TAB = "progress_tab_goals"
    const val ADD = "goal_add"
    const val EMPTY = "goal_empty"
    const val WRITE_ERROR = "goal_write_error"
    const val TARGET = "goal_target"
    const val START = "goal_start"
    const val DATE = "goal_date"
    const val SAVE = "goal_save"
    const val HOME_SLOT = "slot_goals"
    fun card(id: String) = "goal_$id"
    fun bar(id: String) = "goal_bar_$id"
    fun milestone(id: String) = "goal_milestone_$id"
    fun achieved(id: String) = "goal_achieved_$id"
    fun abandon(id: String) = "goal_abandon_$id"
    fun reactivate(id: String) = "goal_reactivate_$id"
    fun delete(id: String) = "goal_delete_$id"
    fun type(wire: String) = "goal_type_$wire"
    fun homeBar(id: String) = "home_goal_bar_$id"
}

/** Stand-in for a FakeXpLedgerRepository. */
data class LedgerRow(val id: String, val sourceType: String, val amount: Int, val reason: String, val createdAt: String)
class PendingXpLedgerSeed {
    /** cursor (null = first page) → (items, nextCursor); a missing cursor means a network failure. */
    val pages = mutableMapOf<String?, Pair<List<LedgerRow>, String?>>()
    val calls = mutableListOf<String?>()
}

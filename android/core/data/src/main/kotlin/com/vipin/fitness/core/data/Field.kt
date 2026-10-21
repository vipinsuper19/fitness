package com.vipin.fitness.core.data

/**
 * Explicit presence for partial updates: [Unchanged] leaves the server value alone (key omitted),
 * [Clear] sends JSON null, [Set] sends the value. Avoids `T?` where null would be ambiguous.
 */
sealed interface Field<out T> {
    data object Unchanged : Field<Nothing>
    data object Clear : Field<Nothing>
    data class Set<T>(val value: T) : Field<T>

    companion object {
        /** Set when non-null, Clear when null. Handy for form fields the user emptied. */
        fun <T : Any> of(value: T?): Field<T> = if (value == null) Clear else Set(value)
    }
}

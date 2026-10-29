package com.vipin.fitness.feature.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Minimum touch target (Material/Android accessibility guideline). */
internal val MIN_TOUCH = 48.dp

/**
 * A tappable list row: at least [MIN_TOUCH] tall, announced as a button with [label]
 * as its action ("double-tap to Edit entry") instead of an unlabeled click.
 */
internal fun Modifier.tappableRow(label: String, enabled: Boolean, onClick: () -> Unit): Modifier =
    heightIn(min = MIN_TOUCH).clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)

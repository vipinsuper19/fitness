package com.vipin.fitness.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.core.designsystem.SettingsLinkTags

/** Profile/settings: help, feedback (email) and legal pages. */
@Composable
fun SettingsLinks() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(stringResource(R.string.settings_help_legal), style = MaterialTheme.typography.titleSmall)
        Link(R.string.settings_help, SettingsLinkTags.HELP) { uri.openUri(LegalLinks.help) }
        Link(R.string.settings_feedback, SettingsLinkTags.FEEDBACK) { uri.openUri(LegalLinks.feedbackMailto) }
        Link(R.string.legal_privacy, SettingsLinkTags.PRIVACY) { uri.openUri(LegalLinks.privacy) }
        Link(R.string.legal_terms, SettingsLinkTags.TERMS) { uri.openUri(LegalLinks.terms) }
    }
}

@Composable
private fun Link(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(stringResource(label)) }
}

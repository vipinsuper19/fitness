package com.vipin.fitness.feature.onboarding

/** Help, feedback and legal destinations (BuildConfig placeholders until the real pages exist). */
object LegalLinks {
    val privacy: String = BuildConfig.PRIVACY_URL
    val terms: String = BuildConfig.TERMS_URL
    val help: String = BuildConfig.HELP_URL
    val feedbackMailto: String = "mailto:${BuildConfig.FEEDBACK_EMAIL}?subject=Fitness%20app%20feedback"
}

package com.vipin.fitness.ui

import com.vipin.fitness.core.data.MacroSuggestionRepository
import com.vipin.fitness.core.data.di.MacroSuggestionDataModule
import com.vipin.fitness.core.testing.FakeMacroSuggestionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: macro suggestions (#120) never reach a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [MacroSuggestionDataModule::class])
abstract class FakeMacroSuggestionModule {
    @Binds abstract fun suggestions(f: FakeMacroSuggestionRepository): MacroSuggestionRepository
    companion object {
        @Provides @Singleton fun fakeSuggestions() = FakeMacroSuggestionRepository()
    }
}

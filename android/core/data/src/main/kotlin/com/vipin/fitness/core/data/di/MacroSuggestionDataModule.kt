package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultMacroSuggestionRepository
import com.vipin.fitness.core.data.MacroSuggestionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MacroSuggestionDataModule {
    @Binds abstract fun suggestion(impl: DefaultMacroSuggestionRepository): MacroSuggestionRepository
}

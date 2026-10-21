package com.vipin.fitness.core.data.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.vipin.fitness.core.data.ConsentRepository
import com.vipin.fitness.core.data.DataStoreConsentRepository
import com.vipin.fitness.core.data.ProfileSignedInUserId
import com.vipin.fitness.core.data.UserScopedState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.Clock
import javax.inject.Singleton

/** Consent lives in its own small DataStore file ("consent"), separate from user_prefs. */
@Module
@InstallIn(SingletonComponent::class)
object ConsentDataModule {
    @Provides @Singleton
    fun consentRepository(@ApplicationContext context: Context, clock: Clock, user: ProfileSignedInUserId): DataStoreConsentRepository =
        DataStoreConsentRepository(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("consent") }, clock, user)

    @Provides fun consent(impl: DataStoreConsentRepository): ConsentRepository = impl
    @Provides @IntoSet fun consentUserState(impl: DataStoreConsentRepository): UserScopedState = impl
}

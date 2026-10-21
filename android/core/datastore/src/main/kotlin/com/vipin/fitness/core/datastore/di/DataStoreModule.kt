package com.vipin.fitness.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.vipin.fitness.core.datastore.EncryptedTokenStore
import com.vipin.fitness.core.datastore.AesGcmTokenCipher
import com.vipin.fitness.core.datastore.KeystoreKeyProvider
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.datastore.SecretKeyProvider
import com.vipin.fitness.core.datastore.TokenCipher
import com.vipin.fitness.core.datastore.UserPreferencesRepository
import com.vipin.fitness.core.network.auth.TokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {
    @Binds abstract fun tokenStore(impl: EncryptedTokenStore): TokenStore
    @Binds abstract fun profileFlags(impl: UserPreferencesRepository): ProfileFlagsCache
    @Binds abstract fun keyProvider(impl: KeystoreKeyProvider): SecretKeyProvider

    companion object {
        @Provides @Singleton
        fun tokenCipher(keys: SecretKeyProvider): TokenCipher = AesGcmTokenCipher(keys)

        @Provides @Singleton
        fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("user_prefs") }
    }
}

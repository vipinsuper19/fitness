package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.BuildConfig
import com.vipin.fitness.core.network.auth.AuthApi
import com.vipin.fitness.core.network.auth.AuthInterceptor
import com.vipin.fitness.core.network.auth.RefreshApi
import com.vipin.fitness.core.network.auth.RetrofitTokenRefresher
import com.vipin.fitness.core.network.auth.TokenAuthenticator
import com.vipin.fitness.core.network.auth.TokenRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    const val BASE_URL = "baseUrl"

    @Provides @Singleton
    fun json(): Json = fitnessJson()

    @Provides @Named(BASE_URL)
    fun baseUrl(): String = checkedBaseUrl(BuildConfig.API_BASE_URL, BuildConfig.DEBUG)

    /** Plain client: timeouts and logging only. Used for /auth/refresh so it can't recurse. */
    @Provides @Singleton @Named("base")
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // Render free tier cold starts take ~50 s
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
                redactHeader("Authorization")
            },
        )
        .build()

    @Provides @Singleton
    fun authedClient(
        @Named("base") base: OkHttpClient,
        authInterceptor: AuthInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = base.newBuilder()
        .addInterceptor(authInterceptor)
        .authenticator(authenticator)
        .build()

    @Provides @Singleton
    fun retrofit(@Named(BASE_URL) baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        retrofit(baseUrl, client, json)

    @Provides @Singleton
    fun refreshApi(@Named(BASE_URL) baseUrl: String, @Named("base") base: OkHttpClient, json: Json): RefreshApi =
        retrofit(baseUrl, base, json).create(RefreshApi::class.java)

    @Provides @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton
    fun tokenRefresher(api: RefreshApi, clock: Clock): TokenRefresher = RetrofitTokenRefresher(api, clock)

    @Provides @Singleton
    fun authApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
}

fun fitnessJson(): Json = Json {
    ignoreUnknownKeys = true // server may add fields before the client knows them
    // Defaults (e.g. optional fields left null) are omitted; an explicitly set null is still sent,
    // which the contract uses to clear a profile field.
    encodeDefaults = false
}

fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()

/** Placeholder baked into release builds until T17 sets `-Pfitness.releaseApiBaseUrl=https://...`. */
const val UNSET_RELEASE_BASE_URL = "https://unset.invalid/api/v1/"

/**
 * Release builds must talk HTTPS to a real host. Fails at startup (first network injection)
 * rather than silently sending traffic to a placeholder or over cleartext.
 */
fun checkedBaseUrl(url: String, debug: Boolean): String {
    if (debug) return url
    check(url.startsWith("https://")) { "Release API_BASE_URL must be https, was $url" }
    check(url != UNSET_RELEASE_BASE_URL) { "Release API_BASE_URL not configured; pass -Pfitness.releaseApiBaseUrl" }
    return url
}

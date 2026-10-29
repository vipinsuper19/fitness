package com.vipin.fitness

import com.vipin.fitness.core.network.ProfileApi
import com.vipin.fitness.core.network.WorkoutApi
import com.vipin.fitness.core.network.auth.AuthApi
import com.vipin.fitness.core.network.auth.RefreshApi
import com.vipin.fitness.core.network.di.NetworkModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import javax.inject.Inject

/**
 * The real Hilt graph for Retrofit and the APIs (NetworkModule is never replaced in app tests).
 * Injecting these overflowed the stack while `NetworkModule.retrofit` called itself.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class RealNetworkGraphTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var retrofit: Retrofit
    @Inject lateinit var refreshApi: RefreshApi
    @Inject lateinit var authApi: AuthApi
    @Inject lateinit var profileApi: ProfileApi
    @Inject lateinit var workoutApi: WorkoutApi
    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var json: Json

    @Before fun setUp() = hilt.inject()

    @Test fun graphBuildsRetrofitAndApis() {
        assertNotNull(retrofit.baseUrl())
        listOf(refreshApi, authApi, profileApi, workoutApi).forEach { assertNotNull(it) }
    }

    @Test fun graphClientMakesARealCallThroughTheRealProvider() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = """{"userId":"u1","onboardingCompleted":true,"unitSystem":"metric","updatedAt":"2026-10-29T10:00:00Z"}"""))
            val api = NetworkModule.profileApi(NetworkModule.retrofit(server.url("/api/v1/").toString(), client, json))
            assertEquals("u1", api.get().userId)
            assertEquals("/api/v1/me/profile", server.takeRequest().url.encodedPath)
        }
    }
}

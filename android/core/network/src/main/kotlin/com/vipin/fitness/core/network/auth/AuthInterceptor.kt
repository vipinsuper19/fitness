package com.vipin.fitness.core.network.auth

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.Invocation
import javax.inject.Inject

/** Adds `Authorization: Bearer <access>` unless the call is [Unauthenticated] or already has one. */
class AuthInterceptor @Inject constructor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null || request.isUnauthenticated()) {
            return chain.proceed(request)
        }
        val token = tokenStore.get()?.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(token))
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

internal fun Request.isUnauthenticated(): Boolean =
    tag(Invocation::class.java)?.method()?.isAnnotationPresent(Unauthenticated::class.java) == true

internal fun Request.withBearer(token: String): Request =
    newBuilder().header(AuthInterceptor.AUTHORIZATION, "Bearer $token").build()

internal fun Request.bearerToken(): String? =
    header(AuthInterceptor.AUTHORIZATION)?.removePrefix("Bearer ")

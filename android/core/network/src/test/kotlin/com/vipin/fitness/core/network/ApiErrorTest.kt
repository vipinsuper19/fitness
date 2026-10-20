package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.di.fitnessJson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ApiErrorTest {
    private val json = fitnessJson()

    @Test fun mapsContractErrorBody() = runTest {
        val body = """{"code":"EMAIL_TAKEN","message":"Email in use","requestId":"req-1","details":{"a":1}}"""
            .toResponseBody("application/json".toMediaType())
        val result = apiCall(json) { throw HttpException(Response.error<Unit>(409, body)) }
        assertEquals(ApiResult.Failure(ApiError.Http(409, "EMAIL_TAKEN", "Email in use", "req-1")), result)
    }

    @Test fun fallsBackWhenBodyIsNotJson() = runTest {
        val body = "<html>".toResponseBody("text/html".toMediaType())
        val result = apiCall(json) { throw HttpException(Response.error<Unit>(502, body)) } as ApiResult.Failure
        assertEquals("HTTP_502", (result.error as ApiError.Http).code)
    }

    @Test fun mapsIoToNetwork() = runTest {
        assertEquals(ApiResult.Failure(ApiError.Network), apiCall<Unit>(json) { throw IOException("offline") })
    }

    @Test fun success() = runTest {
        assertEquals(ApiResult.Success(5), apiCall(json) { 5 })
    }
}

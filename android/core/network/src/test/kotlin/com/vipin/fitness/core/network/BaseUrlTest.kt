package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.di.UNSET_RELEASE_BASE_URL
import com.vipin.fitness.core.network.di.checkedBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class BaseUrlTest {
    @Test fun debugAllowsEmulatorCleartext() {
        assertEquals(BuildConfig.API_BASE_URL, "http://10.0.2.2:3000/api/v1/") // unit tests run the debug variant
        assertEquals("http://10.0.2.2:3000/api/v1/", checkedBaseUrl("http://10.0.2.2:3000/api/v1/", debug = true))
    }

    @Test(expected = IllegalStateException::class) fun releaseRejectsCleartext() {
        checkedBaseUrl("http://api.example.com/api/v1/", debug = false)
    }

    @Test(expected = IllegalStateException::class) fun releaseRejectsUnsetPlaceholder() {
        checkedBaseUrl(UNSET_RELEASE_BASE_URL, debug = false)
    }

    @Test fun releaseAcceptsHttps() {
        assertEquals("https://api.example.com/api/v1/", checkedBaseUrl("https://api.example.com/api/v1/", debug = false))
    }
}

package com.vipin.fitness.feature.auth

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.designsystem.ExportTags
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeAccountRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class FakeExportFiles : ExportFiles {
    val written = mutableListOf<String>()
    val copies = mutableListOf<Pair<Uri, Uri>>()
    var failWrite = false
    override suspend fun write(json: String): Uri {
        check(!failWrite) { "disk full" }
        written += json
        return Uri.parse("content://test.exports/exports/e.json")
    }
    override suspend fun copy(from: Uri, to: Uri) { copies += from to to }
    override fun suggestedName() = "fitness-export-2026-10-21.json"
}

@RunWith(AndroidJUnit4::class)
class ExportViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeAccountRepository().apply { exportResult = ApiResult.Success("""{"user":{"id":"u1"}}""") }
    private val files = FakeExportFiles()
    private val vm = ExportViewModel(repo, files)

    @Test fun exportsOnceWritesTheFileAndAsksToShare() {
        repo.gate = CompletableDeferred()
        vm.export()
        vm.export()
        assertTrue(vm.state.value.loading)
        repo.gate!!.complete(Unit)
        assertEquals(1, repo.exports)
        assertEquals(listOf("""{"user":{"id":"u1"}}"""), files.written)
        assertNotNull(vm.state.value.file)
        assertTrue(vm.state.value.shareRequested)
        vm.shareShown()
        assertFalse(vm.state.value.shareRequested)
        vm.share()
        assertTrue(vm.state.value.shareRequested)
    }

    @Test fun errorsMapToMessagesAndRetryWorks() {
        repo.exportResult = ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "", null))
        vm.export()
        assertEquals(ExportError.RATE_LIMITED, vm.state.value.error)
        repo.exportResult = ApiResult.Failure(ApiError.Network)
        vm.export()
        assertEquals(ExportError.OFFLINE, vm.state.value.error)
        repo.exportResult = ApiResult.Failure(ApiError.Http(500, "INTERNAL", "", null))
        vm.export()
        assertEquals(ExportError.FAILED, vm.state.value.error)
        repo.exportResult = ApiResult.Success("{}")
        vm.export()
        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.file)
    }

    @Test fun saveCopiesToThePickedDocument() {
        vm.export()
        vm.saveTo(null) // picker cancelled
        assertTrue(files.copies.isEmpty())
        val target = Uri.parse("content://docs/picked.json")
        vm.saveTo(target)
        assertEquals(target, files.copies.single().second)
        assertTrue(vm.state.value.saved)
    }

    @Test fun writeFailureIsReported() {
        files.failWrite = true
        vm.export()
        assertEquals(ExportError.SAVE_FAILED, vm.state.value.error)
        assertNull(vm.state.value.file)
    }
}

@RunWith(AndroidJUnit4::class)
class CacheExportFilesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val files = CacheExportFiles(app, Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC))

    @Test fun writesOneShareableFileAndReplacesOlderExports() = runTest {
        File(app.cacheDir, "exports").mkdirs()
        File(app.cacheDir, "exports/old.json").writeText("{}")
        val uri = files.write("""{"a":1}""")
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.exports", uri.authority)
        assertEquals(listOf("fitness-export-2026-10-21.json"), File(app.cacheDir, "exports").list()!!.toList())
    }
}

@RunWith(AndroidJUnit4::class)
class ExportScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingShowsSpinnerAndDisablesStart() {
        compose.setContent { ExportContent(ExportState(loading = true), {}, {}, {}) }
        compose.onNodeWithTag(ExportTags.PROGRESS).assertExists()
        compose.onNodeWithTag(ExportTags.START).assertIsNotEnabled()
    }

    @Test fun rateLimitMentionsTheHourAndOffersRetry() {
        var retried = 0
        compose.setContent { ExportContent(ExportState(error = ExportError.RATE_LIMITED), { retried++ }, {}, {}) }
        compose.onNodeWithText("hour", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ExportTags.RETRY).performClick()
        assertEquals(1, retried)
    }

    @Test fun successOffersShareAndSave() {
        compose.setContent { ExportContent(ExportState(file = Uri.parse("content://x/e.json")), {}, {}, {}) }
        compose.onNodeWithTag(ExportTags.SUCCESS).assertIsDisplayed()
        compose.onNodeWithTag("export_share").assertIsEnabled()
        compose.onNodeWithTag("export_save").assertIsEnabled()
    }

    @Test fun successOpensAJsonShareSheetWithAReadGrant() {
        val repo = FakeAccountRepository().apply { exportResult = ApiResult.Success("{}") }
        val vm = ExportViewModel(repo, FakeExportFiles())
        compose.setContent { ExportRoute(vm) }
        compose.onNodeWithTag(ExportTags.START).performClick()
        compose.waitForIdle()
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val inner = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("application/json", inner.type)
        assertEquals("content", inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!.scheme)
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

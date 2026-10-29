package com.vipin.fitness.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.designsystem.AccountEntryTags
import com.vipin.fitness.core.designsystem.DeleteAccountTags
import com.vipin.fitness.core.designsystem.ExportTags
import com.vipin.fitness.core.testing.FakeAccountRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Test-first (Android P0): account deletion and data export, reached from the existing Profile
 * screen. Tags come from ProposedAccountContract.kt. Un-ignore each test as its screen lands.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DeleteExportUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var account: FakeAccountRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        resetFileProviderCache()
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
    }
    /**
     * Robolectric gives every test a fresh cacheDir, but androidx FileProvider caches its path roots
     * in a static map for the JVM's lifetime. Without this, the real CacheExportFiles can't build a
     * content:// URI after an earlier test in the same JVM (it fails as "Couldn't write the file").
     * On a device cacheDir never moves, so this only affects tests.
     */
    private fun resetFileProviderCache() {
        // FileProvider caches path strategies in a private static `sCache`; Robolectric reuses the
        // class across tests, so a stale entry from an earlier test breaks export. If an androidx
        // upgrade renames or retypes the field, fail loudly here instead of as a confusing export failure.
        val field = try {
            androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "FileProvider.sCache not found (androidx.core changed?). Update resetFileProviderCache() " +
                    "so export tests don't share a stale FileProvider path cache.", e,
            )
        }
        val cache = field.get(null)
        check(cache is MutableMap<*, *>) {
            "FileProvider.sCache is ${cache?.javaClass?.name ?: "null"}, expected a MutableMap; update resetFileProviderCache()."
        }
        cache.clear()
    }

    @After fun tearDown() { scenario?.close() }

    /**
     * Also drains the Robolectric main looper: the export file is written on Dispatchers.IO, and its
     * resume back onto Main is posted from another thread, which waitUntil alone doesn't run.
     */
    private fun waitTag(t: String) = compose.waitUntil(5_000) {
        Thread.sleep(10) // real time for the IO thread; waitUntil runs on a virtual clock
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitText(t: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun absent(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isEmpty()
    private fun tag(t: String) = compose.onNodeWithTag(t)

    private fun openProfile() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("open_profile")
        tag("open_profile").performClick()
        waitText("Profile")
    }
    private fun openDelete() {
        openProfile()
        tag(AccountEntryTags.OPEN_DELETE_ACCOUNT).performScrollTo().performClick()
        waitTag(DeleteAccountTags.SCREEN)
    }
    private fun openExport() {
        openProfile()
        tag(AccountEntryTags.OPEN_EXPORT).performScrollTo().performClick()
        waitTag(ExportTags.SCREEN)
    }

    /** Runs now: guards the proposed tag list against typos/duplicates. */
    @Test fun proposedTagsAreUnique() {
        val all = listOf(
            AccountEntryTags.OPEN_DELETE_ACCOUNT, AccountEntryTags.OPEN_EXPORT,
            DeleteAccountTags.SCREEN, DeleteAccountTags.PASSWORD, DeleteAccountTags.CONFIRM, DeleteAccountTags.CANCEL,
            DeleteAccountTags.PROGRESS, DeleteAccountTags.ERROR,
            ExportTags.SCREEN, ExportTags.START, ExportTags.PROGRESS, ExportTags.SUCCESS, ExportTags.ERROR, ExportTags.RETRY,
        )
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.none { it == "open_profile" })
    }

    // ---- account deletion ----

    @Test fun delete_requiresPassword_beforeConfirmIsEnabled() {
        openDelete()
        tag(DeleteAccountTags.CONFIRM).assertIsNotEnabled()
        tag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        tag(DeleteAccountTags.CONFIRM).assertIsEnabled()
        assertTrue(account.deleteCalls.isEmpty())
    }

    @Test fun delete_cancel_sendsNothing_andStaysSignedIn() {
        openDelete()
        tag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        tag(DeleteAccountTags.CANCEL).performClick()
        compose.waitForIdle()
        assertTrue(absent(DeleteAccountTags.SCREEN))
        waitText("Profile")
        assertTrue(account.deleteCalls.isEmpty())
        assertEquals(0, session.signOuts)
    }

    @Test fun delete_wrongPassword_showsError_staysSignedIn_andCanRetry() {
        account.deleteResult = AccountResults.WRONG_PASSWORD
        openDelete()
        tag(DeleteAccountTags.PASSWORD).performTextInput("wrong-password")
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitTag(DeleteAccountTags.ERROR)
        waitText("password")
        assertEquals(0, session.signOuts)
        tag(DeleteAccountTags.SCREEN).assertExists()
        tag(DeleteAccountTags.CONFIRM).assertIsEnabled()
    }

    @Test fun delete_serverError_showsMessage_retryReusesIdempotencyKey() {
        account.deleteResult = AccountResults.SERVER_ERROR
        openDelete()
        tag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitTag(DeleteAccountTags.ERROR)
        assertEquals(0, session.signOuts)
        account.deleteResult = com.vipin.fitness.core.network.ApiResult.Success(Unit)
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitText("Sign in")
        assertEquals(2, account.deleteCalls.size)
        assertEquals("same action, outcome unknown → same key", account.deleteCalls[0].second, account.deleteCalls[1].second)
    }

    @Test fun delete_inFlight_showsProgress_andBlocksDoubleSubmit() {
        account.gate = CompletableDeferred()
        openDelete()
        tag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitTag(DeleteAccountTags.PROGRESS)
        tag(DeleteAccountTags.CONFIRM).assertIsNotEnabled()
        account.gate!!.complete(Unit)
        waitText("New here? Create an account")
        assertEquals(1, account.deleteCalls.size)
    }

    @Test fun delete_success_signsOut_toSignIn_andBackCannotReturn() {
        openDelete()
        tag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitText("New here? Create an account")
        assertEquals("correct horse", account.deleteCalls.single().first)
        assertEquals(1, session.signOuts)
        assertEquals(false, session.isSignedIn())
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(absent(DeleteAccountTags.SCREEN))
        assertTrue(absent("open_profile"))
        assertTrue(compose.onAllNodes(hasText("Sign out")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun delete_lostSuccess_retryGets401_treatedAsDeleted_signsOutToSignIn() {
        account.deleteResult = AccountResults.OFFLINE // 204 lost in transit: outcome unknown
        openDelete()
        tag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitTag(DeleteAccountTags.ERROR)
        assertEquals(0, session.signOuts)
        account.deleteResult = AccountResults.UNAUTHENTICATED // server already deleted; tokens revoked
        tag(DeleteAccountTags.CONFIRM).performClick()
        waitText("New here? Create an account")
        assertEquals(2, account.deleteCalls.size)
        assertEquals(account.deleteCalls[0].second, account.deleteCalls[1].second)
        assertEquals(1, session.signOuts)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(absent(DeleteAccountTags.SCREEN))
    }

    // ---- data export ----

    @Test fun export_showsProgress_thenSuccess_andSharesJsonFile() {
        account.gate = CompletableDeferred()
        openExport()
        tag(ExportTags.START).performClick()
        waitTag(ExportTags.PROGRESS)
        tag(ExportTags.START).assertIsNotEnabled()
        account.gate!!.complete(Unit)
        waitTag(ExportTags.SUCCESS)
        assertEquals(1, account.exports)
        val started = shadowOf(ApplicationProvider.getApplicationContext<HiltTestApplication>()).nextStartedActivity
        assertNotNull("expected a share chooser or save-file intent", started)
        val inner = if (started.action == Intent.ACTION_CHOOSER) started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!! else started
        assertTrue(inner.action in setOf(Intent.ACTION_SEND, Intent.ACTION_CREATE_DOCUMENT))
        assertEquals("application/json", inner.type)
        if (inner.action == Intent.ACTION_SEND) {
            val uri = inner.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
            assertEquals("content", uri.scheme) // FileProvider, never file://
            assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun export_error_showsRetry_andRetrySucceeds() {
        account.exportResult = AccountResults.SERVER_ERROR
        openExport()
        tag(ExportTags.START).performClick()
        waitTag(ExportTags.ERROR)
        waitTag(ExportTags.RETRY)
        account.exportResult = com.vipin.fitness.core.network.ApiResult.Success("""{"exportedAt":"2026-10-21T11:30:00Z","user":{"id":"u1"}}""")
        tag(ExportTags.RETRY).performClick()
        waitTag(ExportTags.SUCCESS)
        assertTrue(absent(ExportTags.ERROR))
        assertEquals(2, account.exports)
    }

    @Test fun export_rateLimited_showsWaitMessage() {
        account.exportResult = AccountResults.RATE_LIMITED
        openExport()
        tag(ExportTags.START).performClick()
        waitTag(ExportTags.ERROR)
        waitText("hour") // e.g. "You can export 5 times an hour. Try again later."
    }

    @Test fun export_offline_showsError_withRetry() {
        account.exportResult = com.vipin.fitness.core.network.ApiResult.Failure(com.vipin.fitness.core.network.ApiError.Network)
        openExport()
        tag(ExportTags.START).performClick()
        waitTag(ExportTags.ERROR)
        waitTag(ExportTags.RETRY)
        assertTrue(absent(ExportTags.SUCCESS))
    }
}

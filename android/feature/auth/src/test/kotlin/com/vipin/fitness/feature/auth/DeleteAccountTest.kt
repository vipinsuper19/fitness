package com.vipin.fitness.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.designsystem.DeleteAccountTags
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeAccountRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val WRONG_PASSWORD = ApiResult.Failure(ApiError.Http(403, "INVALID_PASSWORD", "Wrong password", null))
private val SERVER_ERROR = ApiResult.Failure(ApiError.Http(500, "INTERNAL", "Internal error", null))

class DeleteAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeAccountRepository()
    private val session = FakeSessionRepository(signedIn = true)
    private val vm = DeleteAccountViewModel(repo, session)

    @Test fun deletionForgetsThatUsersConsentBeforeSigningOut() {
        val consent = com.vipin.fitness.core.testing.FakeConsentRepository(consented = true)
        val vm = DeleteAccountViewModel(repo, session, consent)
        vm.onPassword("hunter22")
        vm.delete()
        assertEquals(1, consent.forgets)
        assertEquals(1, session.signOuts)
    }

    @Test fun wrongPasswordKeepsConsent() {
        val consent = com.vipin.fitness.core.testing.FakeConsentRepository(consented = true)
        val vm = DeleteAccountViewModel(repo, session, consent)
        repo.deleteResult = WRONG_PASSWORD
        vm.onPassword("nope")
        vm.delete()
        assertEquals(0, consent.forgets)
    }

    @Test fun nothingIsSentWithoutAPassword() {
        assertFalse(vm.state.value.canConfirm)
        vm.delete()
        assertTrue(repo.deleteCalls.isEmpty())
    }

    @Test fun deletesOnceThenSignsOut() {
        repo.gate = CompletableDeferred()
        vm.onPassword("hunter22")
        vm.delete()
        vm.delete()
        assertTrue(vm.state.value.submitting)
        assertFalse(vm.state.value.canConfirm)
        repo.gate!!.complete(Unit)
        assertEquals(listOf("hunter22"), repo.deleteCalls.map { it.first })
        assertEquals(1, session.signOuts)
        assertTrue(vm.state.value.deleted)
    }

    @Test fun wrongPasswordStaysSignedInAndANewAttemptGetsANewKey() {
        repo.deleteResult = WRONG_PASSWORD
        vm.onPassword("nope")
        vm.delete()
        assertEquals(DeleteAccountError.WRONG_PASSWORD, vm.state.value.error)
        assertEquals(0, session.signOuts)
        assertTrue(vm.state.value.canConfirm)
        vm.delete()
        assertNotEquals(repo.deleteCalls[0].second, repo.deleteCalls[1].second)
    }

    @Test fun serverErrorAndOfflineRetriesReuseTheKey() {
        repo.deleteResult = SERVER_ERROR
        vm.onPassword("p")
        vm.delete()
        assertEquals(DeleteAccountError.SERVER, vm.state.value.error)
        repo.deleteResult = ApiResult.Failure(ApiError.Network)
        vm.delete()
        assertEquals(DeleteAccountError.OFFLINE, vm.state.value.error)
        repo.deleteResult = ApiResult.Success(Unit)
        vm.delete()
        assertEquals(1, repo.deleteCalls.map { it.second }.distinct().size)
        assertTrue(vm.state.value.deleted)
    }

    @Test fun unauthorizedOnARetryMayMeanDeletedAndOffersSignIn() {
        val consent = com.vipin.fitness.core.testing.FakeConsentRepository(consented = true)
        val vm = DeleteAccountViewModel(repo, session, consent)
        repo.deleteResult = ApiResult.Failure(ApiError.Network) // 204 may have been lost on the way back
        vm.onPassword("p")
        vm.delete()
        repo.deleteResult = ApiResult.Failure(ApiError.Http(401, "UNAUTHENTICATED", "", null))
        vm.delete()
        assertEquals(DeleteAccountError.MAYBE_DELETED, vm.state.value.error)
        assertFalse(vm.state.value.deleted)
        assertFalse(vm.state.value.canConfirm) // another try would just 401 again
        assertEquals(0, session.signOuts)
        assertEquals(0, consent.forgets) // not known to be deleted: keep consent
        vm.signInToCheck()
        vm.signInToCheck()
        assertEquals(1, session.signOuts)
        assertTrue(vm.state.value.signedOutToCheck)
    }

    @Test fun signInToCheckDoesNothingWithoutTheAmbiguousError() {
        vm.signInToCheck()
        assertEquals(0, session.signOuts)
    }

    @Test fun unauthorizedOnAFirstAttemptIsNotTreatedAsDeleted() {
        repo.deleteResult = ApiResult.Failure(ApiError.Http(401, "UNAUTHENTICATED", "", null))
        vm.onPassword("p")
        vm.delete()
        assertFalse(vm.state.value.deleted)
        assertEquals(0, session.signOuts)
    }

    @Test fun rateLimitedIsItsOwnError() {
        repo.deleteResult = ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "", null))
        vm.onPassword("p")
        vm.delete()
        assertEquals(DeleteAccountError.RATE_LIMITED, vm.state.value.error)
    }

    @Test fun rateLimitedRetryReusesTheKey() {
        repo.deleteResult = ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "", null, retryAfterMs = 30_000))
        vm.onPassword("p")
        vm.delete()
        repo.deleteResult = ApiResult.Success(Unit)
        vm.delete()
        assertEquals(2, repo.deleteCalls.size)
        assertEquals(repo.deleteCalls[0].second, repo.deleteCalls[1].second)
        assertTrue(vm.state.value.deleted)
    }
}

@RunWith(AndroidJUnit4::class)
class DeleteAccountScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repo = FakeAccountRepository()
    private val session = FakeSessionRepository(signedIn = true)

    private fun show(onDeleted: () -> Unit = {}, onCancel: () -> Unit = {}) {
        val vm = DeleteAccountViewModel(repo, session)
        compose.setContent { DeleteAccountRoute(onDeleted = onDeleted, onCancel = onCancel, viewModel = vm) }
    }

    @Test fun confirmIsDisabledUntilAPasswordIsTyped() {
        show()
        compose.onNodeWithTag(DeleteAccountTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).assertIsNotEnabled()
        compose.onNodeWithTag(DeleteAccountTags.PASSWORD).performTextInput("x")
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).assertIsEnabled()
    }

    @Test fun cancelSendsNothing() {
        var cancelled = 0
        show(onCancel = { cancelled++ })
        compose.onNodeWithTag(DeleteAccountTags.PASSWORD).performTextInput("x")
        compose.onNodeWithTag(DeleteAccountTags.CANCEL).performClick()
        assertEquals(1, cancelled)
        assertTrue(repo.deleteCalls.isEmpty())
        assertEquals(0, session.signOuts)
    }

    @Test fun inFlightShowsProgressAndBlocksConfirm() {
        repo.gate = CompletableDeferred()
        show()
        compose.onNodeWithTag(DeleteAccountTags.PASSWORD).performTextInput("x")
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).performClick()
        compose.onNodeWithTag(DeleteAccountTags.PROGRESS, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).assertIsNotEnabled()
        repo.gate!!.complete(Unit)
    }

    @Test fun wrongPasswordShowsPasswordErrorAndConfirmStaysUsable() {
        repo.deleteResult = WRONG_PASSWORD
        show()
        compose.onNodeWithTag(DeleteAccountTags.PASSWORD).performTextInput("nope")
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).performClick()
        compose.onNodeWithTag(DeleteAccountTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).assertIsEnabled()
        assertEquals(0, session.signOuts)
    }

    @Test fun successSignsOutAndLeaves() {
        var deleted = 0
        show(onDeleted = { deleted++ })
        compose.onNodeWithTag(DeleteAccountTags.PASSWORD).performTextInput("correct horse")
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals(1, session.signOuts)
        assertEquals(1, deleted)
    }

    @Test fun maybeDeletedShowsMessageAndSignInLeavesSignedOut() {
        var left = 0
        show(onDeleted = { left++ })
        repo.deleteResult = ApiResult.Failure(ApiError.Network)
        compose.onNodeWithTag(DeleteAccountTags.PASSWORD).performTextInput("x")
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).performClick()
        compose.onNodeWithTag(DeleteAccountTags.SIGN_IN_TO_CHECK).assertDoesNotExist()
        repo.deleteResult = ApiResult.Failure(ApiError.Http(401, "UNAUTHENTICATED", "", null))
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).performClick()
        compose.onNodeWithTag(DeleteAccountTags.ERROR).assertTextEquals("Your account may already be deleted. Sign in to check.")
        compose.onNodeWithTag(DeleteAccountTags.CONFIRM).assertIsNotEnabled()
        assertEquals(0, session.signOuts)
        compose.onNodeWithTag(DeleteAccountTags.SIGN_IN_TO_CHECK).performClick()
        compose.waitForIdle()
        assertEquals(1, session.signOuts)
        assertEquals(1, left)
    }
}

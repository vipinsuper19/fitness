package com.vipin.fitness.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.DeleteAccountOutcome
import com.vipin.fitness.core.testing.FakeAccountRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class DeleteAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeAccountRepository()
    private val vm = DeleteAccountViewModel(repo)

    @Test fun emptyPasswordIsNotSent() {
        vm.delete()
        assertEquals(DeleteAccountError.PASSWORD_REQUIRED, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun deletesOnceEvenWhenTappedTwice() {
        repo.gate = CompletableDeferred()
        vm.onPassword("hunter22")
        vm.delete()
        vm.delete()
        assertTrue(vm.state.value.submitting)
        repo.gate!!.complete(Unit)
        assertEquals(1, repo.calls.size)
        assertEquals("hunter22", repo.calls.single().first)
        assertTrue(vm.state.value.deleted)
        assertEquals("", vm.state.value.password)
        vm.delete()
        assertEquals(1, repo.calls.size) // done: nothing more to send
    }

    @Test fun wrongPasswordHasItsOwnMessageAndAFreshKeyNextTime() {
        repo.result = DeleteAccountOutcome.WrongPassword
        vm.onPassword("nope")
        vm.delete()
        assertEquals(DeleteAccountError.WRONG_PASSWORD, vm.state.value.error)
        assertFalse(vm.state.value.deleted)
        repo.result = DeleteAccountOutcome.Deleted
        vm.onPassword("right")
        vm.delete()
        assertNotEquals(repo.calls[0].second, repo.calls[1].second)
    }

    @Test fun offlineRetryReusesTheKey() {
        repo.result = DeleteAccountOutcome.Offline
        vm.onPassword("p")
        vm.delete()
        assertEquals(DeleteAccountError.OFFLINE, vm.state.value.error)
        repo.result = DeleteAccountOutcome.Deleted
        vm.delete()
        assertEquals(repo.calls[0].second, repo.calls[1].second)
        assertTrue(vm.state.value.deleted)
    }
}

@RunWith(AndroidJUnit4::class)
class DeleteAccountScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun warnsAndConfirmsWithPasswordThenLeaves() {
        val repo = FakeAccountRepository()
        val vm = DeleteAccountViewModel(repo)
        var deleted = 0
        compose.setContent { DeleteAccountRoute(onDeleted = { deleted++ }, onCancel = {}, viewModel = vm) }
        compose.onNodeWithTag("delete_account_warning").assertIsDisplayed()
        compose.onNodeWithTag("delete_account_password").performTextInput("hunter22")
        compose.onNodeWithTag("delete_account_confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf("hunter22"), repo.calls.map { it.first })
        assertEquals(1, deleted)
    }

    @Test fun wrongPasswordMessage() {
        compose.setContent { DeleteAccountContent(DeleteAccountState(error = DeleteAccountError.WRONG_PASSWORD), {}, {}, {}) }
        compose.onNodeWithText("That password isn't right. Your account was not deleted.").assertIsDisplayed()
    }

    @Test fun offlineMessage() {
        compose.setContent { DeleteAccountContent(DeleteAccountState(error = DeleteAccountError.OFFLINE), {}, {}, {}) }
        compose.onNodeWithTag("delete_account_error").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach the server, so your account was not deleted yet. Check your connection and try again.").assertIsDisplayed()
    }

    @Test fun buttonsAreDisabledWhileDeleting() {
        compose.setContent { DeleteAccountContent(DeleteAccountState(password = "p", submitting = true), {}, {}, {}) }
        compose.onNodeWithTag("delete_account_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("delete_account_cancel").assertIsNotEnabled()
    }
}

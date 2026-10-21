package com.vipin.fitness.feature.auth

import com.vipin.fitness.core.data.AuthFailure
import com.vipin.fitness.core.data.AuthOutcome
import com.vipin.fitness.core.designsystem.UiText
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthViewModelsTest {
    @get:Rule val main = MainDispatcherRule()
    private val session = FakeSessionRepository()

    @Test fun signInValidatesBeforeCallingServer() {
        val vm = SignInViewModel(session)
        vm.onEmail("not-an-email")
        vm.submit()
        assertEquals(UiText.Res(R.string.auth_error_email), vm.state.value.emailError)
        assertEquals(UiText.Res(R.string.auth_error_password_required), vm.state.value.passwordError)
        assertTrue(session.signInCalls.isEmpty())
    }

    @Test fun signInSuccess() {
        val vm = SignInViewModel(session)
        vm.onEmail("v@x.in"); vm.onPassword("pw")
        vm.submit()
        assertTrue(vm.state.value.signedIn)
        assertEquals("", vm.state.value.password)
        assertEquals(listOf("v@x.in" to "pw"), session.signInCalls)
    }

    @Test fun signInDoubleTapSendsOnce() {
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val vm = SignInViewModel(session)
        vm.onEmail("v@x.in"); vm.onPassword("pw")
        vm.submit(); vm.submit()
        assertTrue(vm.state.value.submitting)
        gate.complete(Unit)
        assertEquals(1, session.signInCalls.size)
        assertFalse(vm.state.value.submitting)
    }

    @Test fun signInWrongPasswordShowsError() {
        session.signInResult = AuthOutcome.Failure(AuthFailure.InvalidCredentials)
        val vm = SignInViewModel(session)
        vm.onEmail("v@x.in"); vm.onPassword("pw")
        vm.submit()
        assertEquals(UiText.Res(R.string.auth_error_invalid_credentials), vm.state.value.error)
        assertFalse(vm.state.value.signedIn)
        vm.onPassword("pw2")
        assertNull(vm.state.value.error)
    }

    @Test fun prefillDoesNotOverwriteTypedEmail() {
        val vm = SignInViewModel(session)
        vm.prefill("a@b.in")
        assertEquals("a@b.in", vm.state.value.email)
        vm.onEmail("typed@b.in")
        vm.prefill("other@b.in")
        assertEquals("typed@b.in", vm.state.value.email)
    }

    @Test fun registerRequiresEightCharPassword() {
        val vm = RegisterViewModel(session)
        vm.onEmail("v@x.in"); vm.onPassword("short")
        vm.submit()
        assertEquals(UiText.Res(R.string.auth_error_password_short), vm.state.value.passwordError)
        assertTrue(session.registerKeys.isEmpty())
    }

    @Test fun registerEmailTakenOffersSignIn() {
        session.registerResult = AuthOutcome.Failure(AuthFailure.EmailTaken)
        val vm = RegisterViewModel(session)
        vm.onEmail("v@x.in"); vm.onPassword("password1")
        vm.submit()
        assertTrue(vm.state.value.emailTaken)
        assertFalse(vm.state.value.registered)
        vm.onEmail("w@x.in")
        assertFalse(vm.state.value.emailTaken)
    }

    @Test fun registerRetryReusesKeyUntilDetailsChange() {
        var n = 0
        session.registerResult = AuthOutcome.Failure(AuthFailure.Network)
        val vm = RegisterViewModel(session).apply { newKey = { "k${++n}" } }
        vm.onEmail("v@x.in"); vm.onPassword("password1")
        vm.submit(); vm.submit() // retry after a network failure
        vm.onPassword("password2")
        vm.submit()
        assertEquals(listOf("k1", "k1", "k2"), session.registerKeys)
        assertNotEquals(session.registerKeys[0], session.registerKeys[2])
    }

    @Test fun registerSuccess() {
        val vm = RegisterViewModel(session)
        vm.onEmail("v@x.in"); vm.onPassword("password1")
        vm.submit()
        assertTrue(vm.state.value.registered)
    }
}

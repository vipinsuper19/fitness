package com.vipin.fitness.e2e

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vipin.fitness.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real debug app (real Hilt modules, no fakes) against the debug API base URL (Render). Credentials for a
 * throwaway @example.invalid account come from instrumentation args `e2eEmail` / `e2ePassword`, generated
 * by the workflow, which also deletes the account afterwards. Nothing secret is logged or written here.
 * Screenshots and a step report go to the app's external files dir (e2e/), pulled by the workflow.
 */
@RunWith(AndroidJUnit4::class)
class RealAppE2eTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val email = requireNotNull(args.getString("e2eEmail")) { "missing e2eEmail" }
    private val password = requireNotNull(args.getString("e2ePassword")) { "missing e2ePassword" }
    private val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "e2e").apply { deleteRecursively(); mkdirs() }
    private val report = File(out, "steps.txt")
    private val net = 120_000L // Render cold start can take 60-90 s

    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bmp ->
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun step(name: String, required: Boolean = true, body: () -> Unit) {
        try {
            body(); shot(name); report.appendText("PASS $name\n")
        } catch (t: Throwable) {
            runCatching { shot("$name-FAIL") }
            // Diagnostics only: thread dump to /data/anr, then a later screenshot to see if the UI settled.
            runCatching { android.os.Process.sendSignal(android.os.Process.myPid(), 3) }
            runCatching { Thread.sleep(30_000); shot("$name-FAIL-late") }
            report.appendText("FAIL $name ${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()?.take(200)}\n")
            if (required) throw t
        }
    }

    private fun present(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(m: SemanticsMatcher, ms: Long = 20_000) = compose.waitUntil(ms) { present(m) }
    private fun waitText(t: String, ms: Long = 20_000) = waitFor(hasText(t), ms)
    private fun waitTag(t: String, ms: Long = 20_000) = waitFor(hasTestTag(t), ms)
    private fun SemanticsNodeInteraction.scrolled() = apply { runCatching { performScrollTo() } }
    private fun click(t: String) { compose.onNode(hasText(t) and hasClickAction()).scrolled().performClick(); compose.waitForIdle() }
    private fun clickTag(t: String) { waitTag(t); compose.onNode(hasTestTag(t)).scrolled().performClick(); compose.waitForIdle() }
    private fun type(label: String, v: String) = compose.onNode(hasSetTextAction() and hasText(label)).scrolled().performTextInput(v)
    private fun setTag(tag: String, v: String) { waitTag(tag); compose.onNode(hasTestTag(tag)).scrolled().apply { performTextClearance(); performTextInput(v) } }
    private fun textStarts(prefix: String) = SemanticsMatcher("text starts with $prefix") { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
    }
    private fun back() { compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun signOut() {
        clickTag("open_profile"); waitText("Sign out"); click("Sign out")
        waitText("New here? Create an account", net)
    }

    /** Consent gate shows after auth for users without the current consent version. */
    private fun passConsentThen(next: SemanticsMatcher) {
        compose.waitUntil(net) { present(hasTestTag("consent_screen")) || present(next) }
        if (present(hasTestTag("consent_screen"))) {
            shot("01a-consent")
            clickTag("consent_agree"); clickTag("consent_continue")
        }
        waitFor(next, net)
    }

    @Test fun signUpSignInTodayWaterSignOut() {
        step("01-signup") {
            waitText("New here? Create an account", net)
            click("New here? Create an account")
            type("Email", email); type("Password", password)
            click("Create account")
            passConsentThen(hasText("Step 1 of 4"))
            for (n in 1..4) { waitText("Step $n of 4", net); click("Skip") }
            waitText("Today's plan", net)
        }
        step("02-signin") {
            signOut()
            type("Email", email); type("Password", password)
            click("Sign in")
            passConsentThen(hasText("Today's plan"))
        }
        step("03-today") {
            waitTag("home_list", net)
        }
        step("04-water") {
            clickTag("tab_NUTRITION")
            waitTag("water_add_250", net)
            clickTag("water_add_250")
            waitFor(textStarts("250 ml"), net)
        }
        step("05-macros-pcf", required = false) {
            waitTag("open_macro_targets", net); clickTag("open_macro_targets")
            waitTag("macro_targets", net); clickTag("macro_edit")
            setTag("macro_edit_protein", "120"); setTag("macro_edit_carbs", "210"); setTag("macro_edit_fat", "60")
            clickTag("macro_save")
            waitTag("macro_saved", net)
            waitFor(hasText("120 g", substring = true), net)
        }
        runCatching { if (!present(hasTestTag("open_profile"))) back() }
        step("06-signout") {
            signOut()
        }
    }
}

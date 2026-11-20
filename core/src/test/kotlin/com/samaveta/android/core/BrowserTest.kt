package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.browser.BrowserPolicy
import com.samaveta.android.core.browser.BrowserSession
import com.samaveta.android.core.browser.ChallengeKind
import com.samaveta.android.core.browser.ElementRole
import com.samaveta.android.core.browser.ExtractedPage
import com.samaveta.android.core.browser.InteractionBlockReason
import com.samaveta.android.core.browser.InteractionDecision
import com.samaveta.android.core.browser.NavigationBlockReason
import com.samaveta.android.core.browser.NavigationDecision
import com.samaveta.android.core.browser.PageElement
import com.samaveta.android.core.browser.ReaderMode
import com.samaveta.android.core.browser.VerificationChallenge

/**
 * The browser is the highest-risk surface: an agent that can navigate and click on the open web can
 * be steered by hostile content. These tests pin the safety rules.
 */
class BrowserTest {

    private fun element(
        ref: String = "el-1",
        role: ElementRole = ElementRole.BUTTON,
        label: String = "Submit",
        enabled: Boolean = true,
        visible: Boolean = true,
        password: Boolean = false,
        navigates: Boolean = false,
    ) = PageElement(
        ref = ref,
        role = role,
        accessibleLabel = label,
        isEnabled = enabled,
        isVisible = visible,
        isPasswordField = password,
        causesNavigation = navigates,
    )

    private fun page(
        url: String = "https://example.com/article",
        elements: List<PageElement> = listOf(element()),
        text: String = "Body text.",
        challenge: VerificationChallenge? = null,
        paywall: Boolean = false,
        loginWall: Boolean = false,
    ) = ExtractedPage(
        url = url,
        title = "Example",
        elements = elements,
        textContent = text,
        mainTextChars = text.length,
        verificationChallenge = challenge,
        hasPaywall = paywall,
        hasLoginWall = loginWall,
    )

    private val policy = BrowserPolicy()

    // ---- navigation policy --------------------------------------------------------------------

    @Test
    fun httpsIsAllowed() {
        assertEquals(NavigationDecision.Allowed, policy.evaluateNavigation("https://example.com/a", true))
    }

    @Test
    fun executableSchemesAreNeverFollowed() {
        listOf("javascript:alert(1)", "data:text/html,<script>x()</script>", "blob:https://x/y").forEach { url ->
            val decision = policy.evaluateNavigation(url, true)
            assertTrue("$url must be blocked", decision is NavigationDecision.Blocked)
            assertEquals(
                NavigationBlockReason.BLOCKED_BY_POLICY,
                (decision as NavigationDecision.Blocked).reason,
            )
        }
    }

    @Test
    fun fileUrlsAreBlocked() {
        val decision = policy.evaluateNavigation("file:///sdcard/secret.txt", true)
        assertEquals(
            NavigationBlockReason.BLOCKED_BY_POLICY,
            (decision as NavigationDecision.Blocked).reason,
        )
    }

    @Test
    fun deepLinksAreHandedToTheUser() {
        listOf("intent://x#Intent;scheme=http;end", "market://details?id=com.x", "tel:+919000000000").forEach { url ->
            val decision = policy.evaluateNavigation(url, true)
            assertEquals(
                NavigationBlockReason.DEEP_LINK_TO_APP,
                (decision as NavigationDecision.Blocked).reason,
            )
        }
    }

    @Test
    fun localAndPrivateAddressesAreUnreachable() {
        listOf(
            "https://localhost:8080/admin",
            "http://127.0.0.1/",
            "http://10.0.0.5/router",
            "http://192.168.1.1/",
            "http://172.16.4.2/",
            "http://169.254.169.254/latest/meta-data",
            "https://router.local/",
        ).forEach { url ->
            val decision = policy.evaluateNavigation(url, true)
            assertTrue("$url must be blocked as local", decision is NavigationDecision.Blocked)
            assertEquals(
                "169.254.169.254 is the cloud metadata endpoint and must never be reachable",
                NavigationBlockReason.LOCAL_NETWORK,
                (decision as NavigationDecision.Blocked).reason,
            )
        }
    }

    @Test
    fun agentNavigationRequiresHttps() {
        val decision = policy.evaluateNavigation("http://example.com/a", initiatedByAgent = true)
        assertEquals(
            NavigationBlockReason.NON_HTTPS,
            (decision as NavigationDecision.Blocked).reason,
        )
        // The user typing an http URL is their decision, not the agent's.
        assertEquals(NavigationDecision.Allowed, policy.evaluateNavigation("http://example.com/a", false))
    }

    @Test
    fun allowlistedDomainsRestrictAgentNavigation() {
        val strict = BrowserPolicy(allowlistedDomains = setOf("wikipedia.org"))
        assertEquals(NavigationDecision.Allowed, strict.evaluateNavigation("https://en.wikipedia.org/wiki/AI", true))
        val blocked = strict.evaluateNavigation("https://example.com/", true)
        assertEquals(
            NavigationBlockReason.DOMAIN_NOT_ALLOWED,
            (blocked as NavigationDecision.Blocked).reason,
        )
    }

    @Test
    fun subdomainMatchingDoesNotAllowLookalikes() {
        val strict = BrowserPolicy(allowlistedDomains = setOf("wikipedia.org"))
        // "notwikipedia.org" must not match "wikipedia.org".
        val blocked = strict.evaluateNavigation("https://notwikipedia.org/", true)
        assertTrue(blocked is NavigationDecision.Blocked)
        assertEquals(NavigationDecision.Allowed, strict.evaluateNavigation("https://wikipedia.org/", true))
    }

    @Test
    fun blockedDomainsAreRefused() {
        assertEquals(
            NavigationBlockReason.BLOCKED_BY_POLICY,
            (policy.evaluateNavigation("https://ads.doubleclick.net/x", true) as NavigationDecision.Blocked).reason,
        )
    }

    @Test
    fun malformedUrlsAreRejected() {
        assertEquals(
            NavigationBlockReason.MALFORMED_URL,
            (policy.evaluateNavigation("not a url", true) as NavigationDecision.Blocked).reason,
        )
        assertEquals(
            NavigationBlockReason.MALFORMED_URL,
            (policy.evaluateNavigation("   ", true) as NavigationDecision.Blocked).reason,
        )
    }

    @Test
    fun userinfoCannotBeUsedToSpoofTheHost() {
        // https://trusted.com@evil.com/ has host evil.com, not trusted.com.
        val strict = BrowserPolicy(allowlistedDomains = setOf("trusted.com"))
        val decision = strict.evaluateNavigation("https://trusted.com@evil.com/", true)
        assertTrue("userinfo must not spoof the host", decision is NavigationDecision.Blocked)
    }

    // ---- interaction policy -------------------------------------------------------------------

    @Test
    fun plainButtonInteractionIsAllowed() {
        val p = page(elements = listOf(element(role = ElementRole.BUTTON, label = "Load more")))
        assertEquals(InteractionDecision.Allowed, policy.evaluateInteraction(p, p.elements.first()))
    }

    @Test
    fun submitAndNavigationRequireConfirmation() {
        val submit = page(elements = listOf(element(role = ElementRole.SUBMIT, label = "Buy now")))
        assertTrue(policy.evaluateInteraction(submit, submit.elements.first()) is InteractionDecision.RequiresConfirmation)

        val link = page(elements = listOf(element(role = ElementRole.LINK, label = "Next", navigates = true)))
        assertTrue(policy.evaluateInteraction(link, link.elements.first()) is InteractionDecision.RequiresConfirmation)
    }

    @Test
    fun passwordFieldsAreNeverTypedInto() {
        val p = page(elements = listOf(element(role = ElementRole.TEXT_FIELD, label = "Password", password = true)))
        val decision = policy.evaluateInteraction(p, p.elements.first()) as InteractionDecision.Blocked
        assertEquals(InteractionBlockReason.CREDENTIAL_FIELD, decision.reason)
    }

    @Test
    fun invisibleOrDisabledElementsAreNotClicked() {
        val hidden = page(elements = listOf(element(visible = false)))
        assertEquals(
            InteractionBlockReason.NOT_VISIBLE,
            (policy.evaluateInteraction(hidden, hidden.elements.first()) as InteractionDecision.Blocked).reason,
        )
        val disabled = page(elements = listOf(element(enabled = false)))
        assertEquals(
            InteractionBlockReason.DISABLED,
            (policy.evaluateInteraction(disabled, disabled.elements.first()) as InteractionDecision.Blocked).reason,
        )
    }

    @Test
    fun nonInteractableRolesAreRefused() {
        val p = page(elements = listOf(element(role = ElementRole.PARAGRAPH, label = "Some text")))
        val decision = policy.evaluateInteraction(p, p.elements.first()) as InteractionDecision.Blocked
        assertEquals(InteractionBlockReason.NOT_INTERACTABLE, decision.reason)
    }

    @Test
    fun verificationChallengeSuspendsAllInteraction() {
        val p = page(
            elements = listOf(element()),
            challenge = VerificationChallenge(ChallengeKind.CAPTCHA, "reCAPTCHA visible"),
        )
        val decision = policy.evaluateInteraction(p, p.elements.first()) as InteractionDecision.Blocked
        assertEquals(InteractionBlockReason.VERIFICATION_IN_PROGRESS, decision.reason)
    }

    @Test
    fun paywalledPagesRequireConfirmationForAnyActivation() {
        val p = page(elements = listOf(element(role = ElementRole.BUTTON, label = "Continue")), paywall = true)
        assertTrue(policy.evaluateInteraction(p, p.elements.first()) is InteractionDecision.RequiresConfirmation)
    }

    // ---- page model ---------------------------------------------------------------------------

    @Test
    fun elementWithoutLabelOrTextIsRejected() {
        try {
            PageElement(ref = "e1", role = ElementRole.BUTTON, accessibleLabel = "", text = null)
            org.junit.Assert.fail("an unlabelled element cannot be described or audited")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("accessible label"))
        }
    }

    @Test
    fun findByNameMatchesLabelsCaseInsensitively() {
        val p = page(
            elements = listOf(
                element(ref = "a", label = "Proceed to checkout"),
                element(ref = "b", label = "Submit"),
                element(ref = "c", role = ElementRole.LINK, label = "Submit an article"),
            ),
        )
        val submitButtons = p.findByName("submit", ElementRole.BUTTON)
        assertEquals(1, submitButtons.size)
        assertEquals("b", submitButtons.first().ref)
    }

    @Test
    fun interactableExcludesHiddenAndDisabled() {
        val p = page(
            elements = listOf(
                element(ref = "a"),
                element(ref = "b", enabled = false),
                element(ref = "c", visible = false),
                element(ref = "d", role = ElementRole.HEADING, label = "Title"),
            ),
        )
        assertEquals(listOf("a"), p.interactable().map { it.ref })
    }

    @Test
    fun describeFormatIsDocumentedShape() {
        val e = element(role = ElementRole.BUTTON, label = "Proceed to checkout")
        assertEquals("BUTTON: Proceed to checkout · enabled=true", e.describe())
    }

    // ---- session ------------------------------------------------------------------------------

    @Test
    fun visitRecordsHistoryAndReportsVerification() {
        val session = BrowserSession("s1", policy)
        val loaded = session.visit(
            page(challenge = VerificationChallenge(ChallengeKind.CAPTCHA, "turnstile")),
            atEpochMs = 10,
            initiatedByAgent = true,
        ) as BrowserSession.VisitResult.Loaded
        assertTrue(loaded.awaitingHuman)
        assertTrue(loaded.notice!!.contains("Complete it yourself"))
        assertTrue(session.isAwaitingHuman())
        assertEquals(1, session.historySnapshot().size)
    }

    @Test
    fun refusedNavigationDoesNotChangeTheCurrentPage() {
        val session = BrowserSession("s1", policy)
        session.visit(page(url = "https://good.com/a"), 1, false)
        val refused = session.visit(page(url = "http://bad.com"), 2, true)
        assertTrue(refused is BrowserSession.VisitResult.Refused)
        assertEquals("https://good.com/a", session.page!!.url)
    }

    @Test
    fun elementReferencesDoNotSurviveNavigation() {
        val session = BrowserSession("s1", policy)
        session.visit(page(url = "https://a.com", elements = listOf(element(ref = "el-1"))), 1, false)
        assertTrue(session.resolve("el-1") is BrowserSession.ResolveResult.Found)

        session.visit(page(url = "https://b.com", elements = listOf(element(ref = "el-9"))), 2, false)
        val stale = session.resolve("el-1")
        assertTrue("a ref from the previous page must not resolve", stale is BrowserSession.ResolveResult.Stale)
    }

    @Test
    fun decideInteractionRejectsUnknownRefs() {
        val session = BrowserSession("s1", policy)
        session.visit(page(), 1, false)
        val decision = session.decideInteraction("nope") as InteractionDecision.Blocked
        assertEquals(InteractionBlockReason.STALE_ELEMENT, decision.reason)
    }

    @Test
    fun humanVerificationCanBeResumedOnceCleared() {
        val session = BrowserSession("s1", policy)
        session.visit(page(challenge = VerificationChallenge(ChallengeKind.CAPTCHA, "x")), 1, true)
        assertTrue(session.isAwaitingHuman())
        assertFalse(
            "still challenged: cannot resume",
            session.resumeAfterHumanVerification(page(challenge = VerificationChallenge(ChallengeKind.CAPTCHA, "x"))),
        )
        assertTrue(session.resumeAfterHumanVerification(page(challenge = null)))
        assertFalse(session.isAwaitingHuman())
    }

    @Test
    fun historyIsBounded() {
        val session = BrowserSession("s1", policy, maxHistoryEntries = 3)
        repeat(10) { i -> session.visit(page(url = "https://a.com/$i"), i.toLong(), false) }
        assertEquals(3, session.historySnapshot().size)
    }

    // ---- reader mode --------------------------------------------------------------------------

    @Test
    fun readerModeStripsBoilerplate() {
        val body = """
            Home
            Menu
            We use cookies to improve your experience. Accept all cookies to continue.
            Android 17 developer preview brings on-device model APIs that let apps run inference
            without a network connection, according to the release notes published today.
            Subscribe to our newsletter for more coverage of mobile platforms and developer tools.
            All rights reserved.
        """.trimIndent()
        val result = ReaderMode.extract(page(text = body))
        assertTrue(result.mainRegionFound)
        assertTrue(result.text.contains("on-device model APIs"))
        assertFalse(result.text.contains("Accept all cookies"))
        assertFalse(result.text.contains("newsletter"))
    }

    @Test
    fun readerModeKeepsRealSentencesGluedToAConsentSentence() {
        // Boilerplate is sentence-scoped: a consent banner sharing a line with real content must not
        // take the real content with it.
        val body = "Android on-device inference is shipping this year. We use cookies to measure " +
            "traffic. Performance claims remain unverified without a device run."
        val result = ReaderMode.extract(page(text = body))
        assertFalse(result.text.contains("We use cookies"))
        assertTrue(result.text.contains("Android on-device inference is shipping this year."))
        assertTrue(result.text.contains("Performance claims remain unverified"))
    }

    @Test
    fun readerModeFallsBackToRawWhenEverythingLooksLikeBoilerplate() {
        // Never hand the model an empty document: a page of pure chrome falls back to the raw text
        // and reports low confidence instead of pretending a main region was found.
        val body = "All rights reserved.\nAdvertisement\nSubscribe to our newsletter."
        val result = ReaderMode.extract(page(text = body))
        assertFalse(result.mainRegionFound)
        assertEquals(0.5, result.confidence, 0.0001)
        assertTrue(result.text.contains("newsletter"))
    }

    @Test
    fun readerModeReportsLowConfidenceWhenNothingStripped() {
        val result = ReaderMode.extract(page(text = "Short page with no boilerplate at all."))
        assertFalse(result.mainRegionFound)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun boundedContextCutsAtSentenceBoundary() {
        val long = (1..60).joinToString(" ") { "Sentence number $it is here." }
        val context = ReaderMode.boundedContext(page(text = long), maxChars = 200)
        assertTrue(context.length <= 200)
        assertTrue("must not cut mid-sentence", context.trimEnd().endsWith("."))
    }

    @Test
    fun boundedContextReturnsEverythingWhenShort() {
        val short = "Only one short sentence."
        assertEquals(short, ReaderMode.boundedContext(page(text = short), maxChars = 500))
    }

    @Test
    fun pageWithoutUrlIsRejected() {
        try {
            ExtractedPage(url = "", title = "x", elements = emptyList(), textContent = "", mainTextChars = 0)
            org.junit.Assert.fail("a page must have a url")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("url"))
        }
    }

    @Test
    fun noPageMeansNoResolution() {
        val session = BrowserSession("s1", policy)
        assertTrue(session.resolve("x") is BrowserSession.ResolveResult.NoPage)
        assertNull(session.page)
    }
}

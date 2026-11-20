package com.samaveta.android.core.browser

/**
 * Agent browser: page understanding.
 *
 * Design decision: SAMAVETA never drives web pages by screen coordinates. Coordinates break on
 * every layout change, every ad, every font-size setting, and they cannot be audited. Instead the
 * browser extracts the *structured* page — elements, roles, labels, states — and acts on element
 * references that are resolved at click time.
 *
 * This module is deliberately transport-agnostic: it operates on whatever the WebView/JS bridge
 * produces, so the extraction and policy rules are unit-testable without a browser.
 */

/** A user-facing page element the agent may interact with. */
data class PageElement(
    val ref: String,
    val role: ElementRole,
    val accessibleLabel: String,
    val text: String? = null,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val isPasswordField: Boolean = false,
    /** True for elements whose activation navigates away or submits data. */
    val causesNavigation: Boolean = false,
    val boundingBox: BoundingBox? = null,
) {
    init {
        require(ref.isNotBlank()) { "element reference required" }
        require(accessibleLabel.isNotBlank() || !text.isNullOrBlank()) {
            "an element must have an accessible label or text; coordinates alone are not enough"
        }
    }

    val displayName: String get() = accessibleLabel.ifBlank { text.orEmpty() }

    /** A one-line description in the format the product documents. */
    fun describe(): String = buildString {
        append(role.name)
        append(": ")
        append(displayName)
        append(" · enabled=")
        append(isEnabled)
        if (isPasswordField) append(" · password")
    }
}

data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Element roles. Kept close to ARIA so the mapping from the accessibility tree is mechanical and
 * reviewable, rather than a bespoke vocabulary that drifts.
 */
enum class ElementRole {
    BUTTON,
    LINK,
    TEXT_FIELD,
    CHECKBOX,
    RADIO,
    SELECT,
    SUBMIT,
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    IMAGE,
    FORM,
    TABLE,
    DIALOG,
    OTHER,
    ;

    val isInteractable: Boolean
        get() = this in setOf(BUTTON, LINK, TEXT_FIELD, CHECKBOX, RADIO, SELECT, SUBMIT)

    val isInput: Boolean
        get() = this in setOf(TEXT_FIELD, CHECKBOX, RADIO, SELECT)
}

/** The extracted page. */
data class ExtractedPage(
    val url: String,
    val title: String,
    val elements: List<PageElement>,
    val textContent: String,
    val mainTextChars: Int,
    /** Detected human-verification challenges. */
    val verificationChallenge: VerificationChallenge? = null,
    /** True when the page requires interaction SAMAVETA must not perform on the user's behalf. */
    val hasPaywall: Boolean = false,
    val hasLoginWall: Boolean = false,
) {
    init {
        require(url.isNotBlank()) { "page url required" }
    }

    fun find(ref: String): PageElement? = elements.firstOrNull { it.ref == ref }

    fun byRole(role: ElementRole): List<PageElement> = elements.filter { it.role == role }

    fun interactable(): List<PageElement> = elements.filter { it.role.isInteractable && it.isEnabled && it.isVisible }

    /** Case-insensitive search over labels — how "find the button labelled Submit" works. */
    fun findByName(name: String, role: ElementRole? = null): List<PageElement> {
        val needle = name.trim().lowercase()
        return elements.filter { element ->
            (role == null || element.role == role) &&
                element.isVisible &&
                (element.displayName.lowercase().contains(needle))
        }
    }

    fun allowlistedPasswordFields(): List<PageElement> = elements.filter { it.isPasswordField }
}

/** A human-verification challenge. SAMAVETA must hand control to the user, never solve it. */
data class VerificationChallenge(val kind: ChallengeKind, val detail: String)

enum class ChallengeKind {
    CAPTCHA,
    TWO_FACTOR,
    BIOMETRIC,
    IDENTITY_DOCUMENT,
    UNUSUAL_TRAFFIC,
    PAYMENT_AUTHORISATION,
    UNKNOWN,
}

/** Outcome of a navigation request, before any page interaction happens. */
sealed interface NavigationDecision {
    data object Allowed : NavigationDecision

    data class Blocked(val reason: NavigationBlockReason, val detail: String) : NavigationDecision
}

enum class NavigationBlockReason(val userMessage: String) {
    NON_HTTPS("Only secure (https) pages can be opened by the agent."),
    DOMAIN_NOT_ALLOWED("This site is not in the list you have allowed for agent browsing."),
    BLOCKED_BY_POLICY("This site is blocked by SAMAVETA's safety policy."),
    LOCAL_NETWORK("Local network addresses cannot be reached by the agent browser."),
    MALFORMED_URL("That address is not valid."),
    DEEP_LINK_TO_APP("That link would open another app; open it yourself if you trust it."),
}

/**
 * Domain policy.
 *
 * Personal browsing is always allowed; *agent-initiated* navigation is restricted to domains the
 * user has approved, plus a small set of things that cannot be exploited (no local network, no file
 * URLs, no javascript:).
 */
class BrowserPolicy(
    private val allowlistedDomains: Set<String> = emptySet(),
    private val blockedDomains: Set<String> = setOf(
        // Deliberately small and documented: this is not a censoring list, it is a list of hosts
        // that are well known to serve hostile redirect chains.
        "doubleclick.net",
        "googlesyndication.com",
    ),
    /** When false, the agent can only navigate to explicitly allowlisted domains. */
    private val allowUnrestrictedUserInitiated: Boolean = true,
) {
    fun evaluateNavigation(rawUrl: String, initiatedByAgent: Boolean): NavigationDecision {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            return NavigationDecision.Blocked(NavigationBlockReason.MALFORMED_URL, "The URL is empty.")
        }
        val lower = url.lowercase()

        // Reject dangerous schemes before anything else can look at them.
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("blob:")) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.BLOCKED_BY_POLICY,
                "Executable URLs (${lower.substringBefore(':')}:) are never followed.",
            )
        }
        if (lower.startsWith("file:")) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.BLOCKED_BY_POLICY,
                "Local files must be opened by the user, not by the agent.",
            )
        }
        if (lower.startsWith("intent:") || lower.startsWith("market:") || lower.startsWith("tel:")
            || lower.startsWith("sms:") || lower.startsWith("mailto:")
        ) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.DEEP_LINK_TO_APP,
                "Deep links are handed to the user rather than followed automatically.",
            )
        }
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.MALFORMED_URL,
                "Unrecognised URL scheme.",
            )
        }

        val host = hostOf(url)
            ?: return NavigationDecision.Blocked(NavigationBlockReason.MALFORMED_URL, "Could not parse a host.")

        if (isLocalOrPrivate(host)) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.LOCAL_NETWORK,
                "Local and private addresses are not reachable by the agent browser.",
            )
        }
        if (matchesAny(host, blockedDomains)) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.BLOCKED_BY_POLICY,
                "Host '$host' is on the blocked list.",
            )
        }
        // http:// is refused for agent navigation but allowed when the user typed it (they see the
        // insecure badge and decide).
        if (lower.startsWith("http://") && initiatedByAgent) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.NON_HTTPS,
                "Agent-initiated navigation must use https.",
            )
        }
        if (initiatedByAgent) {
            val allowlisted = matchesAny(host, allowlistedDomains)
            if (!allowlisted && !allowUnrestrictedUserInitiated) {
                return NavigationDecision.Blocked(
                    NavigationBlockReason.DOMAIN_NOT_ALLOWED,
                    "The agent may only browse domains you have approved.",
                )
            }
            if (allowlistedDomains.isNotEmpty() && !allowlisted) {
                return NavigationDecision.Blocked(
                    NavigationBlockReason.DOMAIN_NOT_ALLOWED,
                    "Host '$host' is not in the approved domain list.",
                )
            }
        } else if (allowlistedDomains.isNotEmpty() && !matchesAny(host, allowlistedDomains)) {
            return NavigationDecision.Blocked(
                NavigationBlockReason.DOMAIN_NOT_ALLOWED,
                "Host '$host' is not in the approved domain list.",
            )
        }
        return NavigationDecision.Allowed
    }

    /**
     * Whether an agent may act on an element.
     *
     * Rules that exist because getting them wrong is harmful:
     *  - an invisible or disabled element is never clicked (silent no-ops look like success);
     *  - a password field is never filled by the agent;
     *  - anything that submits data or navigates requires explicit confirmation upstream, which is
     *    signalled by returning [InteractionDecision.RequiresConfirmation];
     *  - a page under verification stops all interaction.
     */
    fun evaluateInteraction(page: ExtractedPage, element: PageElement): InteractionDecision {
        if (page.verificationChallenge != null) {
            return InteractionDecision.Blocked(
                InteractionBlockReason.VERIFICATION_IN_PROGRESS,
                "A human-verification step is in progress. Control has been handed to you.",
            )
        }
        if (!element.isVisible) {
            return InteractionDecision.Blocked(
                InteractionBlockReason.NOT_VISIBLE,
                "'${element.displayName}' is not visible; acting on it would do nothing.",
            )
        }
        if (!element.isEnabled) {
            return InteractionDecision.Blocked(
                InteractionBlockReason.DISABLED,
                "'${element.displayName}' is disabled.",
            )
        }
        if (element.isPasswordField) {
            return InteractionDecision.Blocked(
                InteractionBlockReason.CREDENTIAL_FIELD,
                "SAMAVETA never types into password fields.",
            )
        }
        if (!element.role.isInteractable) {
            return InteractionDecision.Blocked(
                InteractionBlockReason.NOT_INTERACTABLE,
                "'${element.displayName}' is a ${element.role.name.lowercase()} and cannot be activated.",
            )
        }
        val sensitive = element.causesNavigation ||
            element.role == ElementRole.SUBMIT ||
            page.hasPaywall ||
            page.hasLoginWall
        return if (sensitive) {
            InteractionDecision.RequiresConfirmation(
                "Activating '${element.displayName}' will submit or navigate on ${hostOf(page.url) ?: page.url}.",
            )
        } else {
            InteractionDecision.Allowed
        }
    }

    private fun matchesAny(host: String, domains: Set<String>): Boolean =
        domains.any { domain ->
            val d = domain.lowercase().removePrefix(".")
            host == d || host.endsWith(".$d")
        }

    private fun isLocalOrPrivate(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host == "::1" || host == "[::1]") return true
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host)
        if (ipv4 != null) {
            val (a, b) = ipv4.destructured
            val first = a.toIntOrNull() ?: return true
            val second = b.toIntOrNull() ?: return true
            return first == 10 ||
                first == 127 ||
                (first == 192 && second == 168) ||
                (first == 172 && second in 16..31) ||
                (first == 169 && second == 254) ||
                first == 0
        }
        return false
    }

    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfter('@') // strip any userinfo
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
        return host.lowercase().ifEmpty { null }
    }
}

sealed interface InteractionDecision {
    data object Allowed : InteractionDecision

    data class RequiresConfirmation(val reason: String) : InteractionDecision

    data class Blocked(val reason: InteractionBlockReason, val detail: String) : InteractionDecision
}

enum class InteractionBlockReason {
    NOT_VISIBLE,
    DISABLED,
    CREDENTIAL_FIELD,
    NOT_INTERACTABLE,
    VERIFICATION_IN_PROGRESS,
    STALE_ELEMENT,
}

/**
 * Tracks a browser session for the agent.
 *
 * Element references are session-scoped: they are invalidated on every navigation, because a ref
 * that survives a page change is how an agent clicks the wrong thing on the next page.
 */
class BrowserSession(
    val sessionId: String,
    private val policy: BrowserPolicy,
    private val maxHistoryEntries: Int = 200,
) {
    private var currentPage: ExtractedPage? = null
    private val history = ArrayDeque<PageVisit>()
    private var generation = 0

    val page: ExtractedPage? get() = currentPage

    /** True while a human-verification challenge is open: all agent interaction is suspended. */
    fun isAwaitingHuman(): Boolean = currentPage?.verificationChallenge != null

    fun visit(page: ExtractedPage, atEpochMs: Long, initiatedByAgent: Boolean): BrowserSession.VisitResult {
        val decision = policy.evaluateNavigation(page.url, initiatedByAgent)
        if (decision is NavigationDecision.Blocked) {
            return BrowserSession.VisitResult.Refused(decision.reason, decision.detail)
        }
        generation++
        currentPage = page
        history.addLast(PageVisit(page.url, page.title, atEpochMs))
        while (history.size > maxHistoryEntries) history.removeFirst()
        return BrowserSession.VisitResult.Loaded(
            page = page,
            awaitingHuman = page.verificationChallenge != null,
            notice = when {
                page.verificationChallenge != null ->
                    "A human-verification step is required. Complete it yourself; SAMAVETA will resume afterwards."
                page.hasPaywall -> "This page is paywalled. Only the visible portion was read."
                page.hasLoginWall -> "This page requires a login. SAMAVETA did not attempt to sign in."
                else -> null
            },
        )
    }

    /** Resolves an element reference, refusing stale refs from a previous page. */
    fun resolve(ref: String): BrowserSession.ResolveResult {
        val page = currentPage ?: return BrowserSession.ResolveResult.NoPage
        val element = page.find(ref) ?: return BrowserSession.ResolveResult.Stale(ref)
        return BrowserSession.ResolveResult.Found(element, generation)
    }

    fun decideInteraction(ref: String): InteractionDecision {
        val page = currentPage
            ?: return InteractionDecision.Blocked(
                InteractionBlockReason.STALE_ELEMENT,
                "No page is open.",
            )
        val element = page.find(ref)
            ?: return InteractionDecision.Blocked(
                InteractionBlockReason.STALE_ELEMENT,
                "That element is no longer on the page.",
            )
        return policy.evaluateInteraction(page, element)
    }

    /** Called when the user says they have finished a verification step. */
    fun resumeAfterHumanVerification(refreshedPage: ExtractedPage): Boolean {
        if (refreshedPage.verificationChallenge != null) return false
        currentPage = refreshedPage
        generation++
        return true
    }

    fun historySnapshot(): List<PageVisit> = history.toList()

    data class PageVisit(val url: String, val title: String, val atEpochMs: Long)

    sealed interface VisitResult {
        data class Loaded(val page: ExtractedPage, val awaitingHuman: Boolean, val notice: String?) : VisitResult

        data class Refused(val reason: NavigationBlockReason, val detail: String) : VisitResult
    }

    sealed interface ResolveResult {
        data class Found(val element: PageElement, val pageGeneration: Int) : ResolveResult

        data class Stale(val ref: String) : ResolveResult

        data object NoPage : ResolveResult
    }
}

/**
 * Reader mode / summarisation input.
 *
 * Strips navigation chrome, cookie banners and boilerplate so a model summarises the article rather
 * than a menu. Deliberately conservative: if it cannot identify a main region, it returns the whole
 * text *and says so*, so the caller can report lower confidence.
 */
object ReaderMode {

    data class Result(val text: String, val mainRegionFound: Boolean, val removedChars: Int) {
        val confidence: Double get() = if (mainRegionFound) 0.9 else 0.5
    }

    /**
     * Boilerplate is sentence-scoped, not paragraph-scoped: consent banners, newsletter prompts and
     * legal footers are routinely glued onto a real paragraph. Patterns therefore run per sentence
     * and may cross internal punctuation, but never cross a sentence boundary.
     */
    private val boilerplatePatterns = listOf(
        Regex("(?i)\\bcookies?\\b.*\\b(accept|consent|preferences|agree)\\b"),
        Regex("(?i)\\b(accept|reject) (all )?(cookies|tracking|optional)\\b"),
        Regex("(?i)\\bwe use cookies\\b"),
        Regex("(?i)\\bsubscribe\\b.*\\bnewsletter\\b"),
        Regex("(?i)\\ball rights reserved\\b"),
        Regex("(?i)\\b(sign in|log in|register)\\b.*\\b(to continue|to read|for free)\\b"),
        Regex("(?i)\\badvertisement\\b"),
        Regex("(?i)^\\s*(ad|sponsored)\\s*[:|-]"),
        Regex("(?i)\\b(privacy policy|terms of service|terms of use)\\b\\s*\\.?\\s*$"),
        Regex("(?i)^\\s*share (this|on)\\b"),
    )

    private val sentenceBoundary = Regex("(?<=[.!?])\\s+")

    /** Retained body must be at least this long before we claim a main region was located. */
    private const val MIN_MAIN_CHARS = 120

    private fun isBoilerplate(sentence: String): Boolean =
        boilerplatePatterns.any { it.containsMatchIn(sentence) }

    /**
     * Removes boilerplate sentences from a line and keeps the rest in reading order. Returns null
     * when the line contributes no content, so the caller can drop it entirely.
     */
    private fun cleanLine(line: String): String? {
        val sentences = line.split(sentenceBoundary)
        val kept = sentences.filterNot { isBoilerplate(it) }.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return null
        return kept.joinToString(" ")
    }

    private val chromeLinePatterns = listOf(
        Regex("^(home|menu|search|share|back|next|previous|skip to content)\\s*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*[|·•»]\\s*$"),
    )

    fun extract(page: ExtractedPage): Result {
        val raw = page.textContent
        val lines = raw.split(Regex("\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line -> chromeLinePatterns.any { it.matches(line) } }

        val kept = lines.mapNotNull { cleanLine(it) }
        val text = kept.joinToString("\n\n")
        val removed = raw.length - text.length

        // Never hand the model an empty document. Only when cleaning removed *everything* do we
        // fall back to the raw text, and we then report low confidence instead of high.
        if (text.isBlank()) {
            return Result(text = raw, mainRegionFound = false, removedChars = 0)
        }

        // "Main region found" additionally requires that enough body survived to be useful.
        return Result(
            text = text,
            mainRegionFound = removed > 0 && text.length >= MIN_MAIN_CHARS,
            removedChars = removed,
        )
    }

    /** Builds the prompt-safe context for summarisation, bounded by characters not tokens. */
    fun boundedContext(page: ExtractedPage, maxChars: Int = 6_000): String {
        val result = extract(page)
        return if (result.text.length <= maxChars) {
            result.text
        } else {
            // Cut at a sentence boundary so a truncated quote cannot change meaning.
            val cut = result.text.substring(0, maxChars)
            val lastStop = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            if (lastStop > maxChars / 2) cut.substring(0, lastStop + 1) else cut
        }
    }
}

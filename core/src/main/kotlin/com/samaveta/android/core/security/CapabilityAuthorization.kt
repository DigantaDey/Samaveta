package com.samaveta.android.core.security

/**
 * Capability authorization: the deterministic permission engine required by the product directive.
 *
 * Models (and LAYA) may *propose* actions. This engine alone decides whether an action is
 * authorized, and it never consults a model to do so. Boundaries are enforced here, in code, so no
 * prompt injection or malformed model output can widen them.
 */

/** Every capability SAMAVETA can expose. Stable identifiers are used on the wire and in audits. */
enum class Capability(
    val id: String,
    val displayName: String,
    val sensitivity: Sensitivity,
    /** True when the action changes state outside SAMAVETA (send, delete, purchase, modify). */
    val mutatesState: Boolean,
) {
    NOTIFICATIONS_READ("notifications.read", "Read notifications", Sensitivity.LOW, mutatesState = false),
    NOTIFICATIONS_SUMMARIZE("notifications.summarize", "Summarise messages", Sensitivity.LOW, mutatesState = false),
    MESSAGING_DRAFT("messaging.draft", "Draft replies", Sensitivity.MEDIUM, mutatesState = false),
    MESSAGING_SEND("messaging.send", "Send messages", Sensitivity.HIGH, mutatesState = true),
    CONTACTS_READ("contacts.read", "Read contacts", Sensitivity.MEDIUM, mutatesState = false),
    CALENDAR_READ("calendar.read", "Read calendar", Sensitivity.MEDIUM, mutatesState = false),
    CALENDAR_WRITE("calendar.write", "Create calendar events", Sensitivity.MEDIUM, mutatesState = true),
    FILES_READ("files.read", "Read selected files", Sensitivity.MEDIUM, mutatesState = false),
    FILES_WRITE("files.write", "Write files", Sensitivity.HIGH, mutatesState = true),
    FILES_DELETE("files.delete", "Delete files", Sensitivity.CRITICAL, mutatesState = true),
    CAMERA_CAPTURE("camera.capture", "Take photos", Sensitivity.MEDIUM, mutatesState = false),
    MICROPHONE_RECORD("microphone.record", "Record audio", Sensitivity.HIGH, mutatesState = false),
    LOCATION_READ("location.read", "Read approximate location", Sensitivity.HIGH, mutatesState = false),
    LOCATION_READ_PRECISE("location.read_precise", "Read precise location", Sensitivity.CRITICAL, mutatesState = false),
    CALLS_PLACE("calls.place", "Place calls", Sensitivity.HIGH, mutatesState = true),
    CALLS_SCREEN("calls.screen", "Screen calls", Sensitivity.HIGH, mutatesState = false),
    BROWSER_NAVIGATE("browser.navigate", "Browse the web", Sensitivity.LOW, mutatesState = false),
    BROWSER_SUBMIT("browser.submit", "Submit forms on web pages", Sensitivity.HIGH, mutatesState = true),
    MEMORY_WRITE("memory.write", "Store memories", Sensitivity.LOW, mutatesState = false),
    MEMORY_DELETE("memory.delete", "Delete memories", Sensitivity.MEDIUM, mutatesState = true),
    PURCHASES_EXECUTE("purchases.execute", "Make purchases", Sensitivity.CRITICAL, mutatesState = true),
    SETTINGS_MODIFY_SECURITY("settings.modify_security", "Change security settings", Sensitivity.CRITICAL, mutatesState = true),
    AUTOMATION_DISABLE_CONFIRMATION("automation.disable_confirmation", "Disable confirmations", Sensitivity.CRITICAL, mutatesState = true),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): Capability? = byId[id]

        fun requireId(id: String): Capability =
            byId[id] ?: throw IllegalArgumentException("unknown capability '$id'")

        fun ids(): Set<String> = byId.keys
    }
}

enum class Sensitivity { LOW, MEDIUM, HIGH, CRITICAL }

/**
 * Per-capability user configuration. Defaults are deliberately restrictive: nothing that touches
 * the user's data or the outside world is enabled until the user turns it on.
 */
enum class GrantState {
    /** Not granted. Requests are refused. */
    DENIED,

    /** Granted for this session only; cleared on process death. */
    SESSION,

    /** Granted until the user revokes it. */
    PERSISTENT,
    ;

    val isGranted: Boolean get() = this != DENIED

    /** Critical capabilities are never remembered persistently without an explicit confirmation flag. */
    fun allowsPersistence(): Boolean = this != SESSION
}

data class CapabilityPolicy(
    val capability: Capability,
    val grant: GrantState,
    /** When set, every individual invocation must be confirmed by the user. */
    val confirmEachUse: Boolean,
) {
    companion object {
        /** The shipped default for a capability the user has not configured. */
        fun defaultFor(capability: Capability): CapabilityPolicy = when (capability.sensitivity) {
            Sensitivity.LOW -> CapabilityPolicy(capability, GrantState.DENIED, confirmEachUse = false)
            Sensitivity.MEDIUM -> CapabilityPolicy(capability, GrantState.DENIED, confirmEachUse = true)
            // HIGH and CRITICAL always require per-use confirmation, even when granted.
            else -> CapabilityPolicy(capability, GrantState.DENIED, confirmEachUse = true)
        }
    }
}

/** Authoritative, in-memory view of the user's grants. Persisted by the Android layer. */
class CapabilityRegistry(policies: Collection<CapabilityPolicy> = emptyList()) {
    private val policies = LinkedHashMap<String, CapabilityPolicy>()

    init {
        Capability.entries.forEach { capability ->
            this.policies[capability.id] = CapabilityPolicy.defaultFor(capability)
        }
        policies.forEach { set(it) }
    }

    fun set(policy: CapabilityPolicy) {
        // A CRITICAL capability must never be remembered as persistently granted: that is how an
        // agent ends up able to spend money after a single careless tap.
        val normalised = if (policy.grant == GrantState.PERSISTENT && policy.capability.sensitivity == Sensitivity.CRITICAL) {
            policy.copy(grant = GrantState.SESSION, confirmEachUse = true)
        } else {
            policy
        }
        policies[normalised.capability.id] = normalised
    }

    fun policyFor(capability: Capability): CapabilityPolicy =
        policies.getValue(capability.id)

    fun grantsFor(capability: Capability): GrantState = policyFor(capability).grant

    fun revokeAll() {
        policies.replaceAll { _, p -> p.copy(grant = GrantState.DENIED) }
    }

    /** Clears SESSION grants (called on process start / app pause-depending on settings). */
    fun clearSessionGrants() {
        policies.replaceAll { _, p ->
            if (p.grant == GrantState.SESSION) p.copy(grant = GrantState.DENIED) else p
        }
    }

    fun grantedCapabilityIds(): Set<String> =
        policies.values.filter { it.grant.isGranted }.map { it.capability.id }.toSet()
}

/** Why an action was refused. Every reason is user-explainable. */
enum class DenialReason(val userMessage: String) {
    UNKNOWN_CAPABILITY("That capability does not exist in SAMAVETA."),
    NOT_GRANTED("Permission for this capability has not been granted."),
    AUTOMATION_PAUSED("Automation is paused. Nothing will run until you resume it."),
    EMERGENCY_STOP("The emergency stop is engaged. All automation is halted."),
    CONFIRMATION_REQUIRED("This action needs your explicit confirmation."),
    CONFIRMATION_EXPIRED("The confirmation expired; please confirm again."),
    CONFIRMATION_MISMATCH("The confirmation did not match the action to perform."),
    PLATFORM_PERMISSION_MISSING("Android has not granted the underlying system permission."),
    CAPABILITY_NOT_SUPPORTED("This device or Android version does not support the capability."),
    SCOPE_VIOLATION("The request falls outside the scope the user approved."),
    RATE_LIMITED("Too many requests of this kind in a short period."),
    AGENT_HALTED("The task was cancelled."),
}

/** Result of an authorization decision. */
sealed interface AuthorizationDecision {
    data class Authorized(val capability: Capability, val requiresConfirmation: Boolean) : AuthorizationDecision

    data class Denied(val capability: Capability?, val reason: DenialReason, val detail: String? = null) : AuthorizationDecision
}

/**
 * Scope limits supplied by the user for a specific grant — e.g. "send to these contacts only" or
 * "read files under Documents/". Enforced before the capability runs.
 */
data class ScopeRestriction(
    val capability: Capability,
    /** Empty set means no restriction. */
    val allowedTargets: Set<String> = emptySet(),
    val maxInvocationsPerHour: Int? = null,
)

class CapabilityAuthorizer(
    private val registry: CapabilityRegistry,
    private val scopes: Map<Capability, ScopeRestriction> = emptyMap(),
    private val automationPaused: () -> Boolean = { false },
    private val emergencyStopped: () -> Boolean = { false },
    private val platformSupport: (Capability) -> PlatformSupport = { PlatformSupport.SUPPORTED },
) {

    /**
     * Decides whether [capability] may run against [target].
     *
     * Check order is intentional: global stops first (emergency stop must win over everything),
     * then device support, then user grant, then scope, then confirmation requirements.
     */
    fun authorize(capability: Capability, target: String? = null): AuthorizationDecision {
        if (emergencyStopped()) {
            return AuthorizationDecision.Denied(capability, DenialReason.EMERGENCY_STOP)
        }
        if (automationPaused()) {
            // Read-only inspection capabilities still work while automation is paused, because
            // pausing automation should not make the app unusable.
            if (capability.mutatesState) {
                return AuthorizationDecision.Denied(capability, DenialReason.AUTOMATION_PAUSED)
            }
        }
        when (val support = platformSupport(capability)) {
            is PlatformSupport.Unsupported ->
                return AuthorizationDecision.Denied(capability, DenialReason.CAPABILITY_NOT_SUPPORTED, support.detail)
            PlatformSupport.PermissionNotGranted ->
                return AuthorizationDecision.Denied(capability, DenialReason.PLATFORM_PERMISSION_MISSING)
            PlatformSupport.SUPPORTED -> Unit
        }

        val policy = registry.policyFor(capability)
        if (!policy.grant.isGranted) {
            return AuthorizationDecision.Denied(capability, DenialReason.NOT_GRANTED)
        }

        val scope = scopes[capability]
        if (scope != null && scope.allowedTargets.isNotEmpty()) {
            if (target == null || target !in scope.allowedTargets) {
                return AuthorizationDecision.Denied(
                    capability,
                    DenialReason.SCOPE_VIOLATION,
                    "Target '$target' is not in the approved list for ${capability.id}.",
                )
            }
        }

        val needsConfirmation = policy.confirmEachUse ||
            capability.sensitivity == Sensitivity.CRITICAL ||
            capability.mutatesState
        return AuthorizationDecision.Authorized(capability, needsConfirmation)
    }
}

sealed interface PlatformSupport {
    data object SUPPORTED : PlatformSupport
    data object PermissionNotGranted : PlatformSupport
    data class Unsupported(val detail: String) : PlatformSupport
}

/**
 * A confirmation token binds a user's approval to one exact action, so an approval cannot be
 * replayed for a different action (classic confused-deputy problem).
 */
data class ConfirmationRequest(
    val token: String,
    val capability: Capability,
    val target: String?,
    val actionDescription: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(token.isNotBlank()) { "confirmation token required" }
        require(expiresAtEpochMs > issuedAtEpochMs) { "confirmation must expire after it is issued" }
    }
}

/**
 * A user approval, bound to one exact action and carrying its own expiry. The expiry is stored on
 * the grant rather than looked up from the pending request, because [ConfirmationBroker.grant]
 * removes that request: a grant that read its deadline from a deleted record could never expire.
 */
data class ConfirmationGrant(
    val token: String,
    val capability: Capability,
    val target: String?,
    val grantedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun isExpiredAt(now: Long): Boolean = now > expiresAtEpochMs
}

class ConfirmationBroker(private val ttlMs: Long = 120_000) {

    private val pending = LinkedHashMap<String, ConfirmationRequest>()
    private val granted = LinkedHashMap<String, ConfirmationGrant>()

    fun request(
        token: String,
        capability: Capability,
        target: String?,
        actionDescription: String,
        now: Long,
    ): ConfirmationRequest {
        val request = ConfirmationRequest(
            token = token,
            capability = capability,
            target = target,
            actionDescription = actionDescription,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + ttlMs,
        )
        pending[token] = request
        return request
    }

    fun grant(token: String, now: Long): Boolean {
        val request = pending[token] ?: return false
        if (now > request.expiresAtEpochMs) {
            pending.remove(token)
            return false
        }
        granted[token] = ConfirmationGrant(
            token = token,
            capability = request.capability,
            target = request.target,
            grantedAtEpochMs = now,
            expiresAtEpochMs = request.expiresAtEpochMs,
        )
        pending.remove(token)
        return true
    }

    /**
     * Consumes a grant for exactly one invocation. A confirmation is single-use: replaying it for a
     * second send/delete is refused.
     */
    fun consume(token: String, capability: Capability, target: String?, now: Long): AuthorizationDecision {
        val grant = granted[token]
            ?: return AuthorizationDecision.Denied(capability, DenialReason.CONFIRMATION_REQUIRED)
        // Single-use: the grant is burned whatever the outcome, so a mismatch cannot be retried
        // into a success by editing the target.
        granted.remove(token)
        if (grant.capability != capability || grant.target != target) {
            return AuthorizationDecision.Denied(capability, DenialReason.CONFIRMATION_MISMATCH)
        }
        if (grant.isExpiredAt(now)) {
            return AuthorizationDecision.Denied(capability, DenialReason.CONFIRMATION_EXPIRED)
        }
        return AuthorizationDecision.Authorized(capability, requiresConfirmation = false)
    }

    fun pendingRequests(): List<ConfirmationRequest> = pending.values.toList()
}

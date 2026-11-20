package com.samaveta.android.core.laya

import com.samaveta.android.core.model.AdmissionDecision
import com.samaveta.android.core.model.AdmissionRejection
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelRegistry
import com.samaveta.android.core.device.DeviceCapabilities
import com.samaveta.android.core.device.DeviceProfile

/**
 * Validates a plan proposed by LAYA before SAMAVETA executes any part of it.
 *
 * This is the core safety and correctness boundary required by the product directive:
 * LAYA *decides*, SAMAVETA *validates and executes*. Nothing here may be delegated to a model.
 *
 * The validator is deliberately deterministic and side-effect free so it can be exhaustively
 * unit-tested. It never "fixes" LAYA's plan silently: an invalid step is reported back to LAYA.
 */
class LayaDecisionValidator(
    private val registry: ModelRegistry,
    private val device: DeviceProfile,
    private val capabilities: DeviceCapabilities,
    private val capabilityInventory: Set<String>,
    private val maxSteps: Int = 8,
) {
    init {
        require(maxSteps in 1..32) { "maxSteps out of range" }
    }

    fun validate(plan: LayaPlan): PlanVerdict {
        val issues = mutableListOf<PlanIssue>()

        if (plan.steps.size > maxSteps) {
            issues += PlanIssue(
                steps = plan.steps.map { it.index },
                code = IssueCode.TOO_MANY_STEPS,
                detail = "Plan has ${plan.steps.size} steps; the limit is $maxSteps.",
            )
        }

        // Step indices must be unique and contiguous from 0, otherwise the executor could skip or
        // repeat work while reporting success.
        val indices = plan.steps.map { it.index }
        if (indices.toSet().size != indices.size) {
            issues += PlanIssue(indices, IssueCode.DUPLICATE_STEP_INDEX, "Step indices must be unique.")
        }
        if (indices.sorted() != indices) {
            issues += PlanIssue(indices, IssueCode.OUT_OF_ORDER_STEPS, "Steps must be ordered by index.")
        }

        for (step in plan.steps) {
            when (step.kind) {
                StepKind.MODEL_INFERENCE -> {
                    val modelId = step.modelId!!
                    when (val decision = registry.evaluate(modelId, device, capabilities)) {
                        is AdmissionDecision.Rejected -> issues += PlanIssue(
                            steps = listOf(step.index),
                            code = IssueCode.MODEL_NOT_EXECUTABLE,
                            detail = decision.explanation,
                            rejection = decision.reason,
                        )

                        is AdmissionDecision.Admitted -> {
                            val model = decision.model
                            val required = requiredCapability(plan)
                            if (required != null && required !in model.capabilities) {
                                issues += PlanIssue(
                                    steps = listOf(step.index),
                                    code = IssueCode.CAPABILITY_MISMATCH,
                                    detail = "Model '${model.id}' does not provide ${required.name}.",
                                )
                            }
                        }
                    }
                }

                StepKind.CAPABILITY_INVOCATION -> {
                    val capability = step.capability!!
                    if (capability !in capabilityInventory) {
                        issues += PlanIssue(
                            steps = listOf(step.index),
                            code = IssueCode.CAPABILITY_UNAVAILABLE,
                            detail = "Capability '$capability' is not available on this device or is not authorized.",
                        )
                    }
                }

                else -> Unit
            }
        }

        for (capability in plan.requestedCapabilities) {
            if (capability !in capabilityInventory) {
                issues += PlanIssue(
                    steps = plan.steps.map { it.index }.take(1),
                    code = IssueCode.CAPABILITY_UNAVAILABLE,
                    detail = "Plan requests unauthorized or unavailable capability '$capability'.",
                )
            }
        }

        // A plan that performs an outward-facing or irreversible action must be user-confirmed
        // regardless of what LAYA says.
        val sideEffect = plan.performsSideEffect()
        val needsConfirmation = sideEffect.requiresConfirmation ||
            plan.requiresUserConfirmation ||
            plan.requestedCapabilities.any { it in SENSITIVE_CAPABILITIES }

        return if (issues.isEmpty()) {
            PlanVerdict.Acceptable(
                plan = plan,
                requiresUserConfirmation = needsConfirmation,
                confirmationReason = if (needsConfirmation) sideEffect.reason ?: "The plan requests sensitive capabilities." else null,
            )
        } else {
            PlanVerdict.Rejected(plan.requestId, issues)
        }
    }

    private fun requiredCapability(plan: LayaPlan): ModelCapability? =
        plan.steps.firstNotNullOfOrNull { step ->
            when (step.kind) {
                StepKind.PERCEPTION_OCR -> ModelCapability.OCR
                StepKind.PERCEPTION_VISION -> ModelCapability.VISION_LANGUAGE
                StepKind.MODEL_INFERENCE -> null
                else -> null
            }
        }

    private fun LayaPlan.performsSideEffect(): SideEffect =
        when {
            steps.any { it.kind == StepKind.CAPABILITY_INVOCATION } ->
                SideEffect(true, "The plan invokes a device capability that changes state outside SAMAVETA.")
            steps.any { it.kind == StepKind.BROWSER_TASK } ->
                SideEffect(true, "The plan interacts with web pages, which may submit forms or make purchases.")
            steps.any { it.kind == StepKind.MEMORY_WRITE } ->
                SideEffect(false, null)
            else -> SideEffect(false, null)
        }

    private data class SideEffect(val requiresConfirmation: Boolean, val reason: String?)

    companion object {
        /** Capabilities that always need an explicit user decision, never a silent execution. */
        val SENSITIVE_CAPABILITIES: Set<String> = setOf(
            "messaging.send",
            "files.delete",
            "files.write",
            "calls.place",
            "location.read_precise",
            "purchases.execute",
            "settings.modify_security",
            "automation.disable_confirmation",
        )
    }
}

sealed interface PlanVerdict {
    data class Acceptable(
        val plan: LayaPlan,
        val requiresUserConfirmation: Boolean,
        val confirmationReason: String?,
    ) : PlanVerdict

    data class Rejected(val requestId: String, val issues: List<PlanIssue>) : PlanVerdict {
        /** A compact, machine-readable message SAMAVETA can send back to LAYA. */
        fun toFeedbackJson(): String {
            val issuesJson = issues.joinToString(",") { issue ->
                """{"code":"${issue.code.name}","steps":${issue.steps},"detail":${quote(issue.detail)}}"""
            }
            return """{"request_id":${quote(requestId)},"type":"plan_rejected","issues":[$issuesJson]}"""
        }

        private fun quote(s: String): String = com.samaveta.android.core.json.JsonValue.Str(s)
            .let { com.samaveta.android.core.json.Json.write(it) }
    }
}

data class PlanIssue(
    val steps: List<Int>,
    val code: IssueCode,
    val detail: String,
    val rejection: AdmissionRejection? = null,
)

enum class IssueCode {
    MODEL_NOT_EXECUTABLE,
    CAPABILITY_MISMATCH,
    CAPABILITY_UNAVAILABLE,
    TOO_MANY_STEPS,
    DUPLICATE_STEP_INDEX,
    OUT_OF_ORDER_STEPS,
}

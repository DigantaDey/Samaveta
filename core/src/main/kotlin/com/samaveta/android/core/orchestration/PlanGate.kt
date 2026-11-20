package com.samaveta.android.core.orchestration

import com.samaveta.android.core.laya.LayaDecisionValidator
import com.samaveta.android.core.laya.LayaPlan
import com.samaveta.android.core.laya.PlanVerdict
import com.samaveta.android.core.security.Capability

/**
 * The complete pre-execution gate for a LAYA plan.
 *
 * Two validators exist because they answer different questions, and a plan must satisfy both:
 *
 *  - [LayaDecisionValidator] asks *"can this device actually do it?"* — model admission against the
 *    registry and device profile, capability inventory, and whether a sensitive action needs the
 *    user.
 *  - [PlanValidator] asks *"is this plan trying to widen its own authority?"* — capabilities it used
 *    without declaring, capabilities that do not exist at all, malformed step indexing, and runaway
 *    or oversized plans.
 *
 * Keeping them separate but composed is deliberate: the device question changes as models are
 * installed, while the authority question is a fixed policy. Running both, always, in this order,
 * means neither can be skipped by forgetting to call it.
 */
class PlanGate(
    private val decisionValidator: LayaDecisionValidator,
    private val shapeValidatorMaxSteps: Int = PlanValidator.MAX_STEPS,
) {

    sealed interface Result {
        /**
         * The plan may run.
         *
         * @param requiresUserConfirmation true when the plan performs something outward-facing; the
         *   runner still asks per capability, but this is the whole-plan prompt the UI shows first.
         */
        data class Accepted(
            val plan: LayaPlan,
            val capabilities: List<Capability>,
            val requiresUserConfirmation: Boolean,
            val confirmationReason: String?,
        ) : Result

        /** The plan may not run. [issues] are user-explainable and are also sent back to LAYA. */
        data class Rejected(val issues: List<String>) : Result
    }

    fun validate(plan: LayaPlan): Result {
        // Device/authority-of-inventory first: a plan naming a model we cannot admit is not worth
        // checking for escalation, and the rejection the user sees should name the real problem.
        when (val verdict = decisionValidator.validate(plan)) {
            is PlanVerdict.Rejected -> return Result.Rejected(verdict.issues.map { it.detail })
            is PlanVerdict.Acceptable -> {
                return when (val shape = PlanValidator.validate(plan, maxSteps = shapeValidatorMaxSteps)) {
                    is PlanValidation.Rejected -> Result.Rejected(listOf(shape.reason))
                    is PlanValidation.Accepted -> Result.Accepted(
                        plan = plan,
                        capabilities = shape.capabilities,
                        requiresUserConfirmation = verdict.requiresUserConfirmation,
                        confirmationReason = verdict.confirmationReason,
                    )
                }
            }
        }
    }
}

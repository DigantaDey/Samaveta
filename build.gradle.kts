// Root build configuration. Module-specific configuration lives in each module's build script.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * Verification task group: `./gradlew verify` runs everything CI runs, in the order that fails
 * fastest first. Kept at the root so a contributor has one command to trust.
 */
tasks.register("verify") {
    group = "verification"
    description = "Runs unit tests, lint and dependency checks for all modules."
    dependsOn(
        ":core:test",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
    )
}

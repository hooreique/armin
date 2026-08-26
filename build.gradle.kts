plugins {
    id("com.android.application") version "9.3.2" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/.gradle/**", "**/build/**")
        ktfmt("0.64").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("spotlessCheck") {
    dependsOn(":app:spotlessCheck")
}

tasks.named("spotlessApply") {
    dependsOn(":app:spotlessApply")
}

tasks.register("quality") {
    group = "verification"
    description = "Runs formatting, analysis, tests, and distribution license checks."
    dependsOn(
        "spotlessCheck",
        ":app:detekt",
        ":app:lintDebug",
        ":app:test",
        ":app:verifyDebugLegalAssets",
        ":app:verifyDebugRuntimeLicenseInventory",
    )
}

tasks.register("ci") {
    group = "verification"
    description = "Runs the checks used by pull request CI."
    dependsOn(
        "spotlessCheck",
        ":app:detekt",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
    )
}

import com.android.build.api.artifact.SingleArtifact
import io.gitlab.arturbosch.detekt.Detekt
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@CacheableTask
abstract class GenerateLegalAssetsTask
@Inject
constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionNotice: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseDirectory: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        fileSystemOperations.sync {
            from(projectLicense) {
                into("licenses")
                rename { "Armin-MIT.txt" }
            }
            from(distributionNotice) {
                into("licenses")
                rename { "NOTICE" }
            }
            from(thirdPartyNotices) {
                into("licenses")
                rename { "THIRD_PARTY_NOTICES.md" }
            }
            from(licenseDirectory) { into("licenses") }
            into(outputDirectory)
        }
    }
}

@DisableCachingByDefault(because = "Verifies resolved dependency metadata without producing output")
abstract class VerifyRuntimeLicenseInventoryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectedInventory: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:Input abstract val resolvedCoordinates: ListProperty<String>

    @TaskAction
    fun verify() {
        val expected =
            expectedInventory.get().asFile.readLines().map(String::trim).filter {
                it.isNotEmpty() && !it.startsWith("#")
            }
        val normalizedExpected = expected.distinct().sorted()
        if (expected != normalizedExpected) {
            throw GradleException(
                "Runtime license inventory must be sorted and contain no duplicates"
            )
        }

        val resolved = resolvedCoordinates.get().distinct().sorted()
        if (expected != resolved) {
            val missing = resolved - expected.toSet()
            val stale = expected - resolved.toSet()
            throw GradleException(
                buildString {
                    appendLine("Runtime license inventory does not match debugRuntimeClasspath.")
                    if (missing.isNotEmpty()) appendLine("Missing: ${missing.joinToString()}")
                    if (stale.isNotEmpty()) appendLine("Stale: ${stale.joinToString()}")
                }
            )
        }

        val notices = thirdPartyNotices.get().asFile.readText()
        val undocumented = expected.filterNot(notices::contains)
        if (undocumented.isNotEmpty()) {
            throw GradleException(
                "THIRD_PARTY_NOTICES.md is missing runtime artifacts: ${undocumented.joinToString()}"
            )
        }
    }
}

@DisableCachingByDefault(because = "Verifies packaged files without producing output")
abstract class VerifyLegalAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionNotice: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val expectedFiles =
            linkedMapOf(
                "assets/licenses/Armin-MIT.txt" to projectLicense.get().asFile,
                "assets/licenses/NOTICE" to distributionNotice.get().asFile,
                "assets/licenses/THIRD_PARTY_NOTICES.md" to thirdPartyNotices.get().asFile,
            )
        licenseDirectory
            .get()
            .asFileTree
            .files
            .sortedBy { it.relativeTo(licenseDirectory.get().asFile).invariantSeparatorsPath }
            .forEach { file ->
                val relativePath =
                    file.relativeTo(licenseDirectory.get().asFile).invariantSeparatorsPath
                expectedFiles["assets/licenses/$relativePath"] = file
            }

        val apks =
            apkDirectory.get().asFileTree.matching { include("*.apk") }.files.sortedBy { it.name }
        if (apks.isEmpty()) {
            throw GradleException("No APK was produced in ${apkDirectory.get().asFile}")
        }

        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                expectedFiles.forEach { (entryName, sourceFile) ->
                    val entry =
                        zip.getEntry(entryName)
                            ?: throw GradleException("$entryName is missing from ${apk.name}")
                    val packagedBytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (!packagedBytes.contentEquals(sourceFile.readBytes())) {
                        throw GradleException("$entryName in ${apk.name} differs from $sourceFile")
                    }
                }
            }
        }
    }
}

plugins {
    id("com.android.application")
    id("com.diffplug.spotless")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "dev.armin"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.armin"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        // Toolchain upgrades are deliberate because Nix hashes and the offline cache change too.
        disable += "AndroidGradlePluginVersion"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.webkit:webkit:1.17.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

val projectLicenseFile = rootProject.layout.projectDirectory.file("LICENSE")
val distributionNoticeFile = rootProject.layout.projectDirectory.file("NOTICE")
val thirdPartyNoticesFile = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
val licenseFilesDirectory = rootProject.layout.projectDirectory.dir("LICENSES")
val runtimeLicenseInventoryFile =
    rootProject.layout.projectDirectory.file("legal/runtime-dependencies.txt")

val generateLegalAssets =
    tasks.register<GenerateLegalAssetsTask>("generateLegalAssets") {
        group = "build"
        description = "Generates the legal notices embedded in every APK."
        projectLicense.set(projectLicenseFile)
        distributionNotice.set(distributionNoticeFile)
        thirdPartyNotices.set(thirdPartyNoticesFile)
        licenseDirectory.set(licenseFilesDirectory)
        outputDirectory.set(layout.buildDirectory.dir("generated/legalAssets"))
    }

androidComponents {
    onVariants(selector().all()) { variant ->
        val assets = checkNotNull(variant.sources.assets) { "${variant.name} has no asset sources" }
        assets.addGeneratedSourceDirectory(generateLegalAssets) {
            it.outputDirectory
        }
    }

    onVariants(selector().withName("debug")) { variant ->
        val runtimeArtifacts = variant.runtimeConfiguration.incoming.artifactView {}.artifacts
        val coordinates =
            runtimeArtifacts.resolvedArtifacts.map { artifacts ->
                artifacts
                    .mapNotNull { artifact ->
                        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                        id?.let { "${it.group}:${it.module}:${it.version}" }
                    }
                    .distinct()
                    .sorted()
            }

        tasks.register<VerifyRuntimeLicenseInventoryTask>("verifyDebugRuntimeLicenseInventory") {
            group = "verification"
            description = "Checks the audited production dependency inventory."
            expectedInventory.set(runtimeLicenseInventoryFile)
            thirdPartyNotices.set(thirdPartyNoticesFile)
            resolvedCoordinates.set(coordinates)
        }

        tasks.register<VerifyLegalAssetsTask>("verifyDebugLegalAssets") {
            group = "verification"
            description = "Checks that the APK contains the canonical legal files."
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            projectLicense.set(projectLicenseFile)
            distributionNotice.set(distributionNoticeFile)
            thirdPartyNotices.set(thirdPartyNoticesFile)
            licenseDirectory.set(licenseFilesDirectory)
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktfmt("0.64").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    exclude("**/build/**")
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(true)
    }
}

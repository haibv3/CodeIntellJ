import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.haibachvan"
version = "0.1.5"

val javaVersion: String by project
val platformVersion: String by project
val pluginSinceBuild: String by project
val pluginUntilBuild: String by project

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(javaVersion.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion))
        allWarningsAsErrors.set(false)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
    }
}

sourceSets {
    create("uiTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += output + compileClasspath
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion)
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    // Gson is bundled by the IntelliJ Platform; keep compile-time access without packaging it.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Platform JUnit5 harness still references JUnit4 types (IJPL-159134).
    testRuntimeOnly("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    // uiTest source set (kotlin.stdlib.default.dependency=false requires explicit stdlib).
    "uiTestImplementation"(kotlin("stdlib"))
    "uiTestImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
    "uiTestImplementation"("org.opentest4j:opentest4j:1.3.0")
    "uiTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "uiTestRuntimeOnly"("junit:junit:4.13.2")
}

intellijPlatform {
    instrumentCode = false

    pluginConfiguration {
        id = "dev.haibachvan.codexintellij"
        name = "Codex"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    from("protocol-schema/codex-0.144.5") {
        into("protocol-schema/codex-0.144.5")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<ProcessResources>("processUiTestResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val uiTest by tasks.registering(Test::class) {
    description = "Runs uiTest source set smoke/Driver gates."
    group = "verification"
    testClassesDirs = sourceSets["uiTest"].output.classesDirs
    classpath = sourceSets["uiTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.named("check") {
    dependsOn(uiTest)
}

val schemaRoot = layout.projectDirectory.dir("protocol-schema/codex-0.144.5")
val trustStoreProperty = "codex.trust.store"
val codexBinaryProperty = "codexBinary"

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256File(path: Path): String = sha256Hex(Files.readAllBytes(path))

abstract class CodexSchemaGenerateTask : DefaultTask() {
    @get:Input
    abstract val experimental: Property<Boolean>

    @get:Input
    abstract val binaryPath: Property<String>

    @get:Input
    abstract val trustStorePath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val binary = Path.of(binaryPath.get())
        val store = Path.of(trustStorePath.get())
        require(Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) {
            "Codex binary must be a canonical regular file: $binary"
        }
        require(Files.isExecutable(binary)) { "Codex binary must be executable: $binary" }
        require(Files.isRegularFile(store)) {
            "Trust store missing. Confirm a binary before schema generation: $store"
        }

        val out = outputDir.get().asFile.toPath()
        Files.createDirectories(out)
        val command = buildList {
            add(binary.toAbsolutePath().toString())
            add("app-server")
            add("generate-json-schema")
            if (experimental.get()) {
                add("--experimental")
            }
            add("--out")
            add(out.toAbsolutePath().toString())
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        check(finished) { "Schema generation timed out. Output:\n$output" }
        check(process.exitValue() == 0) {
            "Schema generation failed (exit=${process.exitValue()}):\n$output"
        }
    }
}

tasks.register<CodexSchemaGenerateTask>("generateCodexStableSchema") {
    group = "codex"
    description = "Generate stable app-server JSON schema tree using a confirmed Codex binary."
    experimental.set(false)
    binaryPath.set(providers.gradleProperty(codexBinaryProperty).orElse(""))
    trustStorePath.set(
        providers.gradleProperty(trustStoreProperty)
            .orElse(layout.buildDirectory.file("codex-trust/confirmed-binary.json").map { it.asFile.absolutePath })
    )
    outputDir.set(layout.buildDirectory.dir("generated-schema/stable"))
    onlyIf {
        val binary = binaryPath.orNull
        !binary.isNullOrBlank()
    }
}

tasks.register<CodexSchemaGenerateTask>("generateCodexExperimentalSchema") {
    group = "codex"
    description = "Generate experimental app-server JSON schema tree using a confirmed Codex binary."
    experimental.set(true)
    binaryPath.set(providers.gradleProperty(codexBinaryProperty).orElse(""))
    trustStorePath.set(
        providers.gradleProperty(trustStoreProperty)
            .orElse(layout.buildDirectory.file("codex-trust/confirmed-binary.json").map { it.asFile.absolutePath })
    )
    outputDir.set(layout.buildDirectory.dir("generated-schema/experimental"))
    onlyIf {
        val binary = binaryPath.orNull
        !binary.isNullOrBlank()
    }
}

tasks.register("verifyCodexSchemaManifest") {
    group = "codex"
    description = "Verify committed schema inventory, per-file hashes, and root hashes."
    doEachVerifyManifest(schemaRoot.asFile.toPath())
}

tasks.register("verifyProtocolContract") {
    group = "codex"
    description = "Verify method-schema-map resolves against committed schema trees."
    dependsOn("verifyCodexSchemaManifest")
    doLast {
        val root = schemaRoot.asFile.toPath()
        val mapPath = root.resolve("method-schema-map.json")
        val mapper = groovy.json.JsonSlurper()
        @Suppress("UNCHECKED_CAST")
        val map = mapper.parse(mapPath.toFile()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val methods = map["methods"] as List<Map<String, Any?>>
        check(methods.isNotEmpty()) { "method-schema-map.json contains no methods" }
        methods.forEach { entry ->
            val method = entry["method"] as String
            listOf("requestSchema", "responseSchema", "notificationSchema").forEach { key ->
                val rel = entry[key] as String?
                if (rel != null) {
                    val file = root.resolve(rel)
                    check(Files.isRegularFile(file)) {
                        "Mapped $key for $method missing: $rel"
                    }
                }
            }
            val apiClass = entry["apiClass"] as String
            val tree = entry["tree"] as String
            check(apiClass == "stable" || apiClass == "experimental") {
                "Method $method has invalid apiClass=$apiClass"
            }
            check(tree == apiClass) {
                "Method $method tree=$tree does not match apiClass=$apiClass"
            }
        }
    }
}

tasks.register("cleanCheckoutGate") {
    group = "codex"
    description = "Prove Phase 1 schema gates run from a temporary checkout using only committed wrapper artifacts."
    doLast {
        val wrapperJar = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar").asFile.toPath()
        // Platform Plugin 2.18.1 requires Gradle 9.0+; Phase 1's original 8.13 pin is incompatible.
        val expectedJarSha = "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"
        val actualJarSha = sha256File(wrapperJar)
        check(actualJarSha == expectedJarSha) {
            "gradle-wrapper.jar SHA-256 mismatch: expected=$expectedJarSha actual=$actualJarSha"
        }

        val props = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties").asFile.readText()
        check(props.contains("distributionSha256Sum=8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b")) {
            "gradle-wrapper.properties missing pinned distributionSha256Sum"
        }
        check(props.contains("gradle-9.0.0-bin.zip")) {
            "gradle/wrapper.properties must pin Gradle 9.0.0"
        }
        check(Files.isExecutable(layout.projectDirectory.file("gradlew").asFile.toPath())) {
            "gradlew must be executable"
        }

        val temp = Files.createTempDirectory("codex-intellij-clean-checkout-")
        try {
            val projectDir = layout.projectDirectory.asFile
            // Copy committed sources only (no build caches) using tar to preserve modes.
            val copy = ProcessBuilder(
                "bash",
                "-lc",
                """
                set -euo pipefail
                tar -C '${projectDir.absolutePath}' \
                  --exclude='./.git' \
                  --exclude='./.gradle' \
                  --exclude='./build' \
                  --exclude='./out' \
                  -cf - . | tar -C '${temp.toAbsolutePath()}' -xf -
                """.trimIndent(),
            ).redirectErrorStream(true).start()
            val copyOut = copy.inputStream.bufferedReader().readText()
            check(copy.waitFor() == 0) { "Failed to copy checkout into temp directory:\n$copyOut" }

            val gate = ProcessBuilder(
                "./gradlew",
                "--no-daemon",
                "verifyCodexSchemaManifest",
                "verifyProtocolContract",
            ).directory(temp.toFile())
                .redirectErrorStream(true)
                .start()
            val output = gate.inputStream.bufferedReader().readText()
            val finished = gate.waitFor(45, TimeUnit.MINUTES)
            check(finished) { "cleanCheckoutGate timed out:\n$output" }
            check(gate.exitValue() == 0) {
                "cleanCheckoutGate failed (exit=${gate.exitValue()}):\n$output"
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}

fun Task.doEachVerifyManifest(root: Path) {
    doLast {
        val inventoryPath = root.resolve("schema-inventory.txt")
        val manifestPath = root.resolve("schema-manifest.json")
        check(Files.isRegularFile(inventoryPath)) { "Missing schema-inventory.txt" }
        check(Files.isRegularFile(manifestPath)) { "Missing schema-manifest.json" }

        val inventory = Files.readAllLines(inventoryPath).filter { it.isNotBlank() }
        check(inventory == inventory.sorted()) { "schema-inventory.txt must be sorted" }
        check(inventory.size == inventory.toSet().size) { "schema-inventory.txt has duplicates" }

        val slurper = groovy.json.JsonSlurper()
        @Suppress("UNCHECKED_CAST")
        val manifest = slurper.parse(manifestPath.toFile()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val files = manifest["files"] as Map<String, String>
        check(files.keys.toList().sorted() == inventory) {
            "Manifest file set does not match schema-inventory.txt"
        }

        val hStable = MessageDigest.getInstance("SHA-256")
        val hExp = MessageDigest.getInstance("SHA-256")
        inventory.forEach { rel ->
            val path = root.resolve(rel)
            check(Files.isRegularFile(path)) { "Inventory entry missing on disk: $rel" }
            val digest = sha256File(path)
            check(files[rel] == digest) {
                "Hash mismatch for $rel expected=${files[rel]} actual=$digest"
            }
            val line = "$digest  $rel\n".toByteArray()
            if (rel.startsWith("stable/")) {
                hStable.update(line)
            } else if (rel.startsWith("experimental/")) {
                hExp.update(line)
            } else {
                error("Inventory entry must start with stable/ or experimental/: $rel")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val roots = manifest["roots"] as Map<String, Map<String, Any?>>
        val stableRoot = roots.getValue("stable")["sha256"] as String
        val experimentalRoot = roots.getValue("experimental")["sha256"] as String
        val actualStable = hStable.digest().joinToString("") { "%02x".format(it) }
        val actualExperimental = hExp.digest().joinToString("") { "%02x".format(it) }
        check(stableRoot == actualStable) {
            "Stable root hash mismatch expected=$stableRoot actual=$actualStable"
        }
        check(experimentalRoot == actualExperimental) {
            "Experimental root hash mismatch expected=$experimentalRoot actual=$actualExperimental"
        }
    }
}

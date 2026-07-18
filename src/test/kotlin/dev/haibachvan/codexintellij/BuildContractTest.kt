package dev.haibachvan.codexintellij

import dev.haibachvan.codexintellij.appserver.ProtocolContractValidator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BuildContractTest {
    private val root: Path = Path.of(".").toAbsolutePath().normalize()

    @Test
    fun `wrapper artifacts are committed with pinned checksums`() {
        val gradlew = root.resolve("gradlew")
        val gradlewBat = root.resolve("gradlew.bat")
        val jar = root.resolve("gradle/wrapper/gradle-wrapper.jar")
        val props = root.resolve("gradle/wrapper/gradle-wrapper.properties")

        assertTrue(Files.isExecutable(gradlew), "gradlew must be executable")
        assertTrue(gradlewBat.isRegularFile(), "gradlew.bat must exist")
        assertTrue(jar.isRegularFile(), "gradle-wrapper.jar must exist")
        assertTrue(props.isRegularFile(), "gradle-wrapper.properties must exist")

        val jarSha = sha256(jar.readBytes())
        // Platform Gradle Plugin 2.18.1 requires Gradle >= 9.0.0 (Phase 1 originally pinned 8.13).
        assertEquals(
            "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3",
            jarSha,
            "gradle-wrapper.jar must match the official Gradle 9.0.0 wrapper JAR digest",
        )

        val text = props.readText()
        assertTrue(text.contains("gradle-9.0.0-bin.zip"), text)
        assertTrue(
            text.contains("distributionSha256Sum=8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b"),
            text,
        )
    }

    @Test
    fun `toolchain pins match Phase 1 contract`() {
        val build = root.resolve("build.gradle.kts").readText()
        val props = root.resolve("gradle.properties").readText()
        val pluginXml = root.resolve("src/main/resources/META-INF/plugin.xml").readText()

        assertTrue(build.contains("org.jetbrains.kotlin.jvm\") version \"2.3.20\""), build)
        assertTrue(build.contains("org.jetbrains.intellij.platform\") version \"2.18.1\""), build)
        assertTrue(build.contains("intellijIdea(platformVersion)"), build)
        assertTrue(props.contains("platformVersion=2026.1.4"), props)
        assertTrue(props.contains("pluginSinceBuild=261"), props)
        assertTrue(props.contains("pluginUntilBuild=262.*"), props)
        assertTrue(props.contains("kotlin.stdlib.default.dependency=false"), props)
        assertTrue(pluginXml.contains("<id>dev.haibachvan.codexintellij</id>"), pluginXml)
    }

    @Test
    fun `full schema trees inventory and root hashes validate`() {
        val schemaRoot = root.resolve("protocol-schema/codex-0.144.5")
        assertTrue(schemaRoot.resolve("stable").exists())
        assertTrue(schemaRoot.resolve("experimental").exists())
        val validation = ProtocolContractValidator(schemaRoot).validateTrees()
        assertEquals(604, validation.inventoryCount)
        assertEquals(64, validation.stableRootSha256.length)
        assertEquals(64, validation.experimentalRootSha256.length)
    }

    @Test
    fun `compatibility snapshot fields never include absolute paths or secrets`() {
        // Static contract on the data class shape / fixture values used by UI.
        val snapshot = CodexProjectService.CompatibilitySnapshot(
            expectedBuild = "261.26222.65",
            detectedBuild = "IC-261.26222.65",
            binaryVersion = "codex-cli 0.144.5",
            binaryHashPrefix = "abcdefghijkl",
            reviewState = "Confirmed",
            stableSchemaRootHashPrefix = "1234567890ab",
            experimentalSchemaRootHashPrefix = "fedcba098765",
            processEpoch = "epoch-1",
            lifecycleState = "Ready",
            userAgent = "codex_app_server/0.144.5",
        )
        val rendered = listOf(
            snapshot.expectedBuild,
            snapshot.detectedBuild,
            snapshot.binaryVersion,
            snapshot.binaryHashPrefix,
            snapshot.reviewState,
            snapshot.stableSchemaRootHashPrefix,
            snapshot.experimentalSchemaRootHashPrefix,
            snapshot.processEpoch,
            snapshot.lifecycleState,
            snapshot.userAgent,
        ).joinToString()
        assertFalse(rendered.contains("/home/"))
        assertFalse(rendered.contains("TOKEN"))
        assertFalse(rendered.contains("SECRET"))
        assertEquals(12, snapshot.binaryHashPrefix!!.length)
    }

    @Test
    fun `manifest cannot confer runtime trust by itself`() {
        val schemaRoot = root.resolve("protocol-schema/codex-0.144.5")
        val manifest = schemaRoot.resolve("schema-manifest.json").readText()
        assertTrue(manifest.contains("reviewed evidence") || manifest.contains("not a trust"), manifest)
        assertThrows<Exception> {
            // Opening a non-existent binary must fail independently of the manifest.
            dev.haibachvan.codexintellij.appserver.CodexBinaryTrustPolicy(
                storePath = Files.createTempFile("trust", ".store"),
            ).inspect(Path.of("/definitely/not/a/codex-binary"))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

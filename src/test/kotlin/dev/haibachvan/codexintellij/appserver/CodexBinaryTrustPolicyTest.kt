package dev.haibachvan.codexintellij.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class CodexBinaryTrustPolicyTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inspect rejects symlink directory and non-executable`() {
        val store = tempDir.resolve("trust.store")
        val policy = CodexBinaryTrustPolicy(store, versionRunner = { "codex-cli 0.144.5" })

        val target = createFakeBinary("real-codex", "#!/bin/sh\necho codex-cli 0.144.5\n")
        val link = tempDir.resolve("codex-link")
        Files.createSymbolicLink(link, target.fileName)

        assertThrows<IllegalArgumentException> { policy.inspect(link) }
        assertThrows<IllegalArgumentException> { policy.inspect(tempDir) }

        val nonExec = tempDir.resolve("not-exec")
        Files.writeString(nonExec, "#!/bin/sh\n")
        assertThrows<IllegalArgumentException> { policy.inspect(nonExec) }
    }

    @Test
    fun `confirm and revalidate accept unchanged identity and reject mutations`() {
        val store = tempDir.resolve("trust.store")
        val binary = createFakeBinary("codex", "#!/bin/sh\necho codex-cli 0.144.5\n")
        val policy = CodexBinaryTrustPolicy(
            storePath = store,
            versionRunner = { path ->
                ProcessBuilder(path.toString(), "--version")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().readText().trim()
            },
        )

        val identity = policy.inspect(binary)
        policy.confirm(identity)
        val trusted = policy.revalidate()
        assertTrue(trusted is CodexBinaryTrustPolicy.TrustDecision.Trusted)

        Files.writeString(binary, "#!/bin/sh\necho codex-cli 0.144.5-hacked\n")
        Files.setPosixFilePermissions(
            binary,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val decision = policy.revalidate()
        assertTrue(
            decision is CodexBinaryTrustPolicy.TrustDecision.NeedsConfirmation ||
                decision is CodexBinaryTrustPolicy.TrustDecision.Rejected,
            "changed binary must not remain trusted: $decision",
        )
    }

    @Test
    fun `environment allowlist excludes token and proxy keys unless opted in and never logs values`() {
        val store = tempDir.resolve("trust.store")
        val env = mapOf(
            "HOME" to "/home/fixture",
            "PATH" to "/usr/bin",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
            "TMPDIR" to "/tmp",
            "CODEX_HOME" to "/tmp/codex-home",
            "CODEX_FIXTURE_SCENARIO" to "multi-agent-performance",
            "HTTP_PROXY" to "http://127.0.0.1:8080",
            "OPENAI_API_KEY" to "sk-secret",
            "CUSTOM_FLAG" to "preview-me",
        )
        val policy = CodexBinaryTrustPolicy(
            storePath = store,
            versionRunner = { "codex-cli 0.144.5" },
            environmentReader = { env },
        )

        val preview = policy.environment()
        assertEquals("/home/fixture", preview.inherited["HOME"])
        assertTrue(preview.inherited["PATH"]!!.split(':').contains("/usr/bin"))
        assertEquals("en_US.UTF-8", preview.inherited["LC_ALL"])
        assertTrue(!preview.inherited.containsKey("CODEX_FIXTURE_SCENARIO"))
        assertTrue(!preview.inherited.containsKey("HTTP_PROXY"))
        assertTrue(!preview.inherited.containsKey("OPENAI_API_KEY"))
        assertTrue(preview.optedIn.isEmpty())

        assertThrows<IllegalArgumentException> {
            policy.environment(extraKeys = setOf("OPENAI_API_KEY"))
        }
        assertThrows<IllegalArgumentException> {
            policy.environment(extraKeys = setOf("HTTP_PROXY"))
        }

        val opted = policy.environment(extraKeys = setOf("CUSTOM_FLAG"))
        assertEquals(listOf("CUSTOM_FLAG"), opted.previewKeys)
        assertEquals("preview-me", opted.optedIn["CUSTOM_FLAG"])
        // Preview keys expose names only in logs; values stay out of previewKeys.
        assertTrue(opted.previewKeys.none { it.contains("preview-me") })
    }

    @Test
    fun `version mismatch requires confirmation`() {
        val store = tempDir.resolve("trust.store")
        val binary = createFakeBinary("codex", "#!/bin/sh\necho codex-cli 0.144.5\n")
        var version = "codex-cli 0.144.5"
        val policy = CodexBinaryTrustPolicy(
            storePath = store,
            versionRunner = { version },
        )
        val identity = policy.inspect(binary)
        policy.confirm(identity)
        version = "codex-cli 0.200.0"
        val decision = policy.revalidate()
        assertTrue(decision is CodexBinaryTrustPolicy.TrustDecision.NeedsConfirmation)
    }

    @Test
    fun `ensureTrustedForLaunch auto links from resolver without user confirm`() {
        val store = tempDir.resolve("trust.store")
        val binary = createFakeBinary("codex", "#!/bin/sh\necho codex-cli 0.144.5\n")
        val link = tempDir.resolve("codex-on-path")
        Files.createSymbolicLink(link, binary.fileName)
        val policy = CodexBinaryTrustPolicy(
            storePath = store,
            versionRunner = { "codex-cli 0.144.5" },
        )
        val linked = policy.ensureTrustedForLaunch { link }
        assertEquals(binary.toAbsolutePath().normalize().toString(), linked.canonicalPath)
        assertTrue(policy.revalidate() is CodexBinaryTrustPolicy.TrustDecision.Trusted)
    }

    @Test
    fun `ensureTrustedForLaunch relinks when stored identity changes`() {
        val store = tempDir.resolve("trust.store")
        val binary = createFakeBinary("codex", "#!/bin/sh\necho codex-cli 0.144.5\n")
        var version = "codex-cli 0.144.5"
        val policy = CodexBinaryTrustPolicy(
            storePath = store,
            versionRunner = { version },
        )
        policy.confirm(policy.inspect(binary))
        version = "codex-cli 0.200.0"
        val relinked = policy.ensureTrustedForLaunch { binary }
        assertEquals("codex-cli 0.200.0", relinked.versionText)
        assertTrue(policy.revalidate() is CodexBinaryTrustPolicy.TrustDecision.Trusted)
    }

    @Test
    fun `environment PATH is enriched with common user bin dirs`() {
        val store = tempDir.resolve("trust.store")
        val homeBin = Path.of(System.getProperty("user.home"), ".npm-global", "bin").toString()
        val policy = CodexBinaryTrustPolicy(
            storePath = store,
            versionRunner = { "codex-cli 0.144.5" },
            environmentReader = {
                mapOf(
                    "HOME" to System.getProperty("user.home"),
                    "PATH" to "/usr/bin",
                )
            },
        )
        val preview = policy.environment()
        assertTrue(preview.inherited["PATH"]!!.contains(homeBin) || preview.inherited["PATH"]!!.contains("/usr/bin"))
        assertTrue(preview.inherited["PATH"]!!.split(':').contains("/usr/bin"))
    }

    private fun createFakeBinary(name: String, contents: String): Path {
        val path = tempDir.resolve(name)
        path.writeText(contents)
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
        return path
    }
}

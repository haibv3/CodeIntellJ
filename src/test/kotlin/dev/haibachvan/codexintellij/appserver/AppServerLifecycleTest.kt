package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.session.CapabilityRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class AppServerLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `start handshake restart uses new epoch and rejects stale server responses`() {
        val binary = installFakeBinary()
        val store = tempDir.resolve("trust.store")
        val trust = CodexBinaryTrustPolicy(store)
        val identity = trust.inspect(binary)
        trust.confirm(identity)

        val schemaRoot = Path.of("protocol-schema/codex-0.144.5").toAbsolutePath().normalize()
        val adapter = ProtocolAdapter(schemaRoot)
        val sequencer = ProtocolSequencer()
        val transport = JsonRpcTransport(adapter, sequencer)
        val capabilities = CapabilityRegistry(adapter)
        val lifecycle = AppServerLifecycleActor(
            trustPolicy = trust,
            adapter = adapter,
            transport = transport,
            sequencer = sequencer,
            processFactory = AppServerLifecycleActor.DefaultProcessFactory(),
        )
        val gateway = AppServerGateway(lifecycle, transport, sequencer, capabilities)

        val epoch1 = gateway.start()
        assertEquals(AppServerLifecycleActor.State.Ready, lifecycle.state())
        assertEquals(ProcessEpoch(1), epoch1)
        assertTrue(capabilities.snapshot().userAgent!!.contains("0.144.5"))

        val epoch2 = gateway.restart()
        assertEquals(ProcessEpoch(2), epoch2)
        assertNotEquals(epoch1, epoch2)

        // Old-epoch server request must not be answerable in the new process.
        assertThrows<IllegalArgumentException> {
            transport.respond(
                ServerRequestKey(epoch1, "old-id"),
                fingerprint = "deadbeef",
                body = JsonObject(),
            )
        }
        gateway.dispose()
        assertEquals(AppServerLifecycleActor.State.Disposed, lifecycle.state())
    }

    @Test
    fun `binary change after confirmation is auto relinked on launch`() {
        val binary = installFakeBinary()
        val store = tempDir.resolve("trust.store")
        val trust = CodexBinaryTrustPolicy(store)
        val original = trust.inspect(binary)
        trust.confirm(original)

        // Mutate bytes so stored identity no longer matches.
        Files.writeString(binary, Files.readString(binary) + "\n# mutated\n")
        Files.setPosixFilePermissions(binary, execPerms())

        val relinked = trust.ensureTrustedForLaunch { binary }
        assertNotEquals(original.sha256, relinked.sha256)

        val schemaRoot = Path.of("protocol-schema/codex-0.144.5").toAbsolutePath().normalize()
        val adapter = ProtocolAdapter(schemaRoot)
        val sequencer = ProtocolSequencer()
        val transport = JsonRpcTransport(adapter, sequencer)
        val lifecycle = AppServerLifecycleActor(
            trustPolicy = trust,
            adapter = adapter,
            transport = transport,
            sequencer = sequencer,
        )
        lifecycle.start()
        assertEquals(AppServerLifecycleActor.State.Ready, lifecycle.state())
        lifecycle.close()
    }

    @Test
    fun `experimental method unavailable without opt-in produces no wire traffic`() {
        val binary = installFakeBinary()
        val store = tempDir.resolve("trust.store")
        val trust = CodexBinaryTrustPolicy(store)
        trust.confirm(trust.inspect(binary))

        val schemaRoot = Path.of("protocol-schema/codex-0.144.5").toAbsolutePath().normalize()
        val adapter = ProtocolAdapter(schemaRoot)
        val sequencer = ProtocolSequencer()
        val transport = JsonRpcTransport(adapter, sequencer)
        val capabilities = CapabilityRegistry(adapter)
        val lifecycle = AppServerLifecycleActor(
            trustPolicy = trust,
            adapter = adapter,
            transport = transport,
            sequencer = sequencer,
        )
        val gateway = AppServerGateway(lifecycle, transport, sequencer, capabilities)
        gateway.start()

        val experimental = adapter.knownMethods().first { adapter.apiClass(it) == "experimental" }
        val future = gateway.request(experimental, JsonObject())
        assertTrue(future.isCompletedExceptionally)
        assertEquals(0, transport.pendingCount())
        gateway.dispose()
    }

    private fun installFakeBinary(): Path {
        val source = Path.of("src/test/resources/fixtures/appserver/fake-codex-app-server.py")
            .toAbsolutePath().normalize()
        val binary = tempDir.resolve("codex")
        // Wrapper script so `codex app-server` and `codex --version` both work.
        binary.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            if [[ "${'$'}{1:-}" == "--version" ]]; then
              exec python3 "$source" --version
            fi
            exec python3 "$source" "${'$'}@"
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(binary, execPerms())
        return binary
    }

    private fun execPerms(): Set<PosixFilePermission> =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
}

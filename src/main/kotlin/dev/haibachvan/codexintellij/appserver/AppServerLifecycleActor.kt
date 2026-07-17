package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sole owner of process launch/stop/restart and [ProcessEpoch] increments.
 */
class AppServerLifecycleActor(
    private val trustPolicy: CodexBinaryTrustPolicy,
    private val adapter: ProtocolAdapter,
    private val transport: JsonRpcTransport,
    private val sequencer: ProtocolSequencer,
    private val framer: JsonlFramer = JsonlFramer(),
    private val redaction: RedactionPolicy = RedactionPolicy(),
    private val diagnostics: DiagnosticRingBuffer = DiagnosticRingBuffer(),
    private val processFactory: ProcessFactory = DefaultProcessFactory(),
    private val experimentalApi: Boolean = false,
    private val workingDir: Path? = null,
) : AutoCloseable {
    enum class State {
        Stopped,
        Starting,
        Ready,
        Stopping,
        Disposed,
    }

    fun interface ProcessFactory {
        fun start(command: List<String>, environment: Map<String, String>, workingDir: Path?): Process
    }

    class DefaultProcessFactory : ProcessFactory {
        override fun start(command: List<String>, environment: Map<String, String>, workingDir: Path?): Process {
            val builder = ProcessBuilder(command)
            builder.environment().clear()
            builder.environment().putAll(environment)
            if (workingDir != null) {
                builder.directory(workingDir.toFile())
            }
            builder.redirectErrorStream(false)
            return builder.start()
        }
    }

    private val state = AtomicReference(State.Stopped)
    private val epochRef = AtomicReference(ProcessEpoch(0))
    private val processRef = AtomicReference<Process?>(null)
    private val drainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "codex-appserver-stdout-drain").apply { isDaemon = true }
    }
    private val stderrExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "codex-appserver-stderr-drain").apply { isDaemon = true }
    }

    fun state(): State = state.get()
    fun epoch(): ProcessEpoch = epochRef.get()
    fun diagnosticBuffer(): DiagnosticRingBuffer = diagnostics

    data class StartResult(
        val epoch: ProcessEpoch,
        val initializeResult: InitializeResult,
    )

    @Synchronized
    fun start(extraEnvKeys: Set<String> = emptySet()): StartResult {
        check(state.get() != State.Disposed) { "Lifecycle disposed" }
        check(state.get() == State.Stopped) { "Cannot start from state ${state.get()}" }
        state.set(State.Starting)

        val identity = try {
            trustPolicy.ensureTrustedForLaunch()
        } catch (ex: Exception) {
            state.set(State.Stopped)
            throw IllegalStateException("Codex binary auto-link failed: ${ex.message}", ex)
        }

        // Exec-time revalidation immediately before process creation.
        val rechecked = trustPolicy.inspect(Path.of(identity.canonicalPath))
        require(rechecked == identity) { "Binary identity changed immediately before exec" }

        val envPreview = trustPolicy.environment(extraEnvKeys)
        val env = LinkedHashMap<String, String>()
        env.putAll(envPreview.inherited)
        env.putAll(envPreview.optedIn)

        val nextEpoch = epochRef.get().next()
        epochRef.set(nextEpoch)
        framer.reset(nextEpoch)

        val process = try {
            processFactory.start(
                command = listOf(identity.canonicalPath, "app-server"),
                environment = env,
                workingDir = workingDir,
            )
        } catch (ex: Exception) {
            state.set(State.Stopped)
            emitDiagnostic(nextEpoch, "launch_failed", StructuredDiagnosticEvent.Severity.ERROR, ex.message ?: "launch failed")
            throw ex
        }
        processRef.set(process)

        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        transport.bind(nextEpoch, writer)
        startStdoutDrain(nextEpoch, process)
        startStderrDrain(nextEpoch, process)

        try {
            val init = handshake(nextEpoch)
            state.set(State.Ready)
            emitDiagnostic(nextEpoch, "ready", StructuredDiagnosticEvent.Severity.INFO, "App server ready")
            return StartResult(epoch = nextEpoch, initializeResult = init)
        } catch (ex: Exception) {
            stopInternal(nextEpoch, "handshake_failed: ${ex.message}")
            throw ex
        }
    }

    @Synchronized
    fun stop() {
        val current = state.get()
        if (current == State.Stopped || current == State.Disposed) {
            return
        }
        stopInternal(epochRef.get(), "stop requested")
    }

    @Synchronized
    fun restart(extraEnvKeys: Set<String> = emptySet()): StartResult {
        stop()
        return start(extraEnvKeys)
    }

    @Synchronized
    override fun close() {
        if (state.get() == State.Disposed) {
            return
        }
        stop()
        state.set(State.Disposed)
        drainExecutor.shutdownNow()
        stderrExecutor.shutdownNow()
    }

    private fun handshake(epoch: ProcessEpoch): InitializeResult {
        val params = InitializeParams(
            clientName = "codex-intellij",
            clientTitle = "Codex IntelliJ",
            clientVersion = "0.1.0",
            experimentalApi = experimentalApi,
        )
        val response = transport.request("initialize", params.toJson(), timeoutMs = 20_000L)
            .get(20, TimeUnit.SECONDS)
        if (response.error != null) {
            error("initialize failed: ${response.error}")
        }
        val resultObj = response.result ?: error("initialize returned empty result")
        val parsed = adapter.parseInitializeResult(resultObj)
        transport.notify("initialized", JsonObject())
        return parsed
    }

    private fun startStdoutDrain(epoch: ProcessEpoch, process: Process) {
        drainExecutor.execute {
            try {
                val reader = process.inputStream
                val buffer = ByteArray(8 * 1024)
                while (epochRef.get() == epoch && !Thread.currentThread().isInterrupted) {
                    val read = reader.read(buffer)
                    if (read < 0) {
                        val finished = framer.finish(epoch)
                        finished.diagnostics.forEach {
                            emitDiagnostic(epoch, it.code, StructuredDiagnosticEvent.Severity.WARN, it.message)
                        }
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    val chunk = buffer.copyOf(read)
                    val accepted = framer.accept(epoch, chunk)
                    accepted.diagnostics.forEach {
                        emitDiagnostic(epoch, it.code, StructuredDiagnosticEvent.Severity.WARN, it.message)
                    }
                    val watermark = sequencer.currentRequestWatermark()
                    for (frame in accepted.frames) {
                        val envelope = adapter.decodeLine(frame.line)
                        transport.onIncoming(epoch, envelope, watermark)
                    }
                }
            } catch (_: Exception) {
                // Process teardown races are expected during stop/restart.
            }
        }
    }

    private fun startStderrDrain(epoch: ProcessEpoch, process: Process) {
        stderrExecutor.execute {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null && epochRef.get() == epoch) {
                        emitDiagnostic(
                            epoch,
                            "stderr",
                            StructuredDiagnosticEvent.Severity.WARN,
                            line,
                        )
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {
                // ignored on teardown
            }
        }
    }

    private fun stopInternal(epoch: ProcessEpoch, reason: String) {
        state.set(State.Stopping)
        transport.unbind(epoch, reason)
        sequencer.clearEpoch(epoch)
        val process = processRef.getAndSet(null)
        if (process != null) {
            process.destroy()
            val exited = process.waitFor(5, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
        state.set(State.Stopped)
        emitDiagnostic(epoch, "stopped", StructuredDiagnosticEvent.Severity.INFO, reason)
    }

    private fun emitDiagnostic(
        epoch: ProcessEpoch,
        code: String,
        severity: StructuredDiagnosticEvent.Severity,
        message: String,
    ) {
        val event = redaction.redact(
            StructuredDiagnosticEvent(
                epoch = epoch,
                code = code,
                severity = severity,
                message = message,
            ),
        )
        diagnostics.append(event)
        sequencer.enqueue(
            epoch = epoch,
            kind = SequencedEventKind.DIAGNOSTIC,
            payload = WireEnvelope.Unknown(
                raw = JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", event.message)
                },
                reason = "diagnostic",
            ),
        )
    }
}

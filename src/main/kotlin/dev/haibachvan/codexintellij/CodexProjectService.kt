package dev.haibachvan.codexintellij

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import dev.haibachvan.codexintellij.account.AccountController
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.AppServerLifecycleActor
import dev.haibachvan.codexintellij.appserver.BundledSchemaRoot
import dev.haibachvan.codexintellij.appserver.CodexBinaryLocator
import dev.haibachvan.codexintellij.appserver.CodexBinaryTrustPolicy
import dev.haibachvan.codexintellij.appserver.DiagnosticRingBuffer
import dev.haibachvan.codexintellij.appserver.JsonRpcTransport
import dev.haibachvan.codexintellij.appserver.JsonlFramer
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.ProtocolAdapter
import dev.haibachvan.codexintellij.appserver.ProtocolContractValidator
import dev.haibachvan.codexintellij.appserver.ProtocolSequencer
import dev.haibachvan.codexintellij.appserver.RedactionPolicy
import dev.haibachvan.codexintellij.platform.ContextAttachmentStore
import dev.haibachvan.codexintellij.session.ApprovalStateMachine
import dev.haibachvan.codexintellij.session.CapabilityRegistry
import dev.haibachvan.codexintellij.session.ConversationController
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.settings.ModelCatalog
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
class CodexProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    data class CompatibilitySnapshot(
        val expectedBuild: String,
        val detectedBuild: String,
        val binaryVersion: String?,
        val binaryHashPrefix: String?,
        val reviewState: String,
        val stableSchemaRootHashPrefix: String?,
        val experimentalSchemaRootHashPrefix: String?,
        val processEpoch: String?,
        val lifecycleState: String?,
        val userAgent: String?,
    )

    private val trustPolicy: CodexBinaryTrustPolicy by lazy {
        CodexBinaryTrustPolicy(trustStorePath())
    }

    private val sessionLock = Any()

    private val contextAttachments = ContextAttachmentStore()

    private val approvalStateMachine = ApprovalStateMachine()

    private val modelCatalog = ModelCatalog { synchronized(sessionLock) { gateway } }

    @Volatile
    private var gateway: AppServerGateway? = null

    /** Eager store so Tool Window panels can subscribe before Connect. */
    private val stateStore = ServerStateStore()

    @Volatile
    private var conversationController: ConversationController? = null

    @Volatile
    private var accountController: AccountController? = null

    fun compatibilitySnapshot(): CompatibilitySnapshot {
        val expectedBuild = "261.26222.65"
        val detectedBuild = ApplicationInfo.getInstance().build.asString()
        val confirmed = trustPolicy.loadConfirmed()
        val decision = trustPolicy.revalidate(confirmed)
        val reviewState = when (decision) {
            is CodexBinaryTrustPolicy.TrustDecision.Trusted -> "Auto-linked"
            is CodexBinaryTrustPolicy.TrustDecision.NeedsConfirmation ->
                if (confirmed == null) "Will auto-link on Connect" else "Will re-link on Connect"
            is CodexBinaryTrustPolicy.TrustDecision.Rejected -> "Rejected — will rediscover on Connect"
        }

        val roots = readSchemaRootPrefixes()
        val status = synchronized(sessionLock) { gateway?.status() }
        return CompatibilitySnapshot(
            expectedBuild = expectedBuild,
            detectedBuild = detectedBuild,
            binaryVersion = confirmed?.versionText,
            binaryHashPrefix = confirmed?.sha256?.take(12),
            reviewState = reviewState,
            stableSchemaRootHashPrefix = roots.first,
            experimentalSchemaRootHashPrefix = roots.second,
            processEpoch = status?.epoch?.toString(),
            lifecycleState = status?.state?.name,
            userAgent = status?.userAgent,
        )
    }

    /**
     * Cheap lifecycle probe for action updates / status labels.
     * Never runs binary discovery or `codex --version` (those block for hundreds of ms).
     */
    fun lifecycleStateName(): String? =
        synchronized(sessionLock) { gateway?.status()?.state?.name }

    /** Confirmed binary version from disk store only — no process spawn. */
    fun confirmedBinaryVersion(): String? =
        trustPolicy.loadConfirmed()?.versionText

    fun trustPolicy(): CodexBinaryTrustPolicy = trustPolicy

    fun gateway(): AppServerGateway? = synchronized(sessionLock) { gateway }

    fun serverStateStore(): ServerStateStore = stateStore

    fun conversationController(): ConversationController? = synchronized(sessionLock) { conversationController }

    fun accountController(): AccountController? = synchronized(sessionLock) { accountController }

    fun contextAttachmentStore(): ContextAttachmentStore = contextAttachments

    fun approvalStateMachine(): ApprovalStateMachine = approvalStateMachine

    fun modelCatalog(): ModelCatalog = modelCatalog

    /** Absolute project working directory used as Codex cwd. */
    fun projectCwd(): Path? {
        val base = project.basePath ?: return null
        return Path.of(FileUtil.toSystemIndependentName(base)).toAbsolutePath().normalize()
    }

    fun schemaRoot(): Path {
        val fromProperty = System.getProperty("codex.schema.root")
        if (!fromProperty.isNullOrBlank()) {
            return Path.of(fromProperty)
        }
        BundledSchemaRoot.projectSchemaIfPresent(project.basePath)?.let { return it }
        return BundledSchemaRoot.resolve(javaClass.classLoader)
    }

    fun confirmBinary(path: Path): CodexBinaryTrustPolicy.BinaryIdentity {
        val identity = trustPolicy.inspect(CodexBinaryLocator.toRegularExecutable(path))
        trustPolicy.confirm(identity)
        return identity
    }

    /** Discovers and pins `codex` from PATH / CODEX_BIN without a UI prompt. */
    fun autoLinkBinary(): CodexBinaryTrustPolicy.BinaryIdentity =
        trustPolicy.ensureTrustedForLaunch()

    fun connectAppServer(): ProcessEpoch {
        synchronized(sessionLock) {
            ensureGatewayLocked()
            val existing = gateway!!.status()
            if (existing.state == AppServerLifecycleActor.State.Ready) {
                return existing.epoch
            }
            trustPolicy.ensureTrustedForLaunch()
            val epoch = gateway!!.start()
            conversationController?.startEventPump()
            modelCatalog.refresh()
            return epoch
        }
    }

    fun restartAppServer(): ProcessEpoch {
        synchronized(sessionLock) {
            ensureGatewayLocked()
            val epoch = gateway!!.restart()
            conversationController?.startEventPump()
            return epoch
        }
    }

    fun disconnectAppServer() {
        synchronized(sessionLock) {
            conversationController?.stopEventPump()
            gateway?.stop()
        }
    }

    private fun ensureGatewayLocked() {
        if (gateway != null) {
            return
        }
        val adapter = ProtocolAdapter(schemaRoot())
        val sequencer = ProtocolSequencer()
        val transport = JsonRpcTransport(adapter, sequencer)
        val capabilities = CapabilityRegistry(adapter)
        val lifecycle = AppServerLifecycleActor(
            trustPolicy = trustPolicy,
            adapter = adapter,
            transport = transport,
            sequencer = sequencer,
            framer = JsonlFramer(),
            redaction = RedactionPolicy(),
            diagnostics = DiagnosticRingBuffer(),
            workingDir = projectCwd(),
        )
        val g = AppServerGateway(lifecycle, transport, sequencer, capabilities)
        gateway = g
        conversationController = ConversationController(g, stateStore, approvalStateMachine)
        accountController = AccountController(g)
    }

    /** Global trust store — one confirmed Codex binary for all projects. */
    private fun trustStorePath(): Path {
        val dir = Path.of(System.getProperty("user.home"), ".codex-intellij")
        return dir.resolve("confirmed-binary.store")
    }

    private fun readSchemaRootPrefixes(): Pair<String?, String?> {
        return try {
            val validator = ProtocolContractValidator(schemaRoot())
            val trees = validator.validateTrees()
            trees.stableRootSha256.take(12) to trees.experimentalRootSha256.take(12)
        } catch (_: Exception) {
            null to null
        }
    }
}

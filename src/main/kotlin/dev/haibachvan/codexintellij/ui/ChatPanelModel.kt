package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.TurnId
import dev.haibachvan.codexintellij.settings.ApprovalModeOption

/**
 * Panel-local draft/queue/thread selection and turn preferences.
 */
class ChatPanelModel(
    val panelId: String,
) {
    val queue = FollowUpQueueActor()

    @Volatile
    var draft: String = ""
        private set

    @Volatile
    var selectedThread: ThreadId? = null
        private set

    @Volatile
    var activeTurnId: TurnId? = null
        private set

    @Volatile
    var followUpMode: Boolean = true
        private set

    @Volatile
    var selectedModel: String? = null
        private set

    @Volatile
    var selectedEffort: String? = "medium"
        private set

    @Volatile
    var approvalMode: ApprovalModeOption = ApprovalModeOption.FULL_ACCESS
        private set

    @Volatile
    var ideContextEnabled: Boolean = true
        private set

    @Volatile
    var selectedPersonality: String? = null
        private set

    @Volatile
    var selectedServiceTier: String? = null
        private set

    private val threadTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class LocalNotice(
        val id: String,
        val title: String,
        val bodyMarkdown: String,
    )

    private val localNotices = java.util.concurrent.CopyOnWriteArrayList<LocalNotice>()

    val isBusy: Boolean
        get() = activeTurnId != null

    fun notices(): List<LocalNotice> = localNotices.toList()

    fun addNotice(title: String, bodyMarkdown: String) {
        localNotices.add(
            LocalNotice(
                id = "notice-${System.nanoTime()}",
                title = title,
                bodyMarkdown = bodyMarkdown,
            ),
        )
        // Keep the transcript light — retain the latest few command results.
        while (localNotices.size > 40) {
            localNotices.removeAt(0)
        }
    }

    fun clearNotices() {
        localNotices.clear()
    }

    fun rememberThreadTitle(threadId: ThreadId, title: String) {
        val short = ChatTitles.shortTitle(title)
        if (short.isNotBlank() && !ChatTitles.looksLikeThreadId(short)) {
            threadTitles[threadId.value] = short
        }
    }

    fun rememberedTitle(threadId: ThreadId): String? = threadTitles[threadId.value]

    fun updateDraft(text: String) {
        draft = text
    }

    fun clearDraft() {
        draft = ""
    }

    fun selectThread(threadId: ThreadId?) {
        selectedThread = threadId
    }

    fun beginTurn(turnId: TurnId) {
        activeTurnId = turnId
    }

    fun endTurn(turnId: TurnId? = null) {
        if (turnId == null || activeTurnId == null || activeTurnId == turnId) {
            activeTurnId = null
        }
    }

    fun setFollowUpMode(enabled: Boolean) {
        followUpMode = enabled
    }

    fun setSelectedModel(model: String?) {
        selectedModel = model?.takeIf { it.isNotBlank() && it != "default" }
    }

    fun setSelectedEffort(effort: String?) {
        selectedEffort = effort?.takeIf { it.isNotBlank() }
    }

    fun setApprovalMode(mode: ApprovalModeOption) {
        approvalMode = mode
    }

    fun setIdeContextEnabled(enabled: Boolean) {
        ideContextEnabled = enabled
    }

    fun setSelectedPersonality(value: String?) {
        selectedPersonality = value?.takeIf { it.isNotBlank() }
    }

    fun setSelectedServiceTier(value: String?) {
        selectedServiceTier = value?.takeIf { it.isNotBlank() }
    }

    fun toggleFastServiceTier(): Boolean {
        val enabled = selectedServiceTier != "fast"
        selectedServiceTier = if (enabled) "fast" else null
        return enabled
    }

    fun enqueueDraft(): FollowUpQueueActor.Entry? {
        val text = draft.trim()
        if (text.isEmpty()) return null
        queue.handle(FollowUpQueueActor.Command.Enqueue(text))
        clearDraft()
        return queue.entries().lastOrNull()
    }
}

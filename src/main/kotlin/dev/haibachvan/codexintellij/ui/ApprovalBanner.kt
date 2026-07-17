package dev.haibachvan.codexintellij.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.session.ApprovalOutcomeStatus
import dev.haibachvan.codexintellij.session.ApprovalRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class ApprovalBanner(
    private val onAccept: (ApprovalRequest) -> Unit = {},
    private val onReject: (ApprovalRequest) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val label = JBLabel("No pending approvals")
    private val accept = JButton("Accept")
    private val reject = JButton("Reject")
    private var current: ApprovalRequest? = null

    init {
        isVisible = false
        border = JBUI.Borders.empty(6, 8)
        add(label, BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(accept)
            add(reject)
        }
        add(actions, BorderLayout.EAST)
        accept.addActionListener { current?.let(onAccept) }
        reject.addActionListener { current?.let(onReject) }
    }

    fun render(request: ApprovalRequest?) {
        current = request
        if (request == null) {
            isVisible = false
            label.text = "No pending approvals"
            accept.isEnabled = false
            reject.isEnabled = false
            return
        }
        isVisible = true
        val summary = request.payload.entrySet()
            .take(3)
            .joinToString(", ") { "${it.key}=${it.value}" }
            .ifBlank { request.method }
        label.text = buildString {
            append("Approval required · ")
            append(request.method)
            append(" · ")
            append(summary.take(120))
            if (request.status == ApprovalOutcomeStatus.OutcomeUnknown) {
                append(" — reconcile required; resend disabled")
            }
        }
        val actionable = request.status == ApprovalOutcomeStatus.Pending
        accept.isEnabled = actionable
        reject.isEnabled = actionable
        revalidate()
        repaint()
    }
}

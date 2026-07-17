package dev.haibachvan.codexintellij.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.agents.AgentTreeNode
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AgentTreePanel : JPanel(BorderLayout()) {
    private val tree = JTree()

    init {
        border = JBUI.Borders.empty(8)
        add(JBLabel("Agents"), BorderLayout.NORTH)
        add(tree, BorderLayout.CENTER)
    }

    fun render(nodes: List<AgentTreeNode>) {
        val root = DefaultMutableTreeNode("agents")
        fun add(parent: DefaultMutableTreeNode, node: AgentTreeNode) {
            val n = DefaultMutableTreeNode("${node.agentId} (${node.status})")
            parent.add(n)
            node.children.forEach { add(n, it) }
        }
        nodes.forEach { add(root, it) }
        tree.model = DefaultTreeModel(root)
    }
}

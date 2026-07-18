package dev.haibachvan.codexintellij.ui

import com.intellij.util.ui.JBUI

internal object CodexUiMetrics {
    val space4: Int get() = JBUI.scale(4)
    val space8: Int get() = JBUI.scale(8)
    val space12: Int get() = JBUI.scale(12)
    val space16: Int get() = JBUI.scale(16)
    val space24: Int get() = JBUI.scale(24)
    val control24: Int get() = JBUI.scale(24)
    val control28: Int get() = JBUI.scale(28)
    val control32: Int get() = JBUI.scale(32)
    val radiusControl: Int get() = JBUI.scale(4)
    val radiusCard: Int get() = JBUI.scale(8)
    val iconAction: Int get() = JBUI.scale(16)
    val iconToolWindow: Int get() = JBUI.scale(20)
}

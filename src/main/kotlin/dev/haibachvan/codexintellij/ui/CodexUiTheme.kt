package dev.haibachvan.codexintellij.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Shared semantic colors for Codex tool-window surfaces.
 * All tokens are light/dark aware via [JBColor].
 */
object CodexUiTheme {
    val foreground: Color
        get() = JBColor.foreground()

    val muted: Color =
        JBColor.namedColor("Label.infoForeground", JBColor(Color(0x6E6E6E), Color(0xA8A8A8)))

    val border: Color
        get() = JBColor.border()

    val background: Color
        get() = JBColor.background()

    /** Composer / transcript card fill. */
    val cardBg: Color =
        JBColor.namedColor(
            "Codex.Card.background",
            JBColor(Color(0xF5F5F5), Color(0x2B2B2B)),
        )

    val cardBorder: Color =
        JBColor.namedColor(
            "Codex.Card.borderColor",
            JBColor(Color(0xD0D0D0), Color(0x454545)),
        )

    val cardDivider: Color =
        JBColor(Color(0xE0E0E0), Color(0x3A3A3A))

    /** User message bubble. */
    val bubbleBg: Color =
        JBColor(Color(0xE8E8E8), Color(0x3A3A3A))

    val bubbleFg: Color =
        JBColor(Color(0x1E1E1E), Color(0xE8E8E8))

    /** Inline / fenced code background in HTML transcript. */
    val codeBg: Color =
        JBColor(Color(0xEEEEEE), Color(0x2F2F2F))

    val codeFg: Color =
        JBColor(Color(0x24292F), Color(0xD4D4D4))

    val accent: Color =
        JBColor.namedColor("Link.activeForeground", JBColor(Color(0x2470B3), Color(0x589DF6)))

    val success: Color =
        JBColor(Color(0x1A7F37), Color(0x3FB950))

    val danger: Color =
        JBColor(Color(0xCF222E), Color(0xF85149))

    val agentStar: Color =
        JBColor(Color(0xC47A4A), Color(0xE8A87C))

    val agentPillBg: Color =
        JBColor(Color(0xF0F0F0), Color(0x2A2A2A))

    val agentPillBorder: Color =
        JBColor(Color(0xC8C8C8), Color(0x5A5A5A))

    /** Primary circular send button fill. */
    val sendButtonFill: Color =
        JBColor(Color(0x2B2B2B), Color(0xD8D8D8))

    val sendButtonGlyph: Color =
        JBColor(Color(0xF5F5F5), Color(0x2B2B2B))

    val sendButtonDisabled: Color =
        JBColor(Color(0xA0A0A0), Color(0x555555))

    val approvalBg: Color =
        JBColor(Color(0xFFF8E6), Color(0x3D3420))

    val approvalBorder: Color =
        JBColor(Color(0xE6C35C), Color(0x6B5A2A))

    fun css(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}

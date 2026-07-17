package dev.haibachvan.codexintellij.ui

import com.intellij.util.ui.UIUtil
import java.awt.Font

/**
 * Shared UI type scale — content (prose + inline code) is unified at 13pt.
 */
object CodexUiFonts {
    const val BODY_PX = 13
    const val SECONDARY_PX = 13
    const val META_PX = 13
    const val CODE_PX = 13

    const val BODY = 13f
    const val SECONDARY = 13f
    const val META = 13f
    const val TITLE = 13f
    const val CODE = 13f

    fun body(style: Int = Font.PLAIN): Font =
        UIUtil.getLabelFont().deriveFont(style, BODY)

    fun secondary(style: Int = Font.PLAIN): Font =
        UIUtil.getLabelFont().deriveFont(style, SECONDARY)

    fun meta(style: Int = Font.PLAIN): Font =
        UIUtil.getLabelFont().deriveFont(style, META)

    fun title(style: Int = Font.BOLD): Font =
        UIUtil.getLabelFont().deriveFont(style, TITLE)
}

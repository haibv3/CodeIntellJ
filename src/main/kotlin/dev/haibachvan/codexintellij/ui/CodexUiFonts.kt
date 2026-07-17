package dev.haibachvan.codexintellij.ui

import com.intellij.util.ui.UIUtil
import java.awt.Font

/**
 * Shared UI type scale — clear hierarchy without fighting IDE label fonts.
 */
object CodexUiFonts {
    const val BODY_PX = 13
    const val SECONDARY_PX = 12
    const val META_PX = 11
    const val CODE_PX = 13

    const val BODY = 13f
    const val SECONDARY = 12f
    const val META = 11f
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

package dev.haibachvan.codexintellij.ui

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class ComposerAttachment(
    val id: String = UUID.randomUUID().toString(),
    val absolutePath: Path,
    val displayPath: String,
    val kind: Kind,
) {
    enum class Kind { IMAGE, FILE, FOLDER }

    val fileName: String get() = absolutePath.name

    val subtitle: String
        get() = when (kind) {
            Kind.FOLDER -> "DIR"
            Kind.IMAGE, Kind.FILE -> {
                val ext = absolutePath.extension
                if (ext.isBlank()) {
                    if (kind == Kind.IMAGE) "IMG" else "FILE"
                } else {
                    ext.uppercase(Locale.ROOT)
                }
            }
        }

    fun toWireInput(): JsonObject =
        when (kind) {
            Kind.IMAGE -> JsonObject().apply {
                addProperty("type", "localImage")
                addProperty("path", absolutePath.toAbsolutePath().normalize().toString())
            }
            Kind.FILE, Kind.FOLDER -> JsonObject().apply {
                addProperty("type", "mention")
                addProperty("name", fileName)
                addProperty("path", displayPath)
            }
        }

    companion object {
        private val IMAGE_EXTS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "avif",
        )

        fun fromPath(absolute: Path, displayPath: String): ComposerAttachment {
            val kind = when {
                Files.isDirectory(absolute) -> Kind.FOLDER
                absolute.extension.lowercase(Locale.ROOT) in IMAGE_EXTS -> Kind.IMAGE
                else -> Kind.FILE
            }
            return ComposerAttachment(
                absolutePath = absolute.toAbsolutePath().normalize(),
                displayPath = displayPath.replace('\\', '/'),
                kind = kind,
            )
        }
    }
}

package dev.haibachvan.codexintellij.appserver

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * Ensures the committed protocol schema tree is available as a real filesystem path.
 * Installed plugin zips do not keep `protocol-schema/` next to the opened project, so the
 * tree is bundled under classpath resources and extracted once into the IDE system dir.
 */
object BundledSchemaRoot {
    const val RESOURCE_PREFIX = "protocol-schema/codex-0.144.5"
    private const val VERSION = "codex-0.144.5"

    private val rootFiles = listOf(
        "schema-inventory.txt",
        "schema-manifest.json",
        "method-schema-map.json",
    )

    fun resolve(classLoader: ClassLoader = BundledSchemaRoot::class.java.classLoader): Path {
        val target = Path.of(PathManager.getSystemPath(), "codex-intellij", "schema", VERSION)
        val marker = target.resolve(".bundle-complete")
        val map = target.resolve("method-schema-map.json")
        if (marker.isRegularFile() && map.isRegularFile()) {
            return target
        }

        Files.createDirectories(target)
        val inventoryStream = classLoader.getResourceAsStream("$RESOURCE_PREFIX/schema-inventory.txt")
            ?: error(
                "Bundled schema missing from plugin classpath ($RESOURCE_PREFIX/schema-inventory.txt). " +
                    "Rebuild/install the plugin so protocol-schema is packaged.",
            )
        val inventory = inventoryStream.bufferedReader().use { it.readText() }
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        for (name in rootFiles) {
            copyResource(classLoader, name, target.resolve(name))
        }
        for (rel in inventory) {
            copyResource(classLoader, rel, target.resolve(rel))
        }

        // Copy optional provenance/docs if present in the bundle.
        for (optional in listOf("provenance.json", "README.md")) {
            val stream = classLoader.getResourceAsStream("$RESOURCE_PREFIX/$optional") ?: continue
            stream.use {
                Files.createDirectories(target.resolve(optional).parent)
                Files.copy(it, target.resolve(optional), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        Files.writeString(marker, "ok\n")
        require(map.isRegularFile()) { "Bundled schema extract failed: missing method-schema-map.json at $target" }
        return target
    }

    fun projectSchemaIfPresent(projectBasePath: String?): Path? {
        if (projectBasePath.isNullOrBlank()) return null
        val candidate = Path.of(projectBasePath, "protocol-schema", VERSION)
        return candidate.takeIf { it.resolve("method-schema-map.json").isRegularFile() }
    }

    private fun copyResource(classLoader: ClassLoader, relative: String, destination: Path) {
        val stream = classLoader.getResourceAsStream("$RESOURCE_PREFIX/$relative")
            ?: error("Bundled schema entry missing: $RESOURCE_PREFIX/$relative")
        stream.use {
            Files.createDirectories(destination.parent)
            Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

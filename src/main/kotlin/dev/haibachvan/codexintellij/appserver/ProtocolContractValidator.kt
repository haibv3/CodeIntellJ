package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Validates committed full-tree schema evidence and handwritten DTO goldens.
 * No Kotlin code generation — schemas remain data under protocol-schema/.
 */
class ProtocolContractValidator(
    private val schemaRoot: Path,
) {
    data class TreeValidation(
        val inventoryCount: Int,
        val stableRootSha256: String,
        val experimentalRootSha256: String,
    )

    data class MethodMapValidation(
        val methodCount: Int,
        val stableCount: Int,
        val experimentalCount: Int,
    )

    data class MethodMapping(
        val method: String,
        val direction: String,
        val apiClass: String,
        val tree: String,
        val requestSchema: String?,
        val responseSchema: String?,
        val notificationSchema: String?,
        val nullParams: Boolean,
    )

    fun validateTrees(
        inventoryPath: Path = schemaRoot.resolve("schema-inventory.txt"),
        manifestPath: Path = schemaRoot.resolve("schema-manifest.json"),
    ): TreeValidation {
        require(inventoryPath.isRegularFile()) { "Missing inventory: $inventoryPath" }
        require(manifestPath.isRegularFile()) { "Missing manifest: $manifestPath" }

        val inventory = inventoryPath.readText().lines().filter { it.isNotBlank() }
        require(inventory == inventory.sorted()) { "schema-inventory.txt must be sorted" }
        require(inventory.size == inventory.toSet().size) { "schema-inventory.txt has duplicates" }

        val manifest = JsonParser.parseString(manifestPath.readText()).asJsonObject
        val files = manifest.getAsJsonObject("files")
            ?: throw IllegalArgumentException("Manifest missing files object: $manifestPath")
        require(files.entrySet().map { it.key }.sorted() == inventory) {
            "Manifest files do not match inventory"
        }

        val hStable = MessageDigest.getInstance("SHA-256")
        val hExp = MessageDigest.getInstance("SHA-256")
        for (rel in inventory) {
            val path = schemaRoot.resolve(rel)
            require(path.isRegularFile()) { "Inventory entry missing: $rel" }
            val digest = sha256Hex(path.readBytes())
            val expected = files.get(rel)?.asString
            require(expected == digest) { "Hash mismatch for $rel" }
            val line = "$digest  $rel\n".toByteArray()
            when {
                rel.startsWith("stable/") -> hStable.update(line)
                rel.startsWith("experimental/") -> hExp.update(line)
                else -> error("Inventory entry must be under stable/ or experimental/: $rel")
            }
        }

        val roots = manifest.getAsJsonObject("roots")
        val expectedStableRoot = roots.getAsJsonObject("stable").get("sha256").asString
        val expectedExperimentalRoot = roots.getAsJsonObject("experimental").get("sha256").asString
        val actualStable = hStable.digest().joinToString("") { "%02x".format(it) }
        val actualExperimental = hExp.digest().joinToString("") { "%02x".format(it) }
        require(actualStable == expectedStableRoot) {
            "Stable root hash mismatch expected=$expectedStableRoot actual=$actualStable"
        }
        require(actualExperimental == expectedExperimentalRoot) {
            "Experimental root hash mismatch expected=$expectedExperimentalRoot actual=$actualExperimental"
        }

        return TreeValidation(
            inventoryCount = inventory.size,
            stableRootSha256 = actualStable,
            experimentalRootSha256 = actualExperimental,
        )
    }

    fun loadMethodMap(
        mapPath: Path = schemaRoot.resolve("method-schema-map.json"),
    ): List<MethodMapping> {
        require(mapPath.isRegularFile()) { "Missing method-schema-map.json" }
        val root = JsonParser.parseString(mapPath.readText()).asJsonObject
        val methods = root.getAsJsonArray("methods")
        return methods.map { el ->
            val obj = el.asJsonObject
            MethodMapping(
                method = obj.get("method").asString,
                direction = obj.get("direction").asString,
                apiClass = obj.get("apiClass").asString,
                tree = obj.get("tree").asString,
                requestSchema = obj.get("requestSchema")?.takeUnless { it.isJsonNull }?.asString,
                responseSchema = obj.get("responseSchema")?.takeUnless { it.isJsonNull }?.asString,
                notificationSchema = obj.get("notificationSchema")?.takeUnless { it.isJsonNull }?.asString,
                nullParams = obj.get("nullParams")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }

    fun validateMethodMap(
        mapPath: Path = schemaRoot.resolve("method-schema-map.json"),
    ): MethodMapValidation {
        val methods = loadMethodMap(mapPath)
        require(methods.isNotEmpty()) { "method-schema-map.json is empty" }

        val seen = HashSet<String>()
        for (mapping in methods) {
            val key = "${mapping.direction}:${mapping.method}"
            require(seen.add(key)) { "Duplicate method mapping: $key" }
            require(mapping.apiClass == "stable" || mapping.apiClass == "experimental") {
                "Invalid apiClass for ${mapping.method}"
            }
            require(mapping.tree == mapping.apiClass) {
                "tree/apiClass mismatch for ${mapping.method}"
            }

            fun checkMapped(rel: String?, label: String) {
                if (rel == null) return
                val path = schemaRoot.resolve(rel)
                require(path.isRegularFile()) {
                    "Mapped $label for ${mapping.method} missing: $rel"
                }
                require(rel.startsWith("${mapping.tree}/")) {
                    "Mapped $label for ${mapping.method} not under tree ${mapping.tree}: $rel"
                }
            }

            when (mapping.direction) {
                "clientRequest", "serverRequest" -> {
                    if (mapping.requestSchema == null) {
                        require(mapping.nullParams) {
                            "Unmapped request schema for ${mapping.method}"
                        }
                    } else {
                        checkMapped(mapping.requestSchema, "requestSchema")
                    }
                    checkMapped(mapping.responseSchema, "responseSchema")
                }
                "serverNotification", "clientNotification" -> {
                    require(mapping.notificationSchema != null) {
                        "Unmapped notification schema for ${mapping.method}"
                    }
                    checkMapped(mapping.notificationSchema, "notificationSchema")
                }
                else -> error("Unknown direction ${mapping.direction} for ${mapping.method}")
            }
        }

        return MethodMapValidation(
            methodCount = methods.size,
            stableCount = methods.count { it.apiClass == "stable" },
            experimentalCount = methods.count { it.apiClass == "experimental" },
        )
    }

    /**
     * Validates handwritten DTO golden JSON against the mapped per-type schema document.
     * Unknown properties remain non-fatal (JsonObject boundary).
     */
    fun validateGolden(method: String, json: String, role: GoldenRole = GoldenRole.REQUEST) {
        val mapping = findMethod(method)
            ?: throw IllegalArgumentException("Unmapped method: $method")
        val schemaRel = when (role) {
            GoldenRole.REQUEST -> mapping.requestSchema
            GoldenRole.RESPONSE -> mapping.responseSchema
            GoldenRole.NOTIFICATION -> mapping.notificationSchema
        }
        require(schemaRel != null) {
            "No ${role.name.lowercase()} schema mapped for method $method"
        }

        val schemaPath = schemaRoot.resolve(schemaRel)
        val schemaElement = JsonParser.parseString(schemaPath.readText()).asJsonObject
        val payload = JsonParser.parseString(json)
        validateAgainstSchema(
            payload = payload,
            schema = schemaElement,
            document = schemaElement,
            schemaDir = schemaPath.parent,
            method = method,
        )
    }

    fun findMethod(method: String): MethodMapping? {
        val all = loadMethodMap()
        return all.find { it.method == method && it.direction == "clientRequest" }
            ?: all.find { it.method == method }
    }

    enum class GoldenRole {
        REQUEST,
        RESPONSE,
        NOTIFICATION,
    }

    private fun validateAgainstSchema(
        payload: JsonElement,
        schema: JsonElement,
        document: JsonObject,
        schemaDir: Path,
        method: String,
        depth: Int = 0,
    ) {
        require(depth < 64) { "Schema validation recursion too deep for $method" }
        if (!schema.isJsonObject) {
            return
        }
        val obj = schema.asJsonObject

        if (obj.has("\$ref")) {
            val ref = obj.get("\$ref").asString
            if (ref.startsWith("#/definitions/")) {
                val name = ref.removePrefix("#/definitions/")
                val defs = document.getAsJsonObject("definitions")
                    ?: error("Missing definitions for local ref $ref in $method")
                val resolved = defs.get(name)
                    ?: error("Unresolved local ref $ref for $method")
                validateAgainstSchema(payload, resolved, document, schemaDir, method, depth + 1)
                return
            }
            val resolvedPath = resolveRefPath(ref, schemaDir)
            val resolvedDoc = JsonParser.parseString(resolvedPath.readText()).asJsonObject
            validateAgainstSchema(payload, resolvedDoc, resolvedDoc, resolvedPath.parent, method, depth + 1)
            return
        }

        if (obj.has("const")) {
            require(payload.isJsonPrimitive && payload.asString == obj.get("const").asString) {
                "const mismatch for $method"
            }
            return
        }

        if (obj.has("enum") && payload.isJsonPrimitive) {
            val allowed = obj.getAsJsonArray("enum").map { it.asString }
            require(payload.asString in allowed) { "enum mismatch for $method: ${payload.asString}" }
        }

        if (obj.has("type")) {
            val typeEl = obj.get("type")
            val types = if (typeEl.isJsonArray) {
                typeEl.asJsonArray.map { it.asString }
            } else {
                listOf(typeEl.asString)
            }
            if ("null" in types && payload.isJsonNull) {
                return
            }
            when {
                "object" in types -> require(payload.isJsonObject) { "Expected object for $method" }
                "array" in types -> require(payload.isJsonArray) { "Expected array for $method" }
                "string" in types -> require(payload.isJsonPrimitive && payload.asJsonPrimitive.isString) {
                    "Expected string for $method"
                }
                "number" in types || "integer" in types ->
                    require(payload.isJsonPrimitive && payload.asJsonPrimitive.isNumber) {
                        "Expected number for $method"
                    }
                "boolean" in types ->
                    require(payload.isJsonPrimitive && payload.asJsonPrimitive.isBoolean) {
                        "Expected boolean for $method"
                    }
            }
        }

        if (obj.has("oneOf")) {
            val options = obj.getAsJsonArray("oneOf")
            var matched = 0
            var lastError: String? = null
            for (option in options) {
                try {
                    validateAgainstSchema(payload, option, document, schemaDir, method, depth + 1)
                    matched += 1
                } catch (ex: IllegalArgumentException) {
                    lastError = ex.message
                }
            }
            require(matched == 1) {
                "oneOf for $method matched $matched options${lastError?.let { ": $it" } ?: ""}"
            }
            return
        }

        if (obj.has("anyOf")) {
            val options = obj.getAsJsonArray("anyOf")
            var matched = false
            var lastError: String? = null
            for (option in options) {
                try {
                    validateAgainstSchema(payload, option, document, schemaDir, method, depth + 1)
                    matched = true
                    break
                } catch (ex: IllegalArgumentException) {
                    lastError = ex.message
                }
            }
            require(matched) {
                "anyOf for $method matched no options${lastError?.let { ": $it" } ?: ""}"
            }
            return
        }

        if (payload.isJsonObject && obj.has("required")) {
            val required = obj.getAsJsonArray("required").map { it.asString }
            val props = payload.asJsonObject
            for (name in required) {
                require(props.has(name)) { "Missing required property '$name' for $method" }
            }
        }

        if (payload.isJsonObject && obj.has("properties")) {
            val schemaProps = obj.getAsJsonObject("properties")
            for ((name, value) in payload.asJsonObject.entrySet()) {
                if (schemaProps.has(name)) {
                    validateAgainstSchema(
                        value,
                        schemaProps.get(name),
                        document,
                        schemaDir,
                        method,
                        depth + 1,
                    )
                }
            }
        }
    }

    private fun resolveRefPath(ref: String, schemaDir: Path): Path {
        val target = if (ref.startsWith("./")) {
            schemaDir.resolve(ref.removePrefix("./"))
        } else {
            schemaDir.resolve(ref)
        }
        require(Files.isRegularFile(target)) { "Unable to resolve schema ref $ref at $target" }
        return target
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

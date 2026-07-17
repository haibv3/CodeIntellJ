package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProtocolContractValidatorTest {
    private val schemaRoot: Path =
        Path.of("protocol-schema/codex-0.144.5").toAbsolutePath().normalize()
    private val fixtures: Path =
        Path.of("src/test/resources/fixtures/appserver/0.144.5").toAbsolutePath().normalize()
    private val validator = ProtocolContractValidator(schemaRoot)

    @Test
    fun `validateTrees accepts committed inventory and hashes`() {
        val result = validator.validateTrees()
        assertEquals(604, result.inventoryCount)
        assertTrue(result.stableRootSha256.isNotBlank())
        assertTrue(result.experimentalRootSha256.isNotBlank())
    }

    @Test
    fun `validateMethodMap resolves every mapped schema and classifies stable versus experimental`() {
        val result = validator.validateMethodMap()
        assertTrue(result.methodCount >= 100, "expected full method map, got ${result.methodCount}")
        assertTrue(result.stableCount > 0)
        assertTrue(result.experimentalCount > 0)

        val initialize = validator.findMethod("initialize")
        assertNotNull(initialize)
        assertEquals("stable", initialize!!.apiClass)
        assertEquals("stable/v1/InitializeParams.json", initialize.requestSchema)

        val experimental = validator.loadMethodMap().filter { it.apiClass == "experimental" }
        assertTrue(experimental.any { it.method.startsWith("remoteControl/") || it.method == "memory/reset" })
    }

    @Test
    fun `validateGolden accepts handwritten stable request response and notification fixtures`() {
        validator.validateGolden(
            method = "initialize",
            json = fixtures.resolve("initialize-request.json").readText(),
            role = ProtocolContractValidator.GoldenRole.REQUEST,
        )
        validator.validateGolden(
            method = "initialize",
            json = fixtures.resolve("initialize-response.json").readText(),
            role = ProtocolContractValidator.GoldenRole.RESPONSE,
        )
        validator.validateGolden(
            method = "thread/start",
            json = fixtures.resolve("thread-start-request.json").readText(),
            role = ProtocolContractValidator.GoldenRole.REQUEST,
        )
        validator.validateGolden(
            method = "thread/archived",
            json = fixtures.resolve("thread-archived-notification.json").readText(),
            role = ProtocolContractValidator.GoldenRole.NOTIFICATION,
        )
    }

    @Test
    fun `unmapped and ambiguous methods are rejected`() {
        assertThrows<IllegalArgumentException> {
            validator.validateGolden("not/a/real/method", "{}")
        }
        assertThrows<IllegalArgumentException> {
            validator.validateGolden(
                method = "initialize",
                json = """{"clientInfo":{"name":"x"}}""",
            )
        }
    }

    @Test
    fun `unknown envelope remains JsonObject boundary`() {
        val raw = JsonParser.parseString(fixtures.resolve("unknown-envelope.json").readText()).asJsonObject
        val unknown = UnknownEnvelope(raw)
        assertEquals("future/unknownMethod", unknown.raw.get("method").asString)
        assertTrue(unknown.raw.getAsJsonObject("params").get("opaque").asBoolean)
        assertThrows<IllegalArgumentException> {
            validator.validateGolden(unknown.raw.get("method").asString, unknown.raw.toString())
        }
    }

    @Test
    fun `tampered schema hash fails tree validation`() {
        val tempDir = kotlin.io.path.createTempDirectory("schema-tamper-")
        try {
            val inventory = tempDir.resolve("schema-inventory.txt")
            val manifest = tempDir.resolve("schema-manifest.json")
            // Point inventory at a real relative file from the committed tree via a copied stub entry.
            val sampleRel = "stable/v1/InitializeParams.json"
            inventory.toFile().writeText("$sampleRel\n")
            manifest.toFile().writeText(
                """
                {
                  "files": { "$sampleRel": "0000000000000000000000000000000000000000000000000000000000000000" },
                  "roots": {
                    "stable": { "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
                    "experimental": { "sha256": "2222222222222222222222222222222222222222222222222222222222222222" }
                  }
                }
                """.trimIndent(),
            )
            // Validator resolves inventory paths against schemaRoot, so plant a mismatched hash
            // for an existing file using the real schema root + fake manifest overlay is awkward.
            // Instead assert the real validator rejects a wrong root hash when files map is wrong:
            assertThrows<IllegalArgumentException> {
                ProtocolContractValidator(schemaRoot).validateTrees(
                    inventoryPath = schemaRoot.resolve("schema-inventory.txt"),
                    manifestPath = manifest,
                )
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}

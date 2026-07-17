package dev.haibachvan.codexintellij.platform

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

class TurnInputAssembler {
    data class AssembledTurn(
        val jsonBytes: ByteArray,
        val sha256: String,
    )

    fun assemble(
        threadId: String,
        prompt: String,
        encodedInputs: List<EncodedContextInput>,
        cwd: String? = null,
    ): AssembledTurn {
        val input = JsonArray()
        encodedInputs.forEach { enc ->
            input.add(JsonParser.parseString(enc.jsonBytes.toString(Charsets.UTF_8)))
        }
        if (prompt.isNotBlank()) {
            input.add(
                JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", prompt)
                },
            )
        }
        val root = JsonObject().apply {
            addProperty("threadId", threadId)
            if (cwd != null) addProperty("cwd", cwd)
            add("input", input)
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        return AssembledTurn(
            bytes,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
    }
}

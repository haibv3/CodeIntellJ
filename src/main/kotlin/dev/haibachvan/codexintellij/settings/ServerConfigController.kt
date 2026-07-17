package dev.haibachvan.codexintellij.settings

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import java.util.concurrent.CompletableFuture

class ServerConfigController(
    private val gateway: AppServerGateway,
) {
    fun write(key: String, value: String): CompletableFuture<Boolean> {
        require(WritableConfigAllowlist.contains(key)) { "Config key not allowlisted: $key" }
        require(!WritableConfigAllowlist.isSecret(key)) { "Secret config keys cannot be written from UI" }
        val params = JsonObject().apply {
            add(
                "config",
                JsonObject().apply { addProperty(key, value) },
            )
        }
        return gateway.request("config/write", params).thenApply { it.error == null }
    }
}

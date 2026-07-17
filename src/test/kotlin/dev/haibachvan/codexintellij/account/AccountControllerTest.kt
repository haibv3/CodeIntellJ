package dev.haibachvan.codexintellij.account

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccountControllerTest {
    @Test
    fun `signed out login and expiry states`() {
        val rpc = AccountRpc { method, _ ->
            val result = when (method) {
                "account/read" -> JsonObject().apply { addProperty("loggedIn", false) }
                "account/login/start" -> JsonObject().apply { addProperty("loggedIn", true) }
                else -> JsonObject()
            }
            CompletableFuture.completedFuture(WireEnvelope.Response("1", result, null))
        }
        val controller = AccountController(rpc)
        assertEquals(AccountState.SignedOut, controller.read().get())
        assertEquals(AccountState.SignedIn, controller.login().get())
        controller.markExpired()
        assertEquals(AccountState.Expired, controller.state)
        assertEquals(AccountState.SignedOut, controller.logout().get())
    }
}

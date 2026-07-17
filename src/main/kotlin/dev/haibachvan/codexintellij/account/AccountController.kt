package dev.haibachvan.codexintellij.account

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import java.util.concurrent.CompletableFuture

enum class AccountState { SignedOut, SignedIn, LoginInProgress, Expired }

fun interface AccountRpc {
    fun request(method: String, params: JsonObject?): CompletableFuture<WireEnvelope.Response>
}

class AccountController(
    private val rpc: AccountRpc,
) {
    constructor(gateway: AppServerGateway) : this(AccountRpc { method, params -> gateway.request(method, params) })

    @Volatile
    var state: AccountState = AccountState.SignedOut
        private set

    fun read(): CompletableFuture<AccountState> =
        rpc.request("account/read", JsonObject()).thenApply { response ->
            state = when {
                response.error != null -> AccountState.SignedOut
                response.result?.get("loggedIn")?.asBoolean == true -> AccountState.SignedIn
                else -> AccountState.SignedOut
            }
            state
        }.exceptionally {
            state = AccountState.SignedOut
            AccountState.SignedOut
        }

    fun login(): CompletableFuture<AccountState> {
        state = AccountState.LoginInProgress
        return rpc.request("account/login/start", JsonObject()).thenApply {
            state = AccountState.SignedIn
            state
        }.exceptionally {
            state = AccountState.SignedOut
            AccountState.SignedOut
        }
    }

    fun logout(): CompletableFuture<AccountState> =
        rpc.request("account/logout", JsonObject()).thenApply {
            state = AccountState.SignedOut
            state
        }

    fun markExpired() {
        state = AccountState.Expired
    }
}

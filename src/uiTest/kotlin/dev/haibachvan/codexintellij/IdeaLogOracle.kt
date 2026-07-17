package dev.haibachvan.codexintellij

object IdeaLogOracle {
    private val forbidden = listOf("Access is allowed from Event Dispatch Thread", "sk-", "OPENAI_API_KEY")

    fun assertClean(logText: String) {
        forbidden.forEach { token ->
            require(!logText.contains(token)) { "Log oracle failed on token: $token" }
        }
    }
}

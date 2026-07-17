package dev.haibachvan.codexintellij.settings

enum class WritableConfigKey(val wireKey: String, val secret: Boolean = false) {
    MODEL("model"),
    PERSONALITY("personality"),
    REASONING_EFFORT("effort"),
    APPROVAL_POLICY("approvalPolicy"),
    SANDBOX_MODE("sandboxMode"),
}

object WritableConfigAllowlist {
    fun contains(key: String): Boolean = WritableConfigKey.entries.any { it.wireKey == key }
    fun isSecret(key: String): Boolean = WritableConfigKey.entries.any { it.wireKey == key && it.secret }
}

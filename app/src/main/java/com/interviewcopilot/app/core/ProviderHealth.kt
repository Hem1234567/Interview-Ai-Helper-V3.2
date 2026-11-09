package com.interviewcopilot.app.core

/** Why an answer engine failed. Decides how long it is skipped by Auto mode. */
enum class Failure { RateLimit, Auth, Server, Timeout, Network, Other }

/** Remembers which engines recently failed so Auto mode skips them instead of waiting for them again. */
class ProviderHealth(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val until = HashMap<String, Long>()
    private val lastFailure = HashMap<String, Failure>()

    @Synchronized
    fun markFailed(name: String, failure: Failure, retryAfterMs: Long? = null) {
        val wait = when (failure) {
            Failure.RateLimit -> (retryAfterMs ?: 60_000L).coerceIn(5_000L, 300_000L)
            Failure.Auth -> 600_000L
            Failure.Server -> 30_000L
            Failure.Timeout -> 20_000L
            Failure.Network -> 15_000L
            Failure.Other -> 20_000L
        }
        until[name] = clock() + wait
        lastFailure[name] = failure
    }

    @Synchronized
    fun markOk(name: String) {
        until.remove(name)
        lastFailure.remove(name)
    }

    /** Milliseconds this engine will still be skipped; 0 when it is usable. */
    @Synchronized
    fun coolingMs(name: String): Long = ((until[name] ?: 0L) - clock()).coerceAtLeast(0L)

    @Synchronized
    fun failureOf(name: String): Failure? = if (coolingMs(name) > 0) lastFailure[name] else null

    @Synchronized
    fun reset() {
        until.clear()
        lastFailure.clear()
    }
}

/** The engines the app can answer with, and the order Auto mode tries them in. */
object EngineOrder {
    const val AUTO = "Auto"
    const val LOCAL = "Local"
    const val OPENROUTER = "OpenRouter"
    const val GEMINI = "Gemini"
    const val GROQ = "Groq"

    val all = listOf(AUTO, LOCAL, OPENROUTER, GEMINI, GROQ)

    /** Fastest first. Local is deliberately the last resort in Auto mode. */
    private val onlineByPriority = listOf(GROQ, GEMINI, OPENROUTER)

    fun display(name: String): String = if (name == GEMINI) "Google" else name

    fun normalize(name: String): String = if (name in all) name else AUTO

    /**
     * The engines to try, in order.
     *
     * A fixed choice means exactly that engine. Auto means: working online engines (fastest first),
     * then the on-device model, then online engines that are cooling down (a last attempt in case they recovered).
     */
    fun chain(selected: String, ready: Set<String>, health: ProviderHealth): List<String> {
        val pick = normalize(selected)
        if (pick != AUTO) return if (pick in ready) listOf(pick) else emptyList()
        val online = onlineByPriority.filter { it in ready }
        val healthy = online.filter { health.coolingMs(it) == 0L }
        val cooling = online.filter { health.coolingMs(it) > 0L }
        return healthy + listOfNotNull(LOCAL.takeIf { it in ready }) + cooling
    }
}

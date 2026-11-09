package com.interviewcopilot.app.logic

import com.interviewcopilot.app.core.Failure
import com.interviewcopilot.app.llm.LlamaEngine
import com.interviewcopilot.app.llm.LocalModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** An answer engine failed in a way that says why (limit reached, bad key, server busy ...). */
class ProviderError(
    val failure: Failure,
    message: String,
    val retryAfterMs: Long? = null,
    val code: Int = 0,
    val detail: String = "",
) : IOException(message)

interface LlmProvider {
    val name: String
    val firstTokenTimeoutMs: Long
    fun stream(system: String, user: String, maxTokens: Int): Flow<String>

    /** Cancels the request that is in flight, so a slow engine never blocks the next one. */
    fun abort() {}
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Opens the connection (DNS, TLS) ahead of time so the first request of an answer starts faster. */
    fun warm(urls: List<String>) {
        urls.forEach { u ->
            try {
                val req = Request.Builder().url(u).head().build()
                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                    }
                })
            } catch (ignored: Exception) {
                // warming is best effort
            }
        }
    }
}

internal fun httpError(provider: String, code: Int, body: String, retryAfter: String?): ProviderError {
    val seconds = retryAfter?.trim()?.toDoubleOrNull()
    val lower = body.lowercase()
    val failure = when {
        code == 429 || code == 402 || lower.contains("resource_exhausted") || lower.contains("quota") -> Failure.RateLimit
        code == 401 || code == 403 || code == 404 || lower.contains("api key not valid") || lower.contains("api_key_invalid") -> Failure.Auth
        code in 500..599 -> Failure.Server
        else -> Failure.Other
    }
    val brief = body.replace(Regex("""\s+"""), " ").take(160)
    val message = when (failure) {
        Failure.RateLimit -> "$provider limit reached"
        Failure.Auth -> "$provider rejected the key or model (HTTP $code)"
        Failure.Server -> "$provider is busy (HTTP $code)"
        else -> "$provider error $code: $brief"
    }
    return ProviderError(failure, message, seconds?.let { (it * 1000).toLong() }, code, body.take(400))
}

private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")

/** Base of the online engines: remembers the running call so [abort] can cancel it. */
abstract class OnlineProvider : LlmProvider {
    @Volatile
    private var call: Call? = null

    override fun abort() {
        call?.cancel()
    }

    fun track(c: Call): Call {
        call = c
        return c
    }
}

/** OpenAI-compatible chat streaming (Groq and OpenRouter use this). */
private fun openAiStream(owner: OnlineProvider, provider: String, req: Request): Flow<String> = flow {
    val call = owner.track(Http.client.newCall(req))
    try {
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(provider, resp.code, resp.body?.string().orEmpty(), resp.header("retry-after"))
            val src = resp.body!!.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val piece = try {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").str("content")
                } catch (e: Exception) {
                    ""
                }
                if (piece.isNotEmpty()) emit(piece)
            }
        }
    } finally {
        call.cancel()
    }
}.flowOn(Dispatchers.IO)

private fun chatBody(model: String, system: String, user: String, maxTokens: Int): JSONObject {
    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))
    return JSONObject()
        .put("model", model)
        .put("stream", true)
        .put("max_tokens", maxTokens)
        .put("temperature", 0.3)
        .put("messages", messages)
}

class GroqProvider(private val key: String, private val model: String) : OnlineProvider() {
    override val name = "Groq"
    override val firstTokenTimeoutMs = 3000L

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> {
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(chatBody(model, system, user, maxTokens).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return openAiStream(this, name, req)
    }
}

class OpenRouterProvider(private val key: String, private val model: String) : OnlineProvider() {
    override val name = "OpenRouter"
    override val firstTokenTimeoutMs = 4500L

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> {
        val body = chatBody(model, system, user, maxTokens)
            // Prefer the fastest host that serves this model.
            .put("provider", JSONObject().put("sort", "latency"))
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://interviewcopilot.app")
            .addHeader("X-Title", "Interview Copilot")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return openAiStream(this, name, req)
    }
}

/** Remembers which Gemini model ids rejected the "thinking" setting, so it is not sent again. */
private object GeminiQuirks {
    val noThinking: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

class GeminiProvider(private val key: String, private val model: String) : OnlineProvider() {
    override val name = "Gemini"
    override val firstTokenTimeoutMs = 3500L

    /** Thinking is switched off or kept minimal: for an interview, speed matters more than long reasoning. */
    private fun thinking(): JSONObject? {
        val m = model.lowercase()
        if (m in GeminiQuirks.noThinking) return null
        return when {
            m.contains("2.5") && m.contains("pro") -> null
            m.contains("2.5") -> JSONObject().put("thinkingBudget", 0)
            m.contains("gemini-3") || m.contains("latest") -> JSONObject().put("thinkingLevel", "minimal")
            else -> null
        }
    }

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> = flow {
        val think = thinking()
        try {
            emitAll(request(system, user, maxTokens, think))
        } catch (e: ProviderError) {
            if (e.code == 400 && think != null && e.detail.lowercase().contains("think")) {
                GeminiQuirks.noThinking.add(model.lowercase())
                emitAll(request(system, user, maxTokens, null))
            } else {
                throw e
            }
        }
    }

    private fun request(system: String, user: String, maxTokens: Int, think: JSONObject?): Flow<String> = flow {
        val config = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", 0.3)
        if (think != null) config.put("thinkingConfig", think)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))),
            )
            .put("generationConfig", config)
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
            .addHeader("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = track(Http.client.newCall(req))
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError("Gemini", resp.code, resp.body?.string().orEmpty(), resp.header("retry-after"))
                val src = resp.body!!.source()
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val piece = try {
                        val parts = JSONObject(data).getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (!part.optBoolean("thought", false)) sb.append(part.str("text"))
                        }
                        sb.toString()
                    } catch (e: Exception) {
                        ""
                    }
                    if (piece.isNotEmpty()) emit(piece)
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)
}

/** On-device answer engine (llama.cpp). Loads the model on first use if the warm-up has not finished. */
class LocalProvider(
    private val engine: LlamaEngine,
    private val model: LocalModel,
    private val file: java.io.File,
) : LlmProvider {
    override val name = "Local"
    override val firstTokenTimeoutMs = 120_000L

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> = flow {
        if (!engine.load(model, file)) throw IOException("The on-device model could not be loaded.")
        emitAll(engine.stream(system, user, maxTokens, thinking = false))
    }

    override fun abort() {
        engine.cancel()
    }
}

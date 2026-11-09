package com.interviewcopilot.app

import com.interviewcopilot.app.core.EngineOrder
import com.interviewcopilot.app.core.Failure
import com.interviewcopilot.app.core.ProviderHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthTest {
    private var now = 1_000_000L
    private val health = ProviderHealth { now }
    private val all = setOf("Local", "OpenRouter", "Gemini", "Groq")

    @Test
    fun autoTriesFastestOnlineFirstAndLocalLast() {
        assertEquals(listOf("Groq", "Gemini", "OpenRouter", "Local"), EngineOrder.chain("Auto", all, health))
    }

    @Test
    fun autoSkipsAnEngineThatHitItsLimitUntilItRecovers() {
        health.markFailed("Groq", Failure.RateLimit, 30_000)
        assertEquals(listOf("Gemini", "OpenRouter", "Local", "Groq"), EngineOrder.chain("Auto", all, health))
        now += 31_000
        assertEquals(listOf("Groq", "Gemini", "OpenRouter", "Local"), EngineOrder.chain("Auto", all, health))
    }

    @Test
    fun whenEveryOnlineEngineFailsTheLocalModelComesNext() {
        health.markFailed("Groq", Failure.RateLimit)
        health.markFailed("Gemini", Failure.Auth)
        health.markFailed("OpenRouter", Failure.Server)
        val chain = EngineOrder.chain("Auto", all, health)
        assertEquals("Local", chain.first())
        assertEquals(4, chain.size)
    }

    @Test
    fun autoWithOnlyKeysOrOnlyLocalWorks() {
        assertEquals(listOf("Local"), EngineOrder.chain("Auto", setOf("Local"), health))
        assertEquals(listOf("Gemini"), EngineOrder.chain("Auto", setOf("Gemini"), health))
        assertTrue(EngineOrder.chain("Auto", emptySet(), health).isEmpty())
    }

    @Test
    fun aFixedChoiceMeansExactlyThatEngine() {
        assertEquals(listOf("Gemini"), EngineOrder.chain("Gemini", all, health))
        assertTrue(EngineOrder.chain("Groq", setOf("Local"), health).isEmpty())
    }

    @Test
    fun unknownNamesFallBackToAuto() {
        assertEquals("Auto", EngineOrder.normalize("Whatever"))
        assertEquals("Google", EngineOrder.display("Gemini"))
    }

    @Test
    fun successClearsTheCooldown() {
        health.markFailed("Groq", Failure.Timeout)
        assertTrue(health.coolingMs("Groq") > 0)
        health.markOk("Groq")
        assertEquals(0L, health.coolingMs("Groq"))
    }

    @Test
    fun rateLimitWaitIsBounded() {
        health.markFailed("Groq", Failure.RateLimit, 10_000_000)
        assertEquals(300_000L, health.coolingMs("Groq"))
        health.markFailed("Gemini", Failure.RateLimit, 1)
        assertEquals(5_000L, health.coolingMs("Gemini"))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the request-body tuning that keeps AI latency down. Measured against the live API on one
 * 13.6 s clip: the shipped default voice model answered in 5.9–13.8 s when left to reason and
 * 2.7–8.9 s with reasoning suppressed, and `~google/gemini-pro-latest` took up to 226 s. A
 * regression here is invisible except as "the keyboard got slow again", so it is asserted.
 */
@RunWith(RobolectricTestRunner::class)
class ChatTuningTest {

    private fun client(
        provider: AiProvider = AiProvider.OPENROUTER,
        disableReasoning: Boolean = true,
        model: String = "mistralai/voxtral-small-24b-2507",
    ) = OpenRouterClient(
        apiKey = "test-key",
        model = model,
        systemPrompt = "prompt",
        runtimeInstruction = null,
        provider = provider,
        useZeroDataRetention = true,
        disableReasoning = disableReasoning,
    )

    @Test
    fun openRouterTextRequestAsksTheModelNotToReason() {
        val body = client().buildTextRequestBody("fix this", enforceZdr = true)
        assertFalse(body.getJSONObject("reasoning").getBoolean("enabled"))
    }

    @Test
    fun openRouterAudioRequestAsksTheModelNotToReason() {
        val (prefix, suffix) = client().buildRequestEnvelope(enforceZdr = true)
        assertTrue("\"reasoning\"" in prefix + suffix, "audio envelope must carry the reasoning flag")
        assertTrue("\"enabled\":false" in (prefix + suffix).replace(" ", ""))
    }

    @Test
    fun tuningSurvivesTheNonZdrFallbackAttempt() {
        // Regression guard: folding this into putProviderPreferences, which returns early when ZDR
        // is off, would silently drop the flag on exactly the retry the ZDR fallback performs.
        val body = client().buildTextRequestBody("fix this", enforceZdr = false)
        assertFalse(body.has("provider"))
        assertFalse(body.getJSONObject("reasoning").getBoolean("enabled"))
    }

    @Test
    fun payPerQNeverReceivesTheReasoningFlag() {
        val body = client(provider = AiProvider.PAYPERQ).buildTextRequestBody("fix this", enforceZdr = false)
        assertFalse(body.has("reasoning"), "PayPerQ was never probed for this parameter")
    }

    @Test
    fun allowingReasoningOmitsTheFlagEntirely() {
        val body = client(disableReasoning = false).buildTextRequestBody("fix this", enforceZdr = true)
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun temperatureIsNeverSentOnChatRequests() {
        // The shipped default text model rejects a non-default temperature with a 400, which is
        // not retryable here — it would take Text Fix down entirely.
        assertFalse(client().buildTextRequestBody("fix this", enforceZdr = true).has("temperature"))
        val (prefix, suffix) = client().buildRequestEnvelope(enforceZdr = true)
        assertFalse("\"temperature\"" in prefix + suffix)
    }

    @Test
    fun onlyAnExplicitReasoningComplaintTriggersTheUntunedRetry() {
        assertTrue(isReasoningControlRejected(400, "reasoning cannot be disabled for this model"))
        assertTrue(isReasoningControlRejected(400, "Provider does not support reasoning: false"))
        assertTrue(isReasoningControlRejected(422, "thinking is mandatory on this route"))
        // A generic 400 must not be read as a reasoning complaint, or a genuinely malformed
        // request would be retried untuned and its real error hidden from the user.
        assertFalse(isReasoningControlRejected(400, "model not found"))
        assertFalse(isReasoningControlRejected(400, "invalid api key"))
        assertFalse(isReasoningControlRejected(500, "reasoning cannot be disabled"))
        // Mentioning reasoning without complaining about it is not a rejection either.
        assertFalse(isReasoningControlRejected(400, "reasoning tokens exceeded your limit"))
    }

    @Test
    fun routeFactsExpireSoATransientOutageIsNotRememberedForever() {
        val model = "test/route-fact-model"
        assertFalse(isZdrRouteKnownUnavailable(model))
        markZdrRouteUnavailable(model)
        assertTrue(isZdrRouteKnownUnavailable(model))

        assertFalse(isReasoningControlKnownRejected(model))
        markReasoningControlRejected(model)
        assertTrue(isReasoningControlKnownRejected(model))
    }

    @Test
    fun connectTimeoutStaysShortEnoughToFailFast() {
        // Three attempts at the old 15 s made a black-holed route cost 46 s of dead waiting.
        assertEquals(8_000, OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS)
    }
}

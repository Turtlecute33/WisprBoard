// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Response bodies here are trimmed copies of real PayPerQ replies. The reasoning-only case is the
 * one that made a transcription fail outright: HTTP 200, `finish_reason: stop`, and the finished
 * text delivered under `reasoning` while `content` is null. It arrived on 3 of 10 identical
 * requests to one model, which is why the parser has to classify it as retryable rather than fatal.
 */
@RunWith(RobolectricTestRunner::class)
class ParseResponseTest {
    private fun client(provider: AiProvider = AiProvider.PAYPERQ) = OpenRouterClient(
        apiKey = "test-key",
        model = "xiaomi/mimo-v2.5",
        systemPrompt = "prompt",
        runtimeInstruction = null,
        provider = provider,
    )

    private fun assertStatus(expected: Int, body: String, parse: (String) -> String) {
        try {
            val text = parse(body)
            fail("expected status $expected, but parsing returned \"$text\"")
        } catch (e: OpenRouterException) {
            assertEquals(expected, e.statusCode, e.message)
        }
    }

    @Test
    fun contentIsReadFromAWellFormedReply() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Hello there."},"finish_reason":"stop"}]}"""
        assertEquals("Hello there.", client().parseContent(body))
    }

    @Test
    fun replyWithTextOnlyInReasoningIsRetryable() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,
            "reasoning":"This is a longer recording used to measure end-to-end latency.",
            "refusal":null},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":303,"completion_tokens":101}}
        """.trimIndent()
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, body) { client().parseContent(it) }
    }

    @Test
    fun reasoningIsNeverUsedAsTheAnswer() {
        // The same field carries the model's scratchpad just as often as its answer, so nothing
        // in it may reach the user's text field.
        val scratchpad = "First, the user has provided a system instruction: transcribe the audio."
        val body = """{"choices":[{"message":{"role":"assistant","content":"","reasoning":"$scratchpad"}}]}"""
        try {
            val text = client().parseContent(body)
            assertFalse(scratchpad in text, "reasoning text leaked into the result")
            fail("empty content should not parse")
        } catch (e: OpenRouterException) {
            assertFalse(scratchpad in (e.message ?: ""), "reasoning text leaked into the error")
        }
    }

    @Test
    fun errorEnvelopeServedWithHttp200IsRetryable() {
        val body = """{"error":{"message":"Provider returned error","code":400},"user_id":"user_123"}"""
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, body) { client().parseContent(it) }
    }

    @Test
    fun errorEnvelopeWithAClearStatusIsNotRetried() {
        // PayPerQ answers HTTP 200 with the real status in the envelope. Three requests (for a
        // dictation, three uploads) cannot change a bad key, no credit or a refusal.
        for (code in listOf(401, 402, 403, 404)) {
            val body = """{"error":{"message":"Policy Violation: this user has been blocked","code":$code}}"""
            assertStatus(code, body) { client().parseContent(it) }
            assertStatus(code, body) { client().parseTranscriptionContent(it) }
            assertFalse(OpenRouterClient.isRetryableStatus(code))
        }
        // A rate limit is tried again, but with the backoff of its status.
        assertStatus(429, """{"error":{"message":"Too many requests","code":429}}""") { client().parseContent(it) }
    }

    @Test
    fun reasoningRefusalInAnEnvelopeKeepsItsStatus() {
        // The request then goes again without the switch (see withReasoningFallback).
        val body = """{"error":{"message":"Reasoning is mandatory for this endpoint and cannot be disabled.","code":400}}"""
        try {
            client().parseContent(body)
            fail("an error envelope should not parse")
        } catch (e: OpenRouterException) {
            assertEquals(400, e.statusCode)
            assertTrue(isReasoningControlRejected(e.statusCode, e.errorBody))
        }
    }

    @Test
    fun aCutTextAnswerIsRefusedAndNotRetried() {
        // The result replaces the user's text, so a cut answer deletes the end of it.
        val cut = """{"choices":[{"message":{"content":"Hello th"},"finish_reason":"length"}]}"""
        assertStatus(OpenRouterClient.STATUS_ANSWER_CUT, cut) { client().parseContent(it, replacesText = true) }
        val filtered = """{"choices":[{"message":{"content":"Hello"},"finish_reason":"content_filter"}]}"""
        assertStatus(OpenRouterClient.STATUS_CONTENT_FILTER, filtered) { client().parseContent(it, replacesText = true) }
        assertFalse(OpenRouterClient.isRetryableStatus(OpenRouterClient.STATUS_ANSWER_CUT))
        assertFalse(OpenRouterClient.isRetryableStatus(OpenRouterClient.STATUS_CONTENT_FILTER))
        // The start of a dictation is better than nothing, so a transcription is still typed.
        assertEquals("Hello th", client().parseContent(cut))
    }

    @Test
    fun anAnswerThatStoppedWithAnErrorIsRetried() {
        val body = """{"choices":[{"message":{"content":"Hello th"},"finish_reason":"error"}]}"""
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, body) { client().parseContent(it) }
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, body) { client().parseContent(it, replacesText = true) }
    }

    @Test
    fun cloudflareEdgeErrorsAreRetried() {
        for (status in 520..524) assertTrue(OpenRouterClient.isRetryableStatus(status), "status $status")
    }

    @Test
    fun malformedBodyIsRetryable() {
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, "<html>502 Bad Gateway</html>") {
            client().parseContent(it)
        }
    }

    @Test
    fun everyUnusableReplyIsRetried() {
        assertTrue(OpenRouterClient.isRetryableStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE))
    }

    @Test
    fun transcriptionFallsBackToPayPerQsTranscriptionKey() {
        val body = """{"text":"","transcription":"Hello. This is a test."}"""
        assertEquals("Hello. This is a test.", client().parseTranscriptionContent(body))
    }

    @Test
    fun transcriptionPrefersTextWhenBothArePresent() {
        val body = """{"text":"Hello. This is a test.","transcription":"Hello. This is a test."}"""
        assertEquals("Hello. This is a test.", client().parseTranscriptionContent(body))
    }

    @Test
    fun emptyTranscriptMeansNoSpeechAndIsNotRetried() {
        // Silence reproduces this exactly; re-uploading the same clip would answer the same way
        // and only cost the user another request.
        assertStatus(OpenRouterClient.STATUS_NO_SPEECH, """{"text":"","transcription":""}""") {
            client().parseTranscriptionContent(it)
        }
        assertFalse(OpenRouterClient.isRetryableStatus(OpenRouterClient.STATUS_NO_SPEECH))
    }

    @Test
    fun plainTextTranscriptionBodyIsAccepted() {
        assertEquals("Hello. This is a test.", client().parseTranscriptionContent("Hello. This is a test.\n"))
    }
}

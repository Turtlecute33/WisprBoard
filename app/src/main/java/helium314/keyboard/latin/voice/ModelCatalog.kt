// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Single source of truth for the models the keyboard offers in Voice / STT / Text-Fix
 * dropdowns. Each entry encodes the slug, display name, pricing tier, and whether the
 * model supports OpenRouter ZDR routing and prompt caching. The same data drives:
 *
 *  - the dropdown labels and pills the user sees,
 *  - the slug allow-lists that survive provider-switch fallback in [AiProvider],
 *  - the per-request decision in OpenRouterClient about whether to enforce `zdr: true`.
 *
 * Note on caching: OpenRouterClient attaches a `cache_control` breakpoint to the system prompt
 * of *every* chat request regardless of catalog membership (providers that need it use it;
 * providers that cache implicitly ignore it). The `cache` flag therefore no longer gates the
 * request — it only drives the verified-"CACHE" pill in the picker.
 *
 * The `zdr` flag means the same thing to a reader of the picker on both providers — this model
 * carries a no-retention promise — but it is established differently. On OpenRouter it is verified
 * against `/api/v1/endpoints/zdr` and requests additionally ask for ZDR routing, falling back to
 * standard routing when none is available. PayPerQ has no per-request equivalent: it publishes a
 * `privacyLevel` per model instead, so there the badge marks the models it rates `zdr` and the
 * user's choice of model is the whole of the enforcement.
 */
internal enum class PricingTier { FREE, CHEAP, MEDIUM, EXPENSIVE }

internal data class ModelEntry(
    val slug: String,
    val displayName: String,
    val tier: PricingTier,
    val zdr: Boolean = false,
    val cache: Boolean = false,
)

internal object ModelCatalog {
    // Ordered fastest-first from timings measured against the live API on one 13.6 s clip, 2–3
    // runs each. Chat models that route audio through a reasoning pass are slow and wildly
    // variable, so the purpose-built audio models lead. The `zdr` flags below were reconciled
    // against OpenRouter's own `/api/v1/endpoints/zdr` list rather than left as hand-maintained
    // guesses — three of them were wrong in the direction that under-reports privacy.
    val OPENROUTER_VOICE: List<ModelEntry> = listOf(
        // 1.3–2.2 s. Present on the ZDR list; previously flagged non-ZDR here by mistake.
        ModelEntry("mistralai/voxtral-small-24b-2507", "Voxtral Small 24B", PricingTier.CHEAP, zdr = true, cache = true),
        // 2.7–8.9 s with reasoning suppressed, 5.9–13.8 s without.
        ModelEntry("~google/gemini-flash-latest", "Gemini Flash", PricingTier.CHEAP, zdr = true, cache = true),
        // 2.6–5.7 s tuned, but measured up to 226 s when left to reason. Keep reasoning off.
        ModelEntry("~google/gemini-pro-latest", "Gemini Pro", PricingTier.MEDIUM, zdr = true, cache = true),
        // A reasoning model: 7.4–10.5 s tuned, 89–171 s untuned, and it strands the answer in
        // `reasoning` often enough that ModelCatalog already dropped it from the PayPerQ list.
        ModelEntry("xiaomi/mimo-v2.5", "MiMo V2.5", PricingTier.CHEAP, zdr = true, cache = true),
        ModelEntry(
            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
            "Nemotron Nano Omni",
            PricingTier.FREE,
        ),
    )

    val OPENROUTER_STT: List<ModelEntry> = listOf(
        // 1.2–2.4 s and the most accurate of the four on the measured clip.
        ModelEntry("openai/whisper-large-v3-turbo", "Whisper Large V3 Turbo", PricingTier.CHEAP, zdr = true),
        // 1.9–3.1 s.
        ModelEntry("openai/whisper-large-v3", "Whisper Large V3", PricingTier.MEDIUM, zdr = true),
        // 4.0–4.3 s, and it misheard "OAuth" as "off" where every other entry got it right.
        // Was the default until 7.9.0.
        ModelEntry("google/chirp-3", "Chirp 3", PricingTier.CHEAP, zdr = true),
        // 1.7–2.7 s but genuinely absent from the ZDR list, unlike its siblings.
        ModelEntry("openai/whisper-1", "Whisper 1", PricingTier.CHEAP),
    )

    // Also fastest-first, measured on one Text-Fix request over 2 runs with reasoning suppressed.
    val OPENROUTER_TEXT_FIX: List<ModelEntry> = listOf(
        // 1.3–1.4 s, and it does not reason on this task either way.
        ModelEntry("~openai/gpt-mini-latest", "GPT Mini", PricingTier.MEDIUM, zdr = true, cache = true),
        // 1.3–1.5 s, likewise reasoning-neutral.
        ModelEntry("~anthropic/claude-haiku-latest", "Claude Haiku", PricingTier.MEDIUM, zdr = true, cache = true),
        // 1.2–1.6 s tuned, 2.9–3.6 s untuned. Present on the ZDR list.
        ModelEntry("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", PricingTier.CHEAP, zdr = true, cache = true),
        // 1.1–1.7 s tuned, 3.2–4.8 s untuned. Present on the ZDR list; previously flagged
        // non-ZDR here by mistake.
        ModelEntry("x-ai/grok-4.3", "Grok 4.3", PricingTier.MEDIUM, zdr = true, cache = true),
        // 2.7–3.6 s tuned, 5.2–6.1 s untuned — the slowest of the five for plain copy-editing.
        ModelEntry("~google/gemini-flash-latest", "Gemini Flash", PricingTier.CHEAP, zdr = true, cache = true),
    )

    // PayPerQ has its own model namespace (api.ppq.ai/v1/models) and does not honor OpenRouter's
    // `:free` tier or the per-request `provider.zdr` preference. It does publish a `privacyLevel`
    // per model — `zdr`, `e2e`, or `anon` — and that is what the ZDR badge reflects here: on
    // PayPerQ, zero data retention is a property of the model you pick, not a flag you send. The
    // list is explicit rather than derived from the OpenRouter one because membership differs:
    // every entry below was confirmed to answer on PayPerQ's own endpoints, and the flags come
    // from PayPerQ's catalog plus observed `usage.prompt_tokens_details.cached_tokens`.
    val PAYPERQ_VOICE: List<ModelEntry> = listOf(
        ModelEntry("mistralai/voxtral-small-24b-2507", "Voxtral Small 24B", PricingTier.CHEAP, cache = true),
        ModelEntry("thinkingmachines/inkling-small", "Inkling Small", PricingTier.CHEAP, zdr = true, cache = true),
        ModelEntry("~google/gemini-flash-latest", "Gemini Flash", PricingTier.CHEAP),
        ModelEntry("~google/gemini-pro-latest", "Gemini Pro", PricingTier.MEDIUM),
        // Deliberately absent, both measured against PayPerQ over 10 identical requests:
        //  - nvidia/nemotron-3-nano-omni-30b-a3b-reasoning: listed in the catalog, but every
        //    request answers 404 "No endpoints found".
        //  - xiaomi/mimo-v2.5: 3 in 10 replies came back HTTP 200 with `content: null` and the
        //    finished transcription stranded in `reasoning` instead. Inkling Small covers the same
        //    zero-retention slot without the defect and is roughly three times faster.
    )

    /**
     * PayPerQ's dedicated transcription endpoint. It routes by capability rather than by the `model`
     * field — the field is accepted and ignored — so this offers one entry instead of a menu that
     * would imply a choice the API does not make.
     */
    val PAYPERQ_STT: List<ModelEntry> = listOf(
        ModelEntry("nova-3", "Fast transcription", PricingTier.CHEAP),
    )

    val PAYPERQ_TEXT_FIX: List<ModelEntry> = listOf(
        ModelEntry("~openai/gpt-mini-latest", "GPT Mini", PricingTier.MEDIUM),
        ModelEntry("x-ai/grok-4.3", "Grok 4.3", PricingTier.MEDIUM),
        ModelEntry("~anthropic/claude-haiku-latest", "Claude Haiku", PricingTier.MEDIUM),
        ModelEntry("~google/gemini-flash-latest", "Gemini Flash", PricingTier.CHEAP),
        ModelEntry("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", PricingTier.CHEAP, zdr = true, cache = true),
    )
}

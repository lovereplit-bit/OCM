package dev.opencode.mobile

/**
 * Turns what the server says about a failed request into the honest reason, so the app can name the right fix
 * instead of a generic "quota error". The texts mirror what OpenCode Zen actually sends (verified live):
 * `FreeUsageLimitError`, `RegionError` and the personal-key limits.
 */
object QuotaSignal {
    enum class Reason {
        /** The free lane is spent: pick another free model or connect a personal key. */
        FREE_LIMIT,

        /** The personal API key of this profile has hit its own limit. */
        PERSONAL_LIMIT,

        /** The model is not served to this country at all - no key or account changes that. */
        REGION_BLOCKED,

        /** A plain rate limit: wait, or pick another model. */
        RATE_LIMIT,

        /** Something else; show it as it is. */
        OTHER,
    }

    /**
     * Reads a server error (the message and, when known, the session-error type such as `provider.free-limit`)
     * into a reason. [hasKey] separates a personal key's allowance from the free lane: both surface as
     * free-usage errors, but they mean different things and have different fixes.
     */
    fun decode(message: String, type: String? = null, hasKey: Boolean): Reason {
        val text = ((type ?: "") + " " + message).lowercase()

        if ("regionerror" in text || "not available in your country" in text) return Reason.REGION_BLOCKED

        if ("freeusagelimit" in text || "free usage limit" in text || "free-limit" in text || "free usage" in text) {
            return if (hasKey) Reason.PERSONAL_LIMIT else Reason.FREE_LIMIT
        }

        if ("rate limit" in text || "ratelimit" in text || "429" in text || "too many requests" in text) return Reason.RATE_LIMIT

        if ("quota" in text || "usage limit" in text || "лимит" in text || "квот" in text) {
            return if (hasKey) Reason.PERSONAL_LIMIT else Reason.FREE_LIMIT
        }

        return Reason.OTHER
    }

    /** The honest one-liner for [reason]: what happened and what actually helps. Shown under the server's own text. */
    fun explanation(reason: Reason): String = when (reason) {
        Reason.FREE_LIMIT -> tr(
            "Лимит бесплатной модели Zen исчерпан. Переключитесь на «Free (no key)» — GPT-OSS 20B: эта модель работает без ключа и без квоты Zen. Или подключите свой API-ключ в меню — тогда запросы пойдут под вашим личным лимитом.",
            "The free Zen model's limit is spent. Switch to \"Free (no key)\" — GPT-OSS 20B: it runs without a key and without the Zen quota. Or connect your own API key from the menu, so requests run under your personal limit.",
        )
        Reason.PERSONAL_LIMIT -> tr(
            "Это лимит вашего API-ключа. Подождите сброса или пополните план в OpenCode.",
            "This is your API key's limit. Wait for the reset or top up your OpenCode plan.",
        )
        Reason.REGION_BLOCKED -> tr(
            "Модель не обслуживается в вашей стране — это ограничение сервера, ключ его не снимает. Выберите другую модель.",
            "The model is not served in your country — a server-side restriction no key removes. Pick another model.",
        )
        Reason.RATE_LIMIT -> tr(
            "Слишком много запросов подряд. Подождите немного и попробуйте снова, либо выберите другую модель.",
            "Too many requests in a row. Wait a little and try again, or pick another model.",
        )
        Reason.OTHER -> tr(
            "Это сообщение сервера; приложение показывает его как есть.",
            "This is the server's own message; the app shows it as it is.",
        )
    }
}

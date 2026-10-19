package com.symptree.messaging.i18n

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.symptree.messaging.i18n.MessageCatalog")

/**
 * FR-VWRNDR — the **single** view-layer catalog for the whole bot frontend.
 *
 * The wire from `core` carries only semantic data; this module owns every view
 * concern — chrome strings **and their keys**. This one catalog backs **both**
 * paths as one disjoint key set: the ingress chrome (welcome, symptom prompts,
 * reprompts, conclusion CTAs, language switch, booking confirm) and the
 * reminder/feedback/booking-outgoing keys (`bot.reminder.*`, `bot.feedback.*`).
 * It unifies the former `OutgoingMessageCatalog` (Variant A) into the same class.
 *
 * Flat `Map<String, String>` bundles per language, dot-notation keys, `{name}`
 * interpolation, missing-key → return the key as a visible marker.
 */
class MessageCatalog(
    private val bundles: Map<String, Map<String, String>>,
) {
    /**
     * Look up [key] in the [lang] bundle (falling back to [FALLBACK_LANG]);
     * interpolate [params] (`{name}` → `params["name"]`). Missing-key behavior:
     * log WARN and return the key itself rather than throwing.
     */
    fun get(
        key: String,
        lang: String,
        params: Map<String, String>? = null,
    ): String {
        val bundle = bundles[lang] ?: bundles[FALLBACK_LANG]
        val raw = bundle?.get(key)
        if (raw == null) {
            logger.warn("missing i18n key '{}' for lang '{}'", key, lang)
            return interpolate(key, params)
        }
        return interpolate(raw, params)
    }

    /** The language codes this catalog has a bundle for (for parity/coverage tests). */
    fun languages(): Set<String> = bundles.keys

    /** Whether [key] is present in the [lang] bundle (no fallback, no interpolation). */
    fun has(key: String, lang: String): Boolean = bundles[lang]?.containsKey(key) == true

    /** The full key set of the [lang] bundle (for parity tests). */
    fun keys(lang: String): Set<String> = bundles[lang]?.keys ?: emptySet()

    private fun interpolate(template: String, params: Map<String, String>?): String {
        if (params.isNullOrEmpty()) return template
        var out = template
        for ((k, v) in params) {
            out = out.replace("{$k}", v)
        }
        return out
    }

    companion object {
        private const val FALLBACK_LANG = "ru"

        private val SUPPORTED_LANGS = listOf("ru", "kk")

        private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

        /**
         * Load every `i18n/<lang>.yaml` resource present on the classpath for each
         * supported language. Returns an immutable catalog.
         */
        fun loadFromClasspath(): MessageCatalog {
            val loaded = mutableMapOf<String, Map<String, String>>()
            val loader = MessageCatalog::class.java.classLoader
            for (lang in SUPPORTED_LANGS) {
                val resource = "i18n/$lang.yaml"
                val stream = loader.getResourceAsStream(resource)
                if (stream == null) {
                    logger.warn("i18n bundle '{}' not found on classpath; skipping", resource)
                    continue
                }
                val yamlText = stream.use { it.readBytes().decodeToString() }
                val bundle = Yaml.default.decodeFromString(mapSerializer, yamlText)
                loaded[lang] = bundle
                logger.info("loaded i18n bundle '{}' ({} keys)", resource, bundle.size)
            }
            return MessageCatalog(loaded.toMap())
        }
    }
}

package com.symptree.core.symptomizer

/**
 * Reasons an [Algorithm] tree can fail ingestion validation.
 *
 * Subtypes:
 *  - [MissingEntryPoint] — `entry_point` does not refer to any node in `nodes`.
 *  - [DanglingNext] — a [QuestionNode] option's `next` does not refer to any node.
 *  - [MissingLanguage] — a text-bearing field is missing a required language key.
 */
sealed interface ValidationReason {
    object MissingEntryPoint : ValidationReason
    data class DanglingNext(val optionValue: String, val missingNext: String) : ValidationReason
    data class MissingLanguage(val field: String, val lang: String) : ValidationReason
}

/**
 * Thrown by [AlgorithmIngestionValidator.validate] when a tree fails one of
 * the structural / i18n invariants. The offending node is named via [nodeId];
 * for [ValidationReason.MissingEntryPoint] it is the unresolved entry_point
 * string itself.
 */
class AlgorithmValidationException(
    val nodeId: String,
    val reason: ValidationReason,
) : RuntimeException("validation failed at node '$nodeId': $reason")

/**
 * Validates an [Algorithm] tree against the AC-KT4697.3 invariants. The set
 * of required languages is supplied by the caller (typically the union of
 * the destination clinic's `allowed_languages`) — never inferred from the
 * tree itself.
 *
 * Validation order:
 *   1. `entry_point` resolves to a node in `nodes`.
 *   2. Every [QuestionNode] option's `next` resolves to a node.
 *   3. Every text-bearing field has an entry for every required language.
 *
 * Fails fast on the first violation by throwing [AlgorithmValidationException].
 */
object AlgorithmIngestionValidator {
    fun validate(algorithm: Algorithm, requiredLanguages: Set<String>) {
        // (a) entry_point must exist
        if (!algorithm.nodes.containsKey(algorithm.entryPoint)) {
            throw AlgorithmValidationException(
                nodeId = algorithm.entryPoint,
                reason = ValidationReason.MissingEntryPoint,
            )
        }

        // (b) every question option's next must resolve
        for ((nodeId, node) in algorithm.nodes) {
            if (node is QuestionNode) {
                for (option in node.options) {
                    if (!algorithm.nodes.containsKey(option.next)) {
                        throw AlgorithmValidationException(
                            nodeId = nodeId,
                            reason = ValidationReason.DanglingNext(
                                optionValue = option.value,
                                missingNext = option.next,
                            ),
                        )
                    }
                }
            }
        }

        // (c) every text-bearing field must cover every required language
        // Metadata name lives at algorithm scope, but is reported with field="name".
        checkLanguages(
            nodeId = algorithm.entryPoint,
            field = "name",
            value = algorithm.metadata.name,
            requiredLanguages = requiredLanguages,
        )

        for ((nodeId, node) in algorithm.nodes) {
            when (node) {
                is QuestionNode -> {
                    checkLanguages(nodeId, "text", node.text, requiredLanguages)
                    for (option in node.options) {
                        checkLanguages(
                            nodeId = nodeId,
                            field = "options[${option.value}].label",
                            value = option.label,
                            requiredLanguages = requiredLanguages,
                        )
                    }
                }
                is ConclusionNode -> {
                    checkLanguages(nodeId, "diagnosis", node.diagnosis, requiredLanguages)
                    checkLanguages(nodeId, "action", node.action, requiredLanguages)
                    for ((key, prep) in node.preparation) {
                        checkLanguages(
                            nodeId = nodeId,
                            field = "preparation.$key",
                            value = prep,
                            requiredLanguages = requiredLanguages,
                        )
                    }
                }
            }
        }
    }

    private fun checkLanguages(
        nodeId: String,
        field: String,
        value: Map<String, String>,
        requiredLanguages: Set<String>,
    ) {
        for (lang in requiredLanguages) {
            if (!value.containsKey(lang)) {
                throw AlgorithmValidationException(
                    nodeId = nodeId,
                    reason = ValidationReason.MissingLanguage(field = field, lang = lang),
                )
            }
        }
    }
}

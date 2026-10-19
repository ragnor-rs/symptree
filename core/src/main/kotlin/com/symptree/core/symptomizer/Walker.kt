package com.symptree.core.symptomizer

/**
 * Pure deterministic walker over a symptomizer algorithm tree.
 *
 * Stub for TDD: every public method throws so the failing-test runner
 * sees runtime failures (RED) rather than compile errors.
 */
sealed interface WalkResult {
    data class NextQuestion(val node: QuestionNode) : WalkResult
    data class Conclusion(val node: ConclusionNode) : WalkResult
}

class IllegalAlgorithmStateException(val nodeId: String) :
    RuntimeException("illegal algorithm state at node '$nodeId'")

class InvalidOptionException(
    val nodeId: String,
    val optionValue: String,
    val availableOptionValues: Set<String>,
) : RuntimeException(
    "invalid option '$optionValue' at node '$nodeId' (available: $availableOptionValues)",
)

object Walker {
    fun walk(algorithm: Algorithm, currentNodeId: String, pickedOptionValue: String): WalkResult {
        val current = algorithm.nodes[currentNodeId]
            ?: throw IllegalAlgorithmStateException(currentNodeId)
        val question = current as? QuestionNode
            ?: throw IllegalAlgorithmStateException(currentNodeId)
        val option = question.options.firstOrNull { it.value == pickedOptionValue }
            ?: throw InvalidOptionException(
                nodeId = currentNodeId,
                optionValue = pickedOptionValue,
                availableOptionValues = question.options.map { it.value }.toSet(),
            )
        val next = algorithm.nodes[option.next]
            ?: throw IllegalAlgorithmStateException(option.next)
        return when (next) {
            is QuestionNode -> WalkResult.NextQuestion(next)
            is ConclusionNode -> WalkResult.Conclusion(next)
        }
    }

    fun entry(algorithm: Algorithm): QuestionNode {
        val node = algorithm.nodes[algorithm.entryPoint]
            ?: throw IllegalAlgorithmStateException(algorithm.entryPoint)
        return node as? QuestionNode
            ?: throw IllegalAlgorithmStateException(algorithm.entryPoint)
    }

    fun availableOptionValues(algorithm: Algorithm, currentNodeId: String): Set<String> {
        val node = algorithm.nodes[currentNodeId]
            ?: throw IllegalAlgorithmStateException(currentNodeId)
        val question = node as? QuestionNode
            ?: throw IllegalAlgorithmStateException(currentNodeId)
        return question.options.map { it.value }.toSet()
    }
}

package com.symptree.core.symptomizer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Multilingual symptomizer algorithm — top-level container parsed from the
 * JSON schema in technical-spec.md § 2.2.
 *
 * Node IDs in the JSON live as map keys under `nodes.<id>`; they are not
 * duplicated inside each node object. We re-inject the key into each
 * `Node.id` field via a custom serializer wrapper so downstream code can
 * carry the id around without threading the map key everywhere.
 */
@Serializable(with = AlgorithmSerializer::class)
data class Algorithm(
    @SerialName("algorithm_id") val algorithmId: String,
    val version: String,
    val metadata: AlgorithmMetadata,
    @SerialName("entry_point") val entryPoint: String,
    val nodes: Map<String, Node>,
)

/**
 * Internal wire representation: same shape as `Algorithm` but without the
 * custom serializer — used to delegate the actual JSON parsing.
 */
@Serializable
internal data class AlgorithmWire(
    @SerialName("algorithm_id") val algorithmId: String,
    val version: String,
    val metadata: AlgorithmMetadata,
    @SerialName("entry_point") val entryPoint: String,
    val nodes: Map<String, Node>,
)

/**
 * Custom serializer that copies each node-map key into the corresponding
 * `Node.id` field after deserialization (and strips it back to the empty
 * default on encode, since the wire format does not carry it).
 */
object AlgorithmSerializer : KSerializer<Algorithm> {
    private val delegate = AlgorithmWire.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Algorithm {
        val wire = delegate.deserialize(decoder)
        val nodesWithIds = wire.nodes.mapValues { (id, node) ->
            when (node) {
                is QuestionNode -> node.copy(id = id)
                is ConclusionNode -> node.copy(id = id)
            }
        }
        return Algorithm(
            algorithmId = wire.algorithmId,
            version = wire.version,
            metadata = wire.metadata,
            entryPoint = wire.entryPoint,
            nodes = nodesWithIds,
        )
    }

    override fun serialize(encoder: Encoder, value: Algorithm) {
        delegate.serialize(
            encoder,
            AlgorithmWire(
                algorithmId = value.algorithmId,
                version = value.version,
                metadata = value.metadata,
                entryPoint = value.entryPoint,
                nodes = value.nodes,
            ),
        )
    }
}

/**
 * Shared, pre-configured Json instance for Algorithm parsing.
 *
 * - `classDiscriminator = "type"` matches the technical-spec § 2.2 wire format where
 *   each node object carries `"type": "question" | "conclusion"`.
 * - `ignoreUnknownKeys = true` keeps us forward-compatible with future
 *   optional fields landing in the algorithm schema.
 */
object AlgorithmJson {
    val instance: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }
}

@Serializable
data class AlgorithmMetadata(
    val name: Map<String, String>,
    val category: String,
    @SerialName("age_group") val ageGroup: String,
    @SerialName("estimated_time_minutes") val estimatedTimeMinutes: Int,
)

@Serializable
sealed interface Node {
    val id: String
}

@Serializable
@SerialName("question")
data class QuestionNode(
    override val id: String = "",
    val text: Map<String, String>,
    val options: List<Option>,
) : Node

@Serializable
@SerialName("conclusion")
data class ConclusionNode(
    override val id: String = "",
    val urgency: Urgency,
    val diagnosis: Map<String, String>,
    val action: Map<String, String>,
    val specialist: String?,
    val tests: List<String> = emptyList(),
    @SerialName("additional_tests") val additionalTests: List<String> = emptyList(),
    val preparation: Map<String, Map<String, String>> = emptyMap(),
    @SerialName("clinic_action") val clinicAction: ClinicAction,
    @SerialName("follow_up_days") val followUpDays: Int?,
) : Node

@Serializable
data class Option(
    val value: String,
    val label: Map<String, String>,
    val next: String,
)

@Serializable
enum class Urgency {
    @SerialName("emergency") EMERGENCY,
    @SerialName("urgent") URGENT,
    @SerialName("routine") ROUTINE,
    @SerialName("self_care") SELF_CARE,
}

@Serializable
enum class ClinicAction {
    @SerialName("book_appointment") BOOK_APPOINTMENT,
    @SerialName("bypass_appointment") BYPASS_APPOINTMENT,
    @SerialName("recommend_pharmacy") RECOMMEND_PHARMACY,
    @SerialName("recommend_self_care") RECOMMEND_SELF_CARE,
}

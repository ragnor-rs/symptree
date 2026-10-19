package com.symptree.messaging.port

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * FR-VWRNDR — frontend mirror of `core`'s semantic ingress outcome model
 * (`core/.../routes/IngressOutcomeDto.kt`). The two modules don't share code; the
 * `@SerialName` discriminators, field names, and enum tokens here must match
 * `core` byte-exactly. `IngressRenderer` consumes these and assembles the final
 * platform message from the [com.symptree.messaging.i18n.MessageCatalog].
 */
@Serializable
data class IngressResponse(
    val lang: String,
    val outcomes: List<IngressOutcome>,
)

/** Decodes/encodes [Instant] as epoch-milliseconds (mirrors `core`'s serializer). */
object InstantEpochSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeLong(value.toEpochMilli())

    override fun deserialize(decoder: Decoder): Instant =
        Instant.ofEpochMilli(decoder.decodeLong())
}

@Serializable
data class OptionDto(val value: String, val label: String)

@Serializable
data class TestItemDto(val name: String, val prep: String? = null)

@Serializable
data class ConclusionBodyDto(
    val urgency: Urgency,
    val diagnosis: String,
    val action: String,
    val tests: List<TestItemDto> = emptyList(),
)

@Serializable
enum class Urgency {
    @SerialName("emergency") EMERGENCY,
    @SerialName("urgent") URGENT,
    @SerialName("routine") ROUTINE,
    @SerialName("self_care") SELF_CARE,
    ;

    val wire: String get() = name.lowercase()
}

@Serializable
enum class ClinicAction {
    @SerialName("book_appointment") BOOK_APPOINTMENT,
    @SerialName("bypass_appointment") BYPASS_APPOINTMENT,
    @SerialName("recommend_pharmacy") RECOMMEND_PHARMACY,
    @SerialName("recommend_self_care") RECOMMEND_SELF_CARE,
}

@Serializable
enum class GoalKey {
    @SerialName("symptom_consultation") SYMPTOM_CONSULTATION,
    @SerialName("appointment_booking") APPOINTMENT_BOOKING,
    @SerialName("service_info") SERVICE_INFO,
    ;

    val wire: String get() = name.lowercase()
}

@Serializable
enum class GoalPromptKind {
    @SerialName("main_complaint") MAIN_COMPLAINT,
    @SerialName("booking_coming_soon") BOOKING_COMING_SOON,
    @SerialName("service_info_coming_soon") SERVICE_INFO_COMING_SOON,
    @SerialName("unknown_goal") UNKNOWN_GOAL,
}

@Serializable
enum class RejectReason {
    @SerialName("out_of_set") OUT_OF_SET,
    @SerialName("low_confidence") LOW_CONFIDENCE,
    @SerialName("null_pick") NULL_PICK,
    @SerialName("non_symptom_intent") NON_SYMPTOM_INTENT,
    ;

    val wire: String get() = name.lowercase()
}

@Serializable
sealed interface IngressOutcome {

    @Serializable
    @SerialName("Welcome")
    data class Welcome(val goals: List<GoalKey>) : IngressOutcome

    @Serializable
    @SerialName("LanguagePicker")
    data class LanguagePicker(val allowed: List<String>) : IngressOutcome

    @Serializable
    @SerialName("GoalPrompt")
    data class GoalPrompt(val prompt: GoalPromptKind) : IngressOutcome

    @Serializable
    @SerialName("NextQuestion")
    data class NextQuestion(
        val nodeId: String,
        val text: String,
        val options: List<OptionDto>,
    ) : IngressOutcome

    @Serializable
    @SerialName("Reprompt")
    data class Reprompt(
        val reason: RejectReason,
        val text: String? = null,
        val options: List<OptionDto> = emptyList(),
    ) : IngressOutcome

    @Serializable
    @SerialName("ButtonOnly")
    data class ButtonOnly(
        val nodeId: String,
        val text: String,
        val options: List<OptionDto>,
    ) : IngressOutcome

    @Serializable
    @SerialName("Conclusion")
    data class Conclusion(
        val body: ConclusionBodyDto,
        val clinicAction: ClinicAction,
    ) : IngressOutcome

    @Serializable
    @SerialName("ConfirmSlot")
    data class ConfirmSlot(
        val body: ConclusionBodyDto? = null,
        val doctorName: String,
        @Serializable(with = InstantEpochSerializer::class) val slotAt: Instant,
        @Serializable(with = InstantEpochSerializer::class) val slotEnd: Instant,
        val clinicZone: String,
    ) : IngressOutcome

    @Serializable
    @SerialName("NoSlots")
    data class NoSlots(val body: ConclusionBodyDto) : IngressOutcome

    @Serializable
    @SerialName("BookingConfirmed")
    data class BookingConfirmed(
        val doctorName: String,
        @Serializable(with = InstantEpochSerializer::class) val slotAt: Instant,
        val clinicZone: String,
    ) : IngressOutcome

    @Serializable
    @SerialName("BookingContactClinic")
    data object BookingContactClinic : IngressOutcome

    @Serializable
    @SerialName("NoAlgorithmMatch")
    data object NoAlgorithmMatch : IngressOutcome

    @Serializable
    @SerialName("LanguageChanged")
    data class LanguageChanged(val language: String) : IngressOutcome

    @Serializable
    @SerialName("LanguageNotAllowed")
    data class LanguageNotAllowed(val requested: String) : IngressOutcome
}

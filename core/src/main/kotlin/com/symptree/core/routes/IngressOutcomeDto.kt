package com.symptree.core.routes

import com.symptree.core.symptomizer.ClinicAction
import com.symptree.core.symptomizer.Urgency
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
 * FR-VWRNDR — semantic outcome DTOs for `POST /api/v1/clinics/{slug}/bot/ingress`.
 *
 * The wire is a backend↔frontend contract carrying **semantic data, never view
 * artifacts**. `core` emits *what happened* as typed, data-holding fields; the
 * frontend (`messaging`) decides *how to say it* using its own catalog. No
 * localization key, no catalog key, no finished chrome string, and no view
 * format crosses this boundary.
 *
 * The one piece of presentation `core` legitimately owns is **algorithm-tree
 * text** (question text, diagnosis, action, test names, option labels): it is
 * `core`-owned multilingual domain data, localized **by `core`** to the resolved
 * session language and carried here as concrete `String` fields (AC-VWRNDR.3).
 * Everything else — chrome, urgency icons, datetime formatting, menu layout — is
 * the frontend's concern.
 *
 * The response envelope also carries the resolved session [IngressResponseDto.lang]
 * so `messaging` renders its own chrome in the same language `core` localized the
 * tree text to.
 */
@Serializable
internal data class IngressResponseDto(
    val lang: String,
    val outcomes: List<IngressOutcomeDto>,
)

/** Encodes [Instant] as epoch-milliseconds on the wire (no extra dependency). */
internal object InstantEpochSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeLong(value.toEpochMilli())

    override fun deserialize(decoder: Decoder): Instant =
        Instant.ofEpochMilli(decoder.decodeLong())
}

/** Semantic option: a stable routing [value] plus an already-localized domain [label]. */
@Serializable
internal data class OptionDto(
    val value: String,
    val label: String,
)

/** One recommended test: its [name] and an optional localized [prep] note. */
@Serializable
internal data class TestItemDto(
    val name: String,
    val prep: String? = null,
)

/**
 * The domain body of a conclusion — urgency + diagnosis/action/tests as concrete
 * localized strings. Carries **no** clinic-action CTA and **no** chrome (icon,
 * "tests:" label, layout); the frontend assembles those.
 */
@Serializable
internal data class ConclusionBodyDto(
    val urgency: Urgency,
    val diagnosis: String,
    val action: String,
    val tests: List<TestItemDto> = emptyList(),
)

/** The clinic's enabled goals, offered on the welcome menu. */
@Serializable
internal enum class GoalKey {
    @SerialName("symptom_consultation") SYMPTOM_CONSULTATION,
    @SerialName("appointment_booking") APPOINTMENT_BOOKING,
    @SerialName("service_info") SERVICE_INFO,
}

/** Semantic discriminator for the reply to a goal selection. */
@Serializable
internal enum class GoalPromptKind {
    @SerialName("main_complaint") MAIN_COMPLAINT,
    @SerialName("booking_coming_soon") BOOKING_COMING_SOON,
    @SerialName("service_info_coming_soon") SERVICE_INFO_COMING_SOON,
    @SerialName("unknown_goal") UNKNOWN_GOAL,
}

/** Why the LLM option-mapping was rejected (the static-reprompt discriminator). */
@Serializable
internal enum class RejectReason {
    @SerialName("out_of_set") OUT_OF_SET,
    @SerialName("low_confidence") LOW_CONFIDENCE,
    @SerialName("null_pick") NULL_PICK,
    @SerialName("non_symptom_intent") NON_SYMPTOM_INTENT,
}

/**
 * One semantic outcome of a single ingress turn. The frontend maps each variant
 * to its own catalog keys, renders, and assembles the final platform message.
 */
@Serializable
internal sealed interface IngressOutcomeDto {

    /** Show the welcome text + a goal-pick menu for [goals]. */
    @Serializable
    @SerialName("Welcome")
    data class Welcome(val goals: List<GoalKey>) : IngressOutcomeDto

    /** Show the language picker for the clinic's [allowed] language codes. */
    @Serializable
    @SerialName("LanguagePicker")
    data class LanguagePicker(val allowed: List<String>) : IngressOutcomeDto

    /** Static reply to a goal selection, discriminated by [prompt]. */
    @Serializable
    @SerialName("GoalPrompt")
    data class GoalPrompt(val prompt: GoalPromptKind) : IngressOutcomeDto

    /** A new question node: localized [text] + labelled [options]. */
    @Serializable
    @SerialName("NextQuestion")
    data class NextQuestion(
        val nodeId: String,
        val text: String,
        val options: List<OptionDto>,
    ) : IngressOutcomeDto

    /**
     * The LLM option-mapping was rejected. [reason] drives the frontend's static
     * reprompt; [text] is the current node's localized question (null on pre-pick
     * rejections, where the frontend falls back to the welcome menu); [options]
     * is the node's option list (empty pre-pick).
     */
    @Serializable
    @SerialName("Reprompt")
    data class Reprompt(
        val reason: RejectReason,
        val text: String? = null,
        val options: List<OptionDto> = emptyList(),
    ) : IngressOutcomeDto

    /** LLM upstream unavailable: re-show the node ([text] + [options]) with a notice. */
    @Serializable
    @SerialName("ButtonOnly")
    data class ButtonOnly(
        val nodeId: String,
        val text: String,
        val options: List<OptionDto>,
    ) : IngressOutcomeDto

    /** Terminal conclusion: domain [body] + the [clinicAction] surface. */
    @Serializable
    @SerialName("Conclusion")
    data class Conclusion(
        val body: ConclusionBodyDto,
        val clinicAction: ClinicAction,
    ) : IngressOutcomeDto

    /**
     * Auto-booking slot proposal: optional conclusion [body] (the first
     * proposal) + the proposed slot. The frontend formats [slotAt] in
     * [clinicZone] and shows confirm/decline chrome.
     */
    @Serializable
    @SerialName("ConfirmSlot")
    data class ConfirmSlot(
        val body: ConclusionBodyDto? = null,
        val doctorName: String,
        @Serializable(with = InstantEpochSerializer::class) val slotAt: Instant,
        @Serializable(with = InstantEpochSerializer::class) val slotEnd: Instant,
        val clinicZone: String,
    ) : IngressOutcomeDto

    /** Booking conclusion reached but no slot available: conclusion [body] + a notice. */
    @Serializable
    @SerialName("NoSlots")
    data class NoSlots(val body: ConclusionBodyDto) : IngressOutcomeDto

    /** The booking landed. The frontend formats [slotAt] in [clinicZone]. */
    @Serializable
    @SerialName("BookingConfirmed")
    data class BookingConfirmed(
        val doctorName: String,
        @Serializable(with = InstantEpochSerializer::class) val slotAt: Instant,
        val clinicZone: String,
    ) : IngressOutcomeDto

    /** The patient declined past the cap (or lost a race): contact-clinic handoff. */
    @Serializable
    @SerialName("BookingContactClinic")
    data object BookingContactClinic : IngressOutcomeDto

    /** No enabled algorithm matched the complaint: notice + re-offer the welcome menu. */
    @Serializable
    @SerialName("NoAlgorithmMatch")
    data object NoAlgorithmMatch : IngressOutcomeDto

    /** The session language was switched to [language] (a language code). */
    @Serializable
    @SerialName("LanguageChanged")
    data class LanguageChanged(val language: String) : IngressOutcomeDto

    /** The [requested] language (a language code) is not allowed for this clinic. */
    @Serializable
    @SerialName("LanguageNotAllowed")
    data class LanguageNotAllowed(val requested: String) : IngressOutcomeDto
}

package com.symptree.core.routes.admin

import com.symptree.core.auth.ApiKeyHasher
import com.symptree.core.auth.TokenService
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.model.NewClinicUser
import com.symptree.core.symptomizer.Algorithm
import com.symptree.core.symptomizer.AlgorithmMetadata
import com.symptree.core.symptomizer.ClinicAction
import com.symptree.core.symptomizer.ConclusionNode
import com.symptree.core.symptomizer.Option
import com.symptree.core.symptomizer.QuestionNode
import com.symptree.core.symptomizer.Urgency
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Shared seeding helpers for the admin-route Ktor tests: create a clinic
 * user with a known password and mint a bearer token for it.
 */
object AdminFixtures {

    val FIXED_NOW: Instant = Instant.parse("2026-06-12T10:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

    const val SIGNING_SECRET: String = "admin-test-signing-secret"

    fun tokenService(clock: Clock = FIXED_CLOCK, ttl: Duration = Duration.ofHours(8)): TokenService =
        TokenService(signingSecret = SIGNING_SECRET, ttl = ttl, clock = clock)

    data class SeededUser(val id: Long, val clinicId: Long, val email: String, val role: String, val password: String)

    fun seedUser(
        repo: ClinicUserRepo,
        clinicId: Long,
        email: String,
        password: String = "pw-correct",
        role: String = "admin",
    ): SeededUser {
        val user = repo.insert(
            NewClinicUser(
                clinicId = clinicId,
                email = email,
                passwordHash = ApiKeyHasher.hash(password),
                displayName = "User $email",
                role = role,
                active = true,
            ),
        )
        return SeededUser(user.id, clinicId, email, role, password)
    }

    fun tokenFor(svc: TokenService, user: SeededUser): String =
        svc.issue(clinicId = user.clinicId, clinicUserId = user.id, role = user.role)

    /**
     * A two-node algorithm whose single conclusion carries a [specialist] code,
     * `tests` + a localized `preparation` note, and a `book_appointment` clinic
     * action — enough to exercise the admin session-detail conclusion block
     * (FR-XKH348) and the specialist-codes endpoint. The conclusion node id is
     * `concl`; the entry question is `q1`.
     */
    fun conclusionAlgorithm(
        key: String,
        version: String = "1.0.0",
        specialist: String?,
        tests: List<String> = emptyList(),
    ): Algorithm = Algorithm(
        algorithmId = key,
        version = version,
        metadata = AlgorithmMetadata(
            name = mapOf("ru" to "Фикстура", "kk" to "Фикстура"),
            category = "general",
            ageGroup = "adult",
            estimatedTimeMinutes = 1,
        ),
        entryPoint = "q1",
        nodes = mapOf(
            "q1" to QuestionNode(
                id = "q1",
                text = mapOf("ru" to "Вопрос?", "kk" to "Сұрақ?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "concl"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "concl"),
                ),
            ),
            "concl" to ConclusionNode(
                id = "concl",
                urgency = Urgency.URGENT,
                diagnosis = mapOf("ru" to "Срочный диагноз", "kk" to "Шұғыл диагноз"),
                action = mapOf("ru" to "Запишитесь к врачу", "kk" to "Дәрігерге жазылыңыз"),
                specialist = specialist,
                tests = tests,
                preparation = tests.associateWith { mapOf("ru" to "Натощак", "kk" to "Аш қарынға") },
                clinicAction = ClinicAction.BOOK_APPOINTMENT,
                followUpDays = 7,
            ),
        ),
    )
}

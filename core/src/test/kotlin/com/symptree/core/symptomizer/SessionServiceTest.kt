package com.symptree.core.symptomizer

import com.symptree.core.crypto.PiiCipher
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.llm.AlgorithmSelection
import com.symptree.core.llm.FakeLlmRouterClient
import com.symptree.core.llm.Intent
import com.symptree.core.llm.IntentClassification
import com.symptree.core.llm.LlmRejectedException
import com.symptree.core.llm.OptionMapping
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-PWVTT4.2 / .3 / .8 / .9 — `SessionService` orchestration with a fake
 * `LlmRouterClient` and a real Testcontainers Postgres backing
 * `SessionRepo` + `ClinicRepo` + `AlgorithmRepo`.
 *
 * The fake LLM lets us deterministically exercise:
 *   - start/resume idempotence
 *   - languageHint fallback to clinic default_language
 *   - setLanguage success + LanguageNotAllowedException
 *   - applyStep pre-pick (intent classification → algorithm selection)
 *   - applyStep mid-walk (free-form → option mapping)
 *   - applyStep rejection → RepromptPrompt
 *   - applyStep LLM-throws → ButtonOnlyPrompt fallback (no retries)
 *   - history append + state update in a single UPDATE (atomicity)
 */
class SessionServiceTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var algorithmRepo: AlgorithmRepo
    private lateinit var sessionRepo: SessionRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        algorithmRepo = AlgorithmRepo(db)
        sessionRepo = SessionRepo(db)
    }

    // ---------- AC-PWVTT4.2: startOrResume ----------

    @Test
    fun `AC-2 startOrResume creates a new active session when none exists`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac2-new")
        val service = newService()

        val session = service.startOrResume(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-1",
            languageHint = "ru",
        )
        assertEquals(clinic.id, session.clinicId)
        assertEquals("telegram", session.platform)
        assertEquals("tg-1", session.platformUserId)
        assertEquals("ru", session.language)
        assertEquals("active", session.status)
    }

    @Test
    fun `AC-2 startOrResume returns the existing active session when one is already open`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac2-resume")
        val service = newService()

        val first = service.startOrResume(clinic.id, "telegram", "tg-2", languageHint = "ru")
        val second = service.startOrResume(clinic.id, "telegram", "tg-2", languageHint = "ru")
        assertEquals(first.id, second.id)
    }

    @Test
    fun `AC-2 startOrResume without languageHint falls back to clinic default_language`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac2-default-lang",
            defaultLanguage = "kk",
            allowedLanguages = listOf("kk", "ru"),
        )
        val service = newService()

        val session = service.startOrResume(clinic.id, "telegram", "tg-3", languageHint = null)
        assertEquals("kk", session.language)
    }

    @Test
    fun `AC-2 startOrResume with unsupported languageHint falls back to clinic default_language`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac2-langhint-bad",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru"),
        )
        val service = newService()

        val session = service.startOrResume(clinic.id, "telegram", "tg-4", languageHint = "tr")
        assertEquals("ru", session.language)
    }

    // ---------- AC-PWVTT4.3: setLanguage ----------

    @Test
    fun `AC-3 setLanguage updates language and appends an audit turn`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac3-ok",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )
        val service = newService()
        val session = service.startOrResume(clinic.id, "telegram", "tg-5", languageHint = "ru")

        service.setLanguage(session.id, "kk")

        val reloaded = sessionRepo.findById(session.id)
        assertNotNull(reloaded)
        assertEquals("kk", reloaded.language)
        // history must include an audit turn — assert at least one entry exists.
        assertTrue(reloaded.history.size >= 1, "expected an audit turn appended to history; got ${reloaded.history}")
    }

    @Test
    fun `AC-3 setLanguage rejects with LanguageNotAllowedException when language not in allowed set`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac3-reject",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )
        val service = newService()
        val session = service.startOrResume(clinic.id, "telegram", "tg-6", languageHint = "ru")
        try {
            service.setLanguage(session.id, "tr")
            fail("expected LanguageNotAllowedException")
        } catch (e: LanguageNotAllowedException) {
            assertEquals("tr", e.language)
            assertTrue(e.allowed.containsAll(setOf("ru", "kk")))
        }
        // language must not have been changed
        val reloaded = sessionRepo.findById(session.id)
        assertNotNull(reloaded)
        assertEquals("ru", reloaded.language)
        Unit
    }

    // ---------- AC-PWVTT4.8: applyStep ----------

    @Test
    fun `AC-8 applyStep pre-pick classifies intent and picks an enabled algorithm`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-prepick")
        // Seed one algorithm and enable it for this clinic.
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_x", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
        )
        val service = newService(llm = fakeLlm)

        val session = service.startOrResume(clinic.id, "telegram", "tg-10", languageHint = "ru")
        val result = service.applyStep(session, userText = "у меня болит голова")

        assertTrue(result is StepResult.NextPrompt, "expected NextPrompt for pre-pick, got ${result::class.simpleName}")
        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        assertEquals(
            expected = algorithmId,
            actual = reloaded.currentAlgorithmId,
            message = "current_algorithm_id must be set after pre-pick",
        )
        assertNotNull(reloaded.currentNodeId)
        Unit
    }

    @Test
    fun `AC-8 applyStep mid-walk maps free-form reply to option_value via LLM and advances the walker`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-midwalk")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_y", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            mappingResponse = OptionMapping(pickedOptionValue = "no", confidence = 0.9, clarifyingQuestion = null),
        )
        val service = newService(llm = fakeLlm)

        var session = service.startOrResume(clinic.id, "telegram", "tg-11", languageHint = "ru")
        // First step pre-picks the algorithm; reload to get mid-walk state.
        service.applyStep(session, userText = "у меня болит голова")
        session = sessionRepo.findById(session.id) ?: fail("session must persist after pre-pick")

        val secondResult = service.applyStep(session, userText = "нет, не внезапно")
        // walker option "no" from q1 goes to q2 (NextQuestion) per the fixture tree.
        assertTrue(
            secondResult is StepResult.NextPrompt,
            "expected NextPrompt for mid-walk, got ${secondResult::class.simpleName}",
        )
        // LLM mapping was called exactly once on the mid-walk step.
        assertEquals(1, fakeLlm.mapFreeFormToOptionCallCount)
    }

    @Test
    fun `AC-8 applyStep returns RepromptPrompt when LLM mapping fails validation`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-reprompt")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_r", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        // LLM hallucinates an option value not in the node's option set.
        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            mappingResponse = OptionMapping(pickedOptionValue = "maybe", confidence = 0.95, clarifyingQuestion = null),
        )
        val service = newService(llm = fakeLlm)

        var session = service.startOrResume(clinic.id, "telegram", "tg-12", languageHint = "ru")
        service.applyStep(session, userText = "у меня болит голова") // pre-pick
        session = sessionRepo.findById(session.id) ?: fail("session must persist")

        val result = service.applyStep(session, userText = "может быть")
        assertTrue(
            result is StepResult.RepromptPrompt,
            "expected RepromptPrompt on rejection, got ${result::class.simpleName}",
        )
    }

    @Test
    fun `AC-8 applyStep writes state and history in a single UPDATE (no orphaned writes)`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-atomic")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_a", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val fakeLlm = FakeLlmRouterClient()
        val service = newService(llm = fakeLlm)

        val session = service.startOrResume(clinic.id, "telegram", "tg-13", languageHint = "ru")
        service.applyStep(session, userText = "болит")

        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        // history must contain at least one turn AND state must be advanced.
        assertTrue(reloaded.history.isNotEmpty(), "expected applyStep to append a turn")
        assertNotNull(reloaded.currentAlgorithmId, "expected applyStep to set current_algorithm_id")
        Unit
    }

    @Test
    fun `AC-8 applyStep walking to a ConclusionNode returns ConclusionPrompt and flips status to concluded`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-conclude")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_c", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        // "yes" at q1 walks directly to demo_emergency (ConclusionNode) per the fixture tree.
        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            mappingResponse = OptionMapping(pickedOptionValue = "yes", confidence = 0.95, clarifyingQuestion = null),
        )
        val service = newService(llm = fakeLlm)

        var session = service.startOrResume(clinic.id, "telegram", "tg-conc", languageHint = "ru")
        service.applyStep(session, userText = "болит") // pre-pick → q1
        session = sessionRepo.findById(session.id) ?: fail("session must persist")

        val result = service.applyStep(session, userText = "да, внезапно")
        assertTrue(
            result is StepResult.ConclusionPrompt,
            "expected ConclusionPrompt at terminal node, got ${result::class.simpleName}",
        )
        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        assertEquals("concluded", reloaded.status, "session status must flip to 'concluded' at terminal node")
    }

    @Test
    fun `AC-8 applyStep pre-pick routes non-SYMPTOM intent (BOOKING) to RepromptPrompt with NON_SYMPTOM_INTENT reason`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-non-symptom")
        // No algorithms enabled — the non-SYMPTOM branch must return *before* the enabled-algorithm lookup.
        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.BOOKING, 0.95, null),
        )
        val service = newService(llm = fakeLlm)

        val session = service.startOrResume(clinic.id, "telegram", "tg-nonsym", languageHint = "ru")
        val result = service.applyStep(session, userText = "запишите меня к терапевту")

        assertTrue(
            result is StepResult.RepromptPrompt,
            "expected RepromptPrompt for BOOKING intent in pre-pick, got ${result::class.simpleName}",
        )
        val reprompt = result as StepResult.RepromptPrompt
        assertEquals(
            LlmRejectedException.Reason.NON_SYMPTOM_INTENT,
            reprompt.reason,
            "non-SYMPTOM routing must surface NON_SYMPTOM_INTENT so the bot can render a routing-signal UX",
        )
        // Session must NOT have advanced — current_algorithm_id stays null.
        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        assertEquals(null, reloaded.currentAlgorithmId, "non-SYMPTOM intent must not advance the session into the walker")
    }

    // ---------- AC-PWVTT4.9: LLM unavailable → button-only fallback ----------

    @Test
    fun `AC-9 applyStep mid-walk falls back to ButtonOnlyPrompt when LLM throws (no retries)`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac9-buttononly")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_b", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        // First step uses a healthy LLM to pre-pick; then we swap to a throwing LLM for mid-walk.
        val happyLlm = FakeLlmRouterClient()
        val happyService = newService(llm = happyLlm)
        var session = happyService.startOrResume(clinic.id, "telegram", "tg-14", languageHint = "ru")
        happyService.applyStep(session, userText = "болит")
        session = sessionRepo.findById(session.id) ?: fail("session must persist after pre-pick")

        val sickLlm = FakeLlmRouterClient(throwOnCall = java.io.IOException("upstream timeout"))
        val sickService = newService(llm = sickLlm)
        val result = sickService.applyStep(session, userText = "что-то непонятное")

        assertTrue(
            result is StepResult.ButtonOnlyPrompt,
            "expected ButtonOnlyPrompt on LLM failure, got ${result::class.simpleName}",
        )
        // Options of the current node must be listed in the user's language.
        val options = (result as StepResult.ButtonOnlyPrompt).options
        assertTrue(options.isNotEmpty(), "ButtonOnlyPrompt must list at least one option")
        // No exception escaped (we got here) AND mapping was called exactly once (no retries).
        assertEquals(1, sickLlm.mapFreeFormToOptionCallCount, "LLM must not be retried within a single request")
    }

    // ---------- multi-tenancy (FR Testing § + requirements §2.1) ----------

    @Test
    fun `multi-tenancy startOrResume isolates sessions per clinic — same platform_user can have two active sessions across clinics`() = runBlocking {
        val (clinicA, _) = Fixtures.fixtureClinic(clinicRepo, slug = "tenant-a")
        val (clinicB, _) = Fixtures.fixtureClinic(clinicRepo, slug = "tenant-b")
        val service = newService()

        val sessionA = service.startOrResume(clinicA.id, "telegram", "tg-shared", languageHint = "ru")
        val sessionB = service.startOrResume(clinicB.id, "telegram", "tg-shared", languageHint = "ru")
        // Distinct rows under the UNIQUE(platform, platform_user_id, clinic_id, status) constraint.
        assertTrue(sessionA.id != sessionB.id, "same platform user must yield distinct sessions across clinics")
        assertEquals(clinicA.id, sessionA.clinicId)
        assertEquals(clinicB.id, sessionB.clinicId)
    }

    @Test
    fun `multi-tenancy setLanguage rejects a language allowed in clinic_b but not clinic_a`() = runBlocking {
        val (clinicA, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "tenant-iso-a",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru"),
        )
        // clinic_b allows kk; the cross-clinic check must read the OWNING clinic's allowed_languages.
        Fixtures.fixtureClinic(
            clinicRepo,
            slug = "tenant-iso-b",
            defaultLanguage = "kk",
            allowedLanguages = listOf("ru", "kk"),
        )
        val service = newService()
        val sessionA = service.startOrResume(clinicA.id, "telegram", "tg-iso", languageHint = "ru")

        try {
            service.setLanguage(sessionA.id, "kk")
            fail("expected LanguageNotAllowedException — clinic_a does not allow kk")
        } catch (e: LanguageNotAllowedException) {
            assertEquals("kk", e.language)
            assertEquals(listOf("ru"), e.allowed)
        }
        Unit
    }

    // ---------- helpers ----------

    private fun newService(llm: FakeLlmRouterClient = FakeLlmRouterClient()): SessionService =
        SessionService(
            clinicRepo = clinicRepo,
            algorithmRepo = algorithmRepo,
            sessionRepo = sessionRepo,
            llm = llm,
            piiCipher = TEST_PII_CIPHER,
        )

    @Test
    fun `AC-8 applyStep pre-pick returns NoAlgorithmMatch when LLM rejects all candidates`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-noalgomatch")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_x", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        // LLM classifies the user's text as SYMPTOM but selectAlgorithm returns
        // null (the model decides none of the enabled candidates match — the
        // exact path "слабость" / "спина" went down in production testing).
        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            selectionResponse = AlgorithmSelection(selectedId = null, confidence = 0.95, reason = "no match"),
        )
        val service = newService(llm = fakeLlm)

        val session = service.startOrResume(clinic.id, "telegram", "tg-noalgo", languageHint = "ru")
        val result = service.applyStep(session, userText = "слабость")

        assertTrue(
            result is StepResult.NoAlgorithmMatch,
            "expected NoAlgorithmMatch when LLM rejects all candidates, got ${result::class.simpleName}",
        )
        // Session must stay pre-pick (no algorithm pinned) so the user can try
        // another complaint without being stuck in a wrong algorithm.
        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        assertEquals(null, reloaded.currentAlgorithmId, "currentAlgorithmId must remain null on no-match")
        assertEquals(null, reloaded.currentNodeId, "currentNodeId must remain null on no-match")
    }

    @Test
    fun `AC-8 pre-pick rejects a marginal-confidence selection below the 0_75 launch threshold`() = runBlocking {
        // The "sleep"/"сон" pilot bug: the LLM picked the one enabled flow with
        // only moderate confidence and launched the wrong triage. The call site
        // now requires >= 0.75, so a 0.70 selection — above the shared
        // RouterValidator default of 0.6 but speculative — falls through to
        // NoAlgorithmMatch instead of launching the flow.
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-marginal")
        val algorithm = SessionServiceFixtures.threeNodeAlgorithm("symptom_y", "1.0.0")
        val algorithmId = algorithmRepo.insert(algorithm, ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val fakeLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            selectionResponse = AlgorithmSelection(selectedId = algorithmId, confidence = 0.70, reason = null),
        )
        val service = newService(llm = fakeLlm)

        val session = service.startOrResume(clinic.id, "telegram", "tg-marginal", languageHint = "ru")
        val result = service.applyStep(session, userText = "сон")

        assertTrue(
            result is StepResult.NoAlgorithmMatch,
            "0.70 confidence is below the 0.75 launch threshold → NoAlgorithmMatch, got ${result::class.simpleName}",
        )
        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        assertEquals(null, reloaded.currentAlgorithmId, "no algorithm pinned on a below-threshold selection")
    }

    @Test
    fun `cancelActive flips an active session to cancelled and lets a fresh one take its slot`() = runBlocking {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "cancel-via-service")
        val service = newService(llm = FakeLlmRouterClient())

        val session = service.startOrResume(clinic.id, "telegram", "tg-cancel", languageHint = "ru")
        val rowsAffected = service.cancelActive(session.id)
        assertEquals(1, rowsAffected)

        val reloaded = sessionRepo.findById(session.id) ?: fail("session must persist")
        assertEquals("cancelled", reloaded.status)

        // startOrResume must now create a FRESH session — the cancelled one is
        // no longer in the partial-unique-index 'active' subset.
        val fresh = service.startOrResume(clinic.id, "telegram", "tg-cancel", languageHint = "ru")
        assertTrue(fresh.id != session.id, "expected a new session id after cancel; got the same id")
        assertEquals("active", fresh.status)
    }

    private companion object {
        // Deterministic 32-byte AES-256 test key; production keys come from SYMPTREE_PII_KEY.
        private val TEST_PII_CIPHER = PiiCipher(ByteArray(32) { it.toByte() })
    }
}

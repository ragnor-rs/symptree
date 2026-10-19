package com.symptree.core.symptomizer

/**
 * Local algorithm fixture for `SessionServiceTest`.
 *
 * Three-node shape mirroring the `WalkerTest` fixture so the deterministic
 * walker behavior is well-understood: q1 -> {yes: demo_emergency, no: q2};
 * q2 -> {yes/no: demo_emergency}.
 */
internal object SessionServiceFixtures {
    fun threeNodeAlgorithm(key: String, version: String): Algorithm = Algorithm(
        algorithmId = key,
        version = version,
        metadata = AlgorithmMetadata(
            name = mapOf("ru" to "Тест", "kk" to "Тест"),
            category = "neurology",
            ageGroup = "adult",
            estimatedTimeMinutes = 1,
        ),
        entryPoint = "q1",
        nodes = mapOf(
            "q1" to QuestionNode(
                id = "q1",
                text = mapOf("ru" to "Внезапно?", "kk" to "Күтпеген?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "demo_emergency"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "q2"),
                ),
            ),
            "q2" to QuestionNode(
                id = "q2",
                text = mapOf("ru" to "Долго?", "kk" to "Ұзақ?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "demo_emergency"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "demo_emergency"),
                ),
            ),
            "demo_emergency" to ConclusionNode(
                id = "demo_emergency",
                urgency = Urgency.EMERGENCY,
                diagnosis = mapOf("ru" to "Тест", "kk" to "Тест"),
                action = mapOf("ru" to "Тест", "kk" to "Тест"),
                specialist = null,
                clinicAction = ClinicAction.BYPASS_APPOINTMENT,
                followUpDays = null,
            ),
        ),
    )
}

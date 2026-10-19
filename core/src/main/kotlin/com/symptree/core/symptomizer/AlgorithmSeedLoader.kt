package com.symptree.core.symptomizer

import com.symptree.core.db.AlgorithmRepo
import org.slf4j.LoggerFactory

/**
 * Reads every known algorithm JSON file from the classpath under the
 * `resourcePath` directory (default "algorithms"), validates each against
 * RU+KK, and inserts via [AlgorithmRepo.insert] with `ingestedBy = "seed"`
 * IF the `(algorithm_key, version)` pair is not already present in the DB.
 *
 * Idempotent on re-run: re-seeding a populated DB is a no-op.
 *
 * Discovery model: we ship an explicit list of [KNOWN_ALGORITHMS] filenames
 * rather than walking the classpath. Classpath directory enumeration does
 * not work reliably from a packaged jar, so explicit listing keeps the seed
 * loader working in both `./gradlew run` and `java -jar core.jar`.
 *
 * To add a new seeded algorithm: drop its JSON into
 * `core/src/main/resources/algorithms/` and add its filename here.
 */
object AlgorithmSeedLoader {

    private val log = LoggerFactory.getLogger(AlgorithmSeedLoader::class.java)

    /**
     * Filenames (relative to `resourcePath`) shipped with the project.
     *
     * The repository ships a single **synthetic demo tree** only. It exercises
     * every [Urgency] and [ClinicAction] value so it doubles as the reference
     * example for the schema, but its content is placeholder text and carries
     * no clinical meaning. Operators supply their own clinically-reviewed trees.
     */
    val KNOWN_ALGORITHMS: List<String> = listOf(
        "symptom_demo_adult-v1.0.0.json",
    )

    /**
     * Languages every shipped algorithm must cover at seed time.
     *
     * Distinct from per-clinic `clinics.allowed_languages`: shipped algorithms
     * must support the union of every language any M2 clinic might enable
     * (RU + KK), regardless of what a specific clinic later enables. Per-clinic
     * ingestion-time validation against `allowed_languages` is a separate gate
     * (FR Notes: "validated at ingestion against the union RU+KK").
     */
    private val REQUIRED_LANGUAGES: Set<String> = setOf("ru", "kk")

    fun seed(repo: AlgorithmRepo, resourcePath: String = "algorithms") {
        for (filename in KNOWN_ALGORITHMS) {
            val classpathName = "/$resourcePath/$filename"
            val stream = javaClass.getResourceAsStream(classpathName)
                ?: error("missing seed algorithm resource: $classpathName")
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }

            val algorithm = AlgorithmJson.instance.decodeFromString(Algorithm.serializer(), text)
            AlgorithmIngestionValidator.validate(algorithm, REQUIRED_LANGUAGES)

            val existing = repo.findByKeyAndVersion(algorithm.algorithmId, algorithm.version)
            if (existing != null) {
                log.info(
                    "algorithm already present, skipping: key={} version={} id={}",
                    algorithm.algorithmId,
                    algorithm.version,
                    existing,
                )
                continue
            }
            repo.insert(algorithm, ingestedBy = "seed")
        }
    }
}

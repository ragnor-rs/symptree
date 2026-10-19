# Contributing

Thanks for taking an interest. This project has a few firm architectural
constraints, so please skim this before opening a pull request — it will save
you from building something that gets rejected on principle rather than merit.

## Prerequisites

You need all three to run the gate:

- **JDK 21**
- **Docker**, running. The integration tests use Testcontainers to start
  Postgres. Without Docker the suite cannot pass.
- **Node 20+** with npm. `./gradlew test` runs the admin panel's Vitest suite as
  part of the project-wide aggregate, so Node is not optional even for a
  backend-only change.

## The gate

Every change must pass:

```bash
./gradlew detekt test
```

Verify with a cold run before you push. Gradle will happily report
`UP-TO-DATE` for a suite it never ran, which can mask both a stopped Docker
daemon and a genuine failure:

```bash
./gradlew clean detekt test --rerun-tasks --no-build-cache
```

If you add behaviour, add a test for it in the same pull request.

## Architectural constraints

These are deliberate and predate any given PR. Changing one needs a discussion
in an issue first, not a pull request.

- **Raw JDBC.** No ORM, no JPA, no Exposed, no jOOQ, no Hibernate.
- **No DI framework.** No Koin, no Dagger, no Spring. Dependencies are wired by
  hand from `main()`.
- **`messaging` must not depend on `core` at compile time.** The two modules
  share wire contracts only, over HTTP. The same applies to `admin-panel`.
- **No connection pool yet.** Add HikariCP only when measured load justifies it.
- **Config from environment variables.** No `.properties` files, no config DSLs.
  Every module has a `config/<Module>Config.kt` with a `fromEnv()` factory, and
  secrets have no defaults — a missing one must fail loudly at startup.
- **kotlinx-serialization** for JSON, not Jackson or Gson.
- **No user-facing string literals in `core`.** The backend emits semantic DTOs
  (codes and enums); `messaging` and `admin-panel` own all localization. There
  are sweep tests that enforce this.

## Platform and clinical content

Adding a messaging platform means adding one adapter behind `port/MessagingPort`
in `messaging/src/main/kotlin/.../adapter/`. Handler logic should not change.

Decision trees are content, not code. Pull requests adding clinical decision
trees will not be merged — the repository ships one synthetic demo tree
deliberately, and real trees need clinical review that this project is not set
up to provide. Improvements to the *schema*, the *walker*, or the *validator*
are very welcome.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), subject
≤72 characters:

```
<type>(<scope>): <description>
```

`type` is one of `feat | fix | docs | style | refactor | perf | test | build |
ci | chore | revert`. Append `!` for a breaking change. Scope is the touched
area — `feat(core)`, `fix(messaging)`, `chore(deps)`.

Work on a branch named `<type>/<slug>`, not on `main`.

## Project layout

Specs live in `specs/` — `requirements.md` for cross-cutting invariants,
`technical-spec.md` for architecture and schemas, `testing-spec.md` for test
conventions, and one file per feature under `specs/frs/`. If your change alters
an architectural decision, update the spec in the same pull request.

Tests go in `src/test/kotlin/<same-package-as-source>/`, named
`<ClassName>Test.kt`, or `<ClassName>IT.kt` for ones that need Postgres.
Prefer real objects and fakes over mocks; if mocking becomes unavoidable, MockK
is the default.

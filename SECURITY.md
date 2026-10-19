# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Report it privately through
[GitHub's private vulnerability reporting](https://github.com/ragnor-rs/symptree/security/advisories/new),
or by email to **ragnor.rs@gmail.com**.

Please include what you found, how to reproduce it, and which version or commit
you were on. This is a small project maintained in spare time — expect an
acknowledgement within a week. You will get credit in the fix unless you would
rather not.

## Scope

This software handles patient health information, so the following are
considered high-severity and are especially welcome as reports:

- Cross-tenant data access — any path where one clinic can reach another
  clinic's clinics, sessions, doctors, or bookings. Every row and request is
  supposed to be scoped by `clinic_id`.
- Authentication or authorization bypass on the platform-admin API key, the
  clinic-admin API key, the clinic-user bearer token, or either bot shared
  secret.
- Leakage of patient PII into logs, error responses, or the reminder payload.
  Free-text symptom descriptions and phone numbers are encrypted at rest with
  AES-256-GCM and must never be logged in plaintext.
- Weaknesses in the PII encryption or in admin token signing.
- Prompt injection that causes the LLM to drive the symptomizer into a state the
  decision tree does not permit. All LLM output that affects state is supposed to
  be validated against the walker's legal next states before being applied.

## Out of scope

- Findings that require an operator to have already misconfigured secrets.
- The known limitations listed in the [README](README.md#known-limitations).
  They are documented gaps, not vulnerabilities — though a security *consequence*
  of one is in scope and worth reporting.
- Clinical correctness of decision trees. The repository ships only a synthetic
  demo tree; real trees are operator-supplied content and their clinical safety
  is the operator's responsibility.

## Operator responsibilities

symptree is infrastructure, not a turnkey compliant deployment. If you run it
with real patients you own:

- Generating and safely storing all secrets. `SYMPTREE_PII_KEY` must be 32
  bytes; losing it makes existing encrypted data unrecoverable, and leaking it
  exposes all of it. There is no key-rotation tooling yet.
- TLS termination. The stack speaks plain HTTP behind its proxy.
- Data residency and personal-data law in your jurisdiction, and whether your
  configured trees make the deployment a regulated medical device.
- Restricting Postgres network exposure. The default compose file publishes
  port 5432.

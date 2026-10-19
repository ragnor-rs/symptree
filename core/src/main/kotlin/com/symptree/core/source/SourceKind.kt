package com.symptree.core.source

/**
 * Discriminator for the kind of backing system a [MedicalDataSource] wraps.
 *
 * `kind` is the **adapter + connection-params discriminator**: it selects which
 * adapter implementation backs a source and how that source's connection
 * parameters are parsed. It is orthogonal to [SourceId], which is the source's
 * per-instance primary key — a clinic may run several sources of the same kind,
 * each with its own [SourceId] and connection params.
 *
 * M2 ships only [INTERNAL] — the clinic's own canonical catalog, which has no
 * external connection. Further kinds (a standardized-format family that backs
 * many external provider instances through one adapter, plus per-vendor CRM
 * kinds — REST CRMs are not a standard, so each integration is its own kind)
 * land in M3 alongside their adapter implementations under `source/<kind>/`.
 *
 * The concrete future kinds are named in technical-spec.md § 3.4, deliberately
 * not here: the provider-name-leak guard ([NoProviderNameLeakTest]) keeps
 * vendor/protocol names out of this canonical package — including its comments.
 */
enum class SourceKind { INTERNAL }

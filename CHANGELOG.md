# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- Redis-backed `SemanticCache` and `QuotaStore`
- Micrometer metrics registry
- Actuator endpoint for the cost report
- Streaming responses with mid-stream accounting
- Reference providers for OpenAI, Qwen (DashScope), DeepSeek and Ollama

## [0.1.0] - 2026-10-20

First public pre-release. The governance model is settled; the API surface is not, and renames
should be expected before 1.0.

### Added

- **Governance pipeline.** `Tollgate.call` runs canonicalisation, cache look-up, routing, quota
  reservation, provider invocation with fallback, then pricing, storage, metering and auditing,
  in that order.
- **Semantic cache.** `InMemorySemanticCache` with a two-stage look-up: exact canonical match
  first, vector similarity second. Partitioned by tenant and by system prompt fingerprint.
- **Two-layer query normalisation.** `TextNormalizer` handles case, punctuation, full-width
  characters and whitespace. `QueryCanonicalizer` removes question framing — position-aware, so
  that `申请` is never damaged by removing `请`.
- **Literal guard.** `LiteralGuard` extracts digit runs into a fingerprint that gates similarity
  matching, preventing `订单 12345` from being served `订单 67890`'s answer.
- **Dependency-free embeddings.** `HashingEmbeddingProvider` using signed feature hashing over
  character unigrams, bigrams and trigrams. Pluggable via `EmbeddingProvider`.
- **Routing.** `CostFirstRouter`, `QualityFirstRouter`, `LatencyFirstRouter`, `BalancedRouter`
  (quality threshold plus cost/latency ranking) and `ProfileAwareRouter`.
- **Quota.** `InMemoryQuotaStore` with a windowed budget and an independent token-bucket rate
  limit. Reservations are made before the call and never refunded.
- **Metering.** `UsageRegistry` aggregating per-tenant and per-model spend, and `CostReport`
  reporting both incurred and avoided cost.
- **Auditing.** `AuditLogger` and `InMemoryAuditLog` recording a SHA-256 prompt digest rather
  than the prompt text.
- **Spring Boot starter.** Auto-configuration with `@ConditionalOnMissingBean` back-off for every
  component, and `tollgate.*` property binding.
- **Simulated provider.** `SimulatedProvider` lets the full pipeline run with no API key and no
  network access.
- **Example application.** A runnable walkthrough covering cache hits, routing profiles, quota
  rejection and the cost report.

### Design notes

- `BalancedRouter` originally scored quality, cost and latency as a weighted sum with min-max
  normalisation. Testing showed that with two candidates the cost term always dominated, making
  the router equivalent to `CostFirstRouter` regardless of weights. Replaced with a quality
  threshold plus cost/latency ranking. See ADR-0004.
- `CacheEntry` originally carried its own TTL. This allowed an entry constructed with a one-hour
  TTL to outlive a cache configured for ten minutes. TTL is now owned solely by `CachePolicy`.
- `QueryCanonicalizer` originally stripped single characters anywhere in the string, which turned
  `申请` into `申`. Framing removal is now positional. See ADR-0002.

### Known limitations

- The in-process cache scans entries linearly beyond the exact-match path. Sized for thousands of
  entries per tenant, not millions.
- Quota counters are per instance. In a multi-instance deployment the effective budget is the
  configured budget multiplied by the instance count.
- Multi-turn conversations bypass the semantic cache by design.
- Non-numeric identifiers are not covered by the literal guard.
- `HashingEmbeddingProvider` recognises near-duplicate wording, not paraphrase.

[Unreleased]: https://github.com/tollgate-io/tollgate/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/tollgate-io/tollgate/releases/tag/v0.1.0

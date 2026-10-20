# Architecture

## Modules

```
tollgate-parent
├── tollgate-core                  the governance engine, zero runtime dependencies
├── tollgate-spring-boot-starter   auto-configuration and property binding
└── tollgate-example               runnable walkthrough, no API key required
```

`tollgate-core` has no dependency on Spring and no dependency on any third-party library. It can
be used from a plain `main` method, a Quarkus application, an Akka actor, or a test. The starter
is an optional convenience layer that wires the same objects together.

```
┌───────────────────── tollgate-core ─────────────────────┐
│                                                          │
│  model/      LlmRequest, LlmResponse, Message, ModelSpec │
│  provider/   LlmProvider, SimulatedProvider              │
│  cache/      SemanticCache, CacheEntry, CachePolicy,     │
│              EmbeddingProvider, HashingEmbeddingProvider │
│  route/      ModelRouter, CostFirst/QualityFirst/        │
│              LatencyFirst/Balanced/ProfileAware          │
│  quota/      QuotaStore, QuotaPolicy, QuotaAction        │
│  meter/      UsageRecord, UsageListener, UsageRegistry,  │
│              CostReport                                  │
│  audit/      AuditEvent, AuditLogger, InMemoryAuditLog   │
│  util/       TextNormalizer, QueryCanonicalizer,         │
│              LiteralGuard, TokenEstimator, PromptDigest  │
│  pipeline/   Tollgate                                    │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

Dependencies flow one way: `pipeline` knows everything, `util` knows nothing. The `route` package
depends only on `model` and `exception`, which is what makes routers trivially testable as pure
functions.

---

## Request lifecycle

A single `Tollgate.call(request)` runs six stages. The order is load-bearing and each step
explains why it sits where it does.

```
                        ┌─────────────┐
                        │   request   │
                        └──────┬──────┘
                               ▼
                   ┌───────────────────────┐
   stage 1         │ canonicalise          │  normalise text, strip question
                   │ - TextNormalizer      │  framing, extract literal fingerprint
                   │ - QueryCanonicalizer  │
                   │ - LiteralGuard        │
                   └───────────┬───────────┘
                               ▼
                   ┌───────────────────────┐
   stage 2         │ cache look-up         │  exact match, then similarity
                   │ gated on tenant +     │  gated on literal fingerprint
                   │ system prompt +       │
                   │ literal fingerprint   │
                   └───────────┬───────────┘
                        hit ───┴─── miss
                         │          │
                         │          ▼
                         │  ┌───────────────────────┐
   stage 3               │  │ route                 │  filter by context window,
                         │  │ - ModelRouter         │  then rank by profile
                         │  └───────────┬───────────┘
                         │              ▼
                         │  ┌───────────────────────┐
   stage 4               │  │ reserve quota         │  prompt + maxTokens, never refunded
                         │  └───────────┬───────────┘
                         │              ▼
                         │  ┌───────────────────────┐
   stage 5               │  │ invoke provider       │  re-route across remaining
                         │  │ + fallback            │  candidates on failure
                         │  └───────────┬───────────┘
                         │              ▼
                         │  ┌───────────────────────┐
   stage 6               │  │ price, store, meter,  │  cost from reported usage,
                         │  │ audit                 │  never from the estimate
                         │  └───────────┬───────────┘
                         ▼              ▼
                   ┌─────────────────────────┐
                   │        response         │
                   └─────────────────────────┘
```

### Stage 1 — Canonicalisation

Three pure functions run in sequence:

| Function | Purpose |
| --- | --- |
| `TextNormalizer.normalize` | NFKC folding, lower-casing, punctuation removal, whitespace collapsing |
| `QueryCanonicalizer.canonicalize` | question-framing removal (see ADR-0002) |
| `LiteralGuard.fingerprint` | digit-run extraction used as a matching gate (see ADR-0003) |

All three are stateless and allocation-light. They run on every call, including cache hits, so
they are on the latency path and are kept simple for that reason.

### Stage 2 — Cache look-up

The cache key is the triple `(tenant, system prompt fingerprint, literal fingerprint)` plus the
canonical question. Look-up runs in two passes:

1. **Exact** — hash map access, O(1). Resolves the majority of real duplicates once framing has
   been stripped.
2. **Similarity** — linear scan over the tenant's entries, filtered by system prompt fingerprint
   and literal fingerprint, then dot product against the query embedding.

A hit returns immediately. A tenant whose budget is exhausted still receives a cached answer,
because the original call was already paid for — see `TollgateTest#servesCacheEvenWhenOverBudget`.

### Stage 3 — Routing

The router receives the full candidate pool and the request. It:

1. Filters candidates whose context window cannot hold `prompt + maxTokens`. Checking the prompt
   alone would admit requests the model is guaranteed to truncate.
2. Ranks the survivors according to the request's `RoutingProfile`.

Routers are pure functions with no I/O and no state. That constraint is what makes routing
decisions reproducible in tests and explainable in a post-mortem.

### Stage 4 — Quota reservation

`QuotaStore.tryConsume(tenant, estimatedPromptTokens + maxTokens)` is called before the provider.
The reservation is atomic, so concurrent callers cannot collectively exceed the budget.

On refusal the configured `QuotaAction` decides what happens: `REJECT` throws, `DEGRADE` re-routes
to the cheapest candidate, `LOG_ONLY` proceeds and records the overrun.

### Stage 5 — Invocation and fallback

The provider is called with the chosen model. If it throws, the failed model is removed from the
pool and the router picks again from the remainder, up to `maxFallbacks` times. A request that
pinned its model explicitly gets no fallback: the caller asked for that model, and silently
substituting another would violate the contract.

### Stage 6 — Closing the loop

Pricing uses the provider's reported token counts, never the reservation estimate. The entry is
written to the cache, a `UsageRecord` is published to the registry and any extra listeners, and
an `AuditEvent` carries the digest to the audit logger.

Both fan-out points are best-effort. A listener that throws is isolated and swallowed: losing a
metric is preferable to failing a request that already succeeded.

---

## Extension points

Every component is an interface with a working default.

| Interface | Default | Replace when |
| --- | --- | --- |
| `LlmProvider` | none — you must supply one | always, this is the vendor seam |
| `ModelRouter` | `ProfileAwareRouter` → `BalancedRouter` | your cost or quality model differs |
| `EmbeddingProvider` | `HashingEmbeddingProvider` | you need true paraphrase matching |
| `SemanticCache` | `InMemorySemanticCache` | you run more than one instance |
| `QuotaStore` | none — quotas are off until supplied | you want budget enforcement |
| `AuditLogger` | `AuditLogger.noop()` in core, `InMemoryAuditLog` in the starter | you need durable audit |
| `UsageListener` | `UsageRegistry` | you need to ship metrics somewhere |

In the Spring starter, supplying a bean of any of these types replaces the default through
`@ConditionalOnMissingBean`. No configuration flag is needed.

---

## Concurrency model

| Component | Mechanism | Contention |
| --- | --- | --- |
| `InMemorySemanticCache` | `ConcurrentHashMap` + `LongAdder` | none on the read path |
| `InMemoryQuotaStore` | per-tenant monitor | bounded by concurrent calls for the same tenant |
| `UsageRegistry` | `LongAdder` / `DoubleAdder` | none |
| `InMemoryAuditLog` | single monitor around an `ArrayDeque` | brief, one lock for the ring |
| `Tollgate` | stateless apart from an `AtomicLong` for trace ids | none |

The quota critical section contains arithmetic only. It deliberately does not span the model
call, so a slow provider cannot hold a tenant's budget lock.

Two consequences are accepted rather than solved:

- **Duplicate work on concurrent misses.** Two threads asking the same uncached question will
  both call the provider. The alternative is holding a lock across a network call.
- **Per-instance quotas.** Each JVM keeps its own counters, so the effective budget in an
  N-instance deployment is N times the configured value. Documented in `InMemoryQuotaStore` and
  listed as a roadmap item.

---

## Failure behaviour

| Failure | Behaviour |
| --- | --- |
| Provider throws | Re-route across remaining candidates; if none succeed, `ProviderException` |
| No model fits the context window | `NoEligibleModelException` with the required and maximum sizes |
| Tenant over budget, action `REJECT` | `QuotaExceededException` with tenant and requested tokens |
| Tenant over budget, action `DEGRADE` | Cheapest eligible model is used instead |
| Embedding provider throws | Cache is skipped for this call; the request proceeds |
| Usage listener throws | Swallowed; the call returns normally |
| Audit logger throws | Swallowed; the request path is never dependent on audit durability |

The last three are the only places where Tollgate deliberately swallows exceptions. All three are
observability or optimisation side-channels: a failure there should degrade the quality of the
report, never the availability of the feature.

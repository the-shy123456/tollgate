# Configuration reference

Every property lives under the `tollgate` prefix and is bound to `TollgateProperties`.
Properties are optional; the defaults below are what you get by adding the dependency and
changing nothing.

---

## `tollgate.enabled`

| | |
| --- | --- |
| Type | `boolean` |
| Default | `true` |

Turns auto-configuration on or off. When `false`, no `Tollgate` bean is created and the starter
backs off entirely. Useful for tests and for staging environments that must not emit LLM traffic.

---

## Cache

### `tollgate.cache.enabled`

| | |
| --- | --- |
| Type | `boolean` |
| Default | `true` |

Whether semantic caching is active. Disabling it does not disable routing, metering or auditing;
those continue to work so that the cost report remains accurate.

### `tollgate.cache.similarity-threshold`

| | |
| --- | --- |
| Type | `double`, `(0, 1]` |
| Default | `0.75` |

Cosine similarity above which two questions are treated as the same.

**Raising it** makes the cache more conservative: fewer hits, fewer chances of a wrong answer.
**Lowering it** increases the hit rate and the risk. Two failure modes to keep in mind:

- A false positive returns an answer to a question the user did not ask.
- A false negative costs one model call.

These are not symmetric. When in doubt, raise the threshold.

The useful range depends on the embedding provider. With the built-in
`HashingEmbeddingProvider`, values above `0.90` effectively restrict hits to near-identical
strings. With a real embedding model, `0.85`–`0.92` is a more typical band.

### `tollgate.cache.ttl`

| | |
| --- | --- |
| Type | `Duration` (`6h`, `30m`, `7d`) |
| Default | `6h` |

How long a cached answer stays valid. `0` means entries never expire by age and are only removed
by the per-tenant ceiling.

Set this below the rate at which your source data changes. A refund-policy answer cached for a
week will contradict the new policy for most of that week.

### `tollgate.cache.max-entries-per-tenant`

| | |
| --- | --- |
| Type | `int`, positive |
| Default | `2000` |

Ceiling on cached entries per tenant. Entries are evicted in insertion order (FIFO, not LRU) once
the ceiling is exceeded.

The default is sized for the in-memory cache's linear similarity scan. Raising it significantly
raises lookup cost proportionally; back the cache with a vector store instead.

---

## Quota

Quotas are **off by default** because an arbitrary budget is worse than no budget — it either
never triggers or it breaks something during a demo. Turn them on after you have read a real cost
report.

### `tollgate.quota.enabled`

| | |
| --- | --- |
| Type | `boolean` |
| Default | `false` |

Requires a `QuotaStore` bean. Enabling this without one fails at startup with an actionable
message rather than silently not enforcing anything.

### `tollgate.quota.tokens-per-window`

| | |
| --- | --- |
| Type | `long`, positive |
| Default | `1000000` |

Tokens allowed per window, per tenant.

Note that this counts **reservations**, not actual usage. A request reserves
`estimatedPromptTokens + maxTokens`. Applications that set `maxTokens` to a large theoretical
maximum will consume their budget several times faster than their real usage suggests.

### `tollgate.quota.window`

| | |
| --- | --- |
| Type | `Duration` |
| Default | `30d` |

Length of the budget window. The window starts at a tenant's first call and rolls over
`window` later; it is a fixed window, not a calendar month. A tenant whose window starts on the
7th gets a fresh budget on the 7th.

### `tollgate.quota.max-tokens-per-second`

| | |
| --- | --- |
| Type | `long` |
| Default | `0` (unlimited) |

Sustained token rate ceiling, enforced with a token bucket sized to one second of burst.

This is a separate limit from the budget, and it exists because the budget alone cannot stop a
retry loop. A loop inside a `for` statement can consume a quarter's budget in an afternoon; the
budget check will not object until the following morning.

### `tollgate.quota.action`

| | |
| --- | --- |
| Type | `QuotaAction` |
| Default | `reject` |

| Value | Behaviour |
| --- | --- |
| `reject` | Throw `QuotaExceededException`. Right for batch jobs and internal tools, where a caller can retry tomorrow. |
| `degrade` | Re-route to the cheapest eligible model. Keeps the feature alive; still consumes the remaining budget. |
| `log_only` | Proceed and record the overrun. Use during the measurement phase, before enforcement. |

---

## Routing

### `tollgate.routing.quality-tolerance`

| | |
| --- | --- |
| Type | `int` |
| Default | `2` |

How many quality tiers below the best available candidate remain acceptable to the balanced
router.

`0` means "always use the best tier available" — which makes balanced equivalent to
best-quality. `5` means "anything goes", which makes it equivalent to cheapest. The default of
`2` on a 1–5 scale means a deployment whose best model is tier 5 will also consider tiers 4 and 3.

The tolerance is relative to the pool, not absolute: a deployment with only mid-tier models keeps
using them rather than rejecting everything.

### `tollgate.routing.cost-weight`

| | |
| --- | --- |
| Type | `double`, non-negative |
| Default | `0.7` |

Weight of cost when ranking acceptable models.

### `tollgate.routing.latency-weight`

| | |
| --- | --- |
| Type | `double`, non-negative |
| Default | `0.3` |

Weight of latency when ranking acceptable models.

`cost-weight` and `latency-weight` are normalised against each other, so only their ratio matters.
`0.7 / 0.3` and `7 / 3` behave identically.

### `tollgate.routing.max-fallbacks`

| | |
| --- | --- |
| Type | `int`, non-negative |
| Default | `1` |

How many alternative models may be tried when a provider call fails. `0` disables fallback.

Each additional attempt costs a full provider round trip on the failure path. A value of `1`
covers the common case — one vendor outage — without turning a total outage into a long chain of
timeouts.

Requests that pin a model explicitly are exempt: the caller asked for that model, and silently
substituting another would violate the contract.

---

## Audit

### `tollgate.audit.enabled`

| | |
| --- | --- |
| Type | `boolean` |
| Default | `true` |

Whether audit events are recorded. The recorded event contains a SHA-256 digest of the prompt,
never the prompt itself — see ADR-0007.

### `tollgate.audit.capacity`

| | |
| --- | --- |
| Type | `int`, positive |
| Default | `1000` |

Size of the in-memory ring buffer. Once full, the oldest events are discarded.

This ring is a development and single-node diagnostic tool, not a compliance archive. Production
deployments should supply an `AuditLogger` bean backed by their existing log infrastructure,
which already has retention, access control and tamper evidence.

---

## Recipes

### A support bot with templated questions

High duplication, no identifiers in most questions. Maximise the cache, keep routing cheap.

```yaml
tollgate:
  cache:
    similarity-threshold: 0.74
    ttl: 12h
  routing:
    quality-tolerance: 2
```

### An order-tracking assistant

Every question contains an order number, so the literal guard will refuse most similarity
matching and the cache will mostly miss. That is correct. Rely on routing for savings instead.

```yaml
tollgate:
  cache:
    ttl: 5m                  # account status goes stale fast anyway
  routing:
    quality-tolerance: 3
    cost-weight: 0.9
    latency-weight: 0.1
```

### A multi-tenant SaaS product

Budget enforcement matters more than hit rate.

```yaml
tollgate:
  cache:
    enabled: true
    max-entries-per-tenant: 500
  quota:
    enabled: true
    tokens-per-window: 500000
    window: 30d
    max-tokens-per-second: 2000
    action: degrade
```

### A batch backfill job

No user waiting, no quota needed, cost is everything.

```yaml
tollgate:
  cache:
    enabled: true
    ttl: 30d
  routing:
    quality-tolerance: 5     # any tier
    cost-weight: 1.0
    latency-weight: 0.0
```

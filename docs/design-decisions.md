# Design decisions

This document records the decisions that shaped Tollgate, including the ones that were
reversed after testing. Each entry states the problem, the choice, and what the choice costs.

The audience is someone about to change this code. If you are about to break one of these,
you should at least know what it was protecting.

---

## ADR-0001: No third-party runtime dependencies in the core

**Status:** accepted

**Context.** The obvious implementation of a gateway reaches for a JSON library, a caching
library, a logging facade, and an HTTP client. Each is individually reasonable. Together they
mean that adopting Tollgate changes an application's dependency graph and, eventually, its
classpath conflicts.

**Decision.** `tollgate-core` depends on the JDK and nothing else. Where a library would have
been convenient, the dependency was replaced:

| Would have used | Used instead | Cost accepted |
| --- | --- | --- |
| Caffeine | `ConcurrentHashMap` + FIFO queue | No access-order LRU, no async refresh |
| Jackson | No serialization in core at all | Providers and the Spring layer handle their own payloads |
| SLF4J | No logging in core; callers observe via listeners | No diagnostic output without wiring a listener |
| An embedding model | `HashingEmbeddingProvider` | Similarity is lexical, not semantic (ADR-0002) |

**Consequences.** Adoption costs one Maven coordinate. The replacements are weaker than the
libraries they stand in for, and each weakness is documented at the class that carries it rather
than hidden behind an abstraction that pretends otherwise.

---

## ADR-0002: Two-layer cache normalisation instead of embeddings alone

**Status:** accepted

**Context.** The first implementation keyed the cache on the raw prompt and matched by cosine
similarity. It performed badly on the workload it was built for. `如何申请退款` and
`请问我想申请退款呢` are the same question; as character strings they share only a fragment, and
their cosine similarity sat below any threshold that was safe for short queries.

**Decision.** Normalise before embedding, in two stages:

1. **Text normalisation** — Unicode NFKC (folds full-width characters), lower-casing,
   punctuation removal, whitespace collapsing.
2. **Question-framing removal** — strip the words that frame a question rather than carry its
   subject: `如何`, `请问`, `我想`, `是什么`, trailing `吗`/`呢`/`吧`.

Both strings collapse to `申请退款` and match exactly, with no vector arithmetic at all.

**Why position matters.** A naive substring filter is destructive in Chinese, which has no
spaces. Removing the single character `请` turns `申请` (apply) into `申`. Removing `的` damages
every noun phrase containing it. The implementation therefore removes framing **only at the
start of the string** (where `请问`/`我想`/`帮我` live), **at the end** (where `吗`/`呢` live),
and for **multi-character question words** that are unambiguous anywhere (`如何`, `为什么`).
Single characters are never removed from the middle.

**Consequences.** Templated and FAQ-style traffic now hits exactly rather than approximately,
which is both cheaper and safer. Genuine paraphrase (`退款` vs `要回我的钱`) still does not
match — that requires a real embedding model, and `EmbeddingProvider` exists so one can be
plugged in.

**Reversal note.** A weighted-sum score that let similarity override the exact match was tried
and removed. Exact canonical matches are the only hits that can be trusted without a threshold
argument, so they are checked first and always win.

---

## ADR-0003: Literal values gate similarity matching

**Status:** accepted

**Context.** This is the failure that makes semantic caching dangerous rather than merely
imprecise:

```
订单 12345 的物流到哪了
订单 67890 的物流到哪了
```

Character similarity is above 0.99. Semantically these are the same question. The answers are
different tracking numbers belonging to different customers. A cache that matches them **leaks
data between users**, and no similarity threshold can separate them, because they are genuinely,
maximally similar.

**Decision.** Before any similarity comparison, extract every digit run from the canonical text
and join them into a literal fingerprint. Two questions may only be matched by similarity when
their fingerprints are identical. Exact canonical matches still require the fingerprint to match,
because it is part of the cache key.

`订单 12345` → fingerprint `12345`
`订单 67890` → fingerprint `67890`
Different → similarity matching refused → the model is called.

**Consequences.**
- Identifier-bearing traffic loses most of its cache benefit. This is correct: those are exactly
  the queries where a wrong answer is unacceptable.
- FAQ traffic without digits shares the empty fingerprint and caches as before, so the guard
  costs nothing where caching matters most.
- The guard is deliberately strict. It does not reason that `12345` and `12346` are "probably
  different orders" — it treats every distinct digit sequence as distinct, because the failure
  mode is disclosure, not waste.

**Open question.** Non-numeric identifiers (ticket keys, email addresses, names) are not yet
covered. Extending the fingerprint to those is the highest-value open item in the security
category.

---

## ADR-0004: Quality as a threshold, not a term in a weighted sum

**Status:** accepted (supersedes an earlier weighted-sum design)

**Context.** The first `BalancedRouter` scored every candidate as
`wq * quality + wc * cost + wl * latency`, each axis min-max normalised against the current pool.
In tests with three models spanning a fifty-fold price range it always returned the cheapest
model, regardless of the quality weights.

The reason is structural. Min-max normalisation maps the cheapest candidate to `1` and the most
expensive to `0` **regardless of the actual gap**. With two candidates in the pool, a fifty-fold
difference and a five percent difference both become the same `0`/`1` pair. The cost term then
dominates permanently and the quality term can never recover.

**Decision.** Quality becomes a hard filter; cost and latency rank what survives it.

```
acceptable = candidates where tier >= (bestTier - tolerance)
winner     = argmax over acceptable of (costWeight * inverseNorm(cost)
                                        + latencyWeight * inverseNorm(latency))
```

**Consequences.**
- The router is predictable: "we will not use a model more than two tiers below the best
  available" is a statement an operator can verify against a log.
- The tolerance is relative to the pool, not absolute. A deployment with only two mid-tier models
  keeps both; the router does not start rejecting everything for lack of a tier-five option.
- `CHEAPEST` and `BEST_QUALITY` remain available as explicit profiles. Teams that care only about
  spend should select `CHEAPEST` rather than trying to express it through weights, which the
  previous design could never honour.

---

## ADR-0005: Quota is reserved before the call and never refunded

**Status:** accepted

**Context.** Metering after the fact produces elegant reports and no enforcement. A tenant can
exceed its budget by any amount before the first refusal arrives, and concurrent calls all pass
the check because none of them have recorded their spend yet.

**Decision.** Reserve `estimatedPromptTokens + maxTokens` before the model is called. Do not
refund the difference between the reservation and the actual usage.

**Consequences.**
- Concurrency is handled correctly for free: the reservation is atomic, so the last token can
  only be granted once.
- Reservations are biased upward. A large `maxTokens` on a request that produces a short answer
  over-reserves. Applications that need tight accounting should set a realistic `maxTokens`
  rather than the model's theoretical maximum.
- A cache hit consumes no quota, because caching is checked first. A tenant that has exhausted
  its budget can still be served an answer that was already paid for. This is intentional and
  covered by a test.

---

## ADR-0006: Multi-turn conversations bypass the semantic cache

**Status:** accepted

**Context.** Semantic caching assumes a question's meaning is a function of its text. That
assumption holds for a single-turn question and fails for a conversation: `那怎么办` means
different things depending on what preceded it. Two users asking `那怎么办` in different
conversations have different problems and must not share an answer.

**Decision.** Only requests containing exactly one user message are eligible for the cache.

**Consequences.**
- The cache does not help the multi-turn assistant, which is where a large share of traffic
  often goes.
- Extending the cache to conversations requires keying on the full turn history, which removes
  most of the deduplication benefit anyway. Left open rather than half-solved.

---

## ADR-0007: The audit log stores digests, not prompts

**Status:** accepted

**Context.** The tempting audit design stores the full prompt so that reviewers can see exactly
what was asked. That turns the audit log into a second copy of the most sensitive data in the
system, subject to the same retention and deletion obligations as the primary store, typically
with weaker access controls — and it will be the copy that survives the incident.

**Decision.** Store a SHA-256 digest of the canonical prompt. Retain model, tenant, outcome,
tokens, cost and latency in full.

**Consequences.**
- An auditor can prove that two calls carried the same question without seeing the question.
- An auditor cannot reconstruct what a user asked. Teams that need that for debugging must opt
  in explicitly to prompt retention with a short window, rather than receiving it by default.
- Deletion requests are still handled: `invalidateTenant` purges cached answers, and the digest
  contains no recoverable text.

---

## ADR-0008: No HTTP layer in the core

**Status:** accepted

**Context.** The fastest path to adoption is to ship a client for the most popular vendor.
That client then becomes the thing people depend on, and every pricing change, deprecation and
API quirk lands in this repository's issue tracker.

**Decision.** Ship no transport. `LlmProvider` is the seam, and `SimulatedProvider` exists so
that the whole pipeline can be exercised without one.

**Consequences.**
- The first fifteen minutes require writing an adapter. This costs adoption, and reference
  providers are on the roadmap for exactly this reason.
- In exchange, Tollgate has no opinion about how you call a model, and cannot break when a
  vendor changes a field name.

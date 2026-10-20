# Security policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Report privately through GitHub's [Security Advisories](../../security/advisories/new) feature.
If that is unavailable to you, contact the maintainers listed in the repository profile.

Please include:

- A description of the issue and its impact.
- Steps to reproduce, or a failing test.
- Affected versions.
- Whether you intend to publish a write-up, and on what timeline.

You will receive an acknowledgement within 72 hours. We aim to ship a fix for confirmed issues
within 30 days, and will credit you in the release notes unless you prefer otherwise.

---

## Threat model

Tollgate sits between an application and an LLM provider. It handles prompts, tenant identifiers
and billing data. The relevant threats are not the usual web-application ones — there is no HTTP
surface and no authentication layer — but the following three.

### 1. Cross-tenant answer disclosure

**Threat.** Tenant A's question is semantically similar to tenant B's, and the cache serves A's
answer to B, exposing data that B was never meant to see.

**Mitigation.** Cache partitions are keyed by tenant. The partition key is the first argument to
every cache operation, and there is no code path that scans across partitions. Covered by
`InMemorySemanticCacheTest#isolatesTenants` and `TollgateTest#isolatesTenants`.

**When calling this in review:** the tenant identifier must never be derived from anything the
caller does not control. If your application passes a user-supplied string as the tenant, you have
removed this mitigation entirely.

### 2. Cross-request answer disclosure through literal values

**Threat.** `订单 12345 的物流` and `订单 67890 的物流` are 99% similar as strings. A similarity
match serves the wrong order's status to the wrong customer.

**Mitigation.** Similarity matching is gated on a literal fingerprint derived from every digit
run in the question (ADR-0003). Different identifiers mean the fingerprints differ, and matching
is refused before the vectors are compared. Covered by
`InMemorySemanticCacheTest#neverConfusesDifferentIds` and `TollgateTest#neverConfusesOrders`.

**Known gap.** Non-numeric identifiers — ticket keys, email addresses, names — are not yet part of
the fingerprint. If your traffic contains those and they change the answer, set
`cacheable(false)` on those requests until this is addressed.

### 3. Prompt disclosure through audit logs

**Threat.** The audit log becomes a second copy of the most sensitive data in the system, with
weaker access controls and no deletion path.

**Mitigation.** Audit events store a SHA-256 digest of the prompt, never the text (ADR-0007).
`invalidateTenant` purges cached answers when tenant data is deleted.

**What this does not protect against.** A digest is not encryption. For low-entropy prompts — a
yes/no question, a four-digit PIN — an attacker holding the digest can enumerate candidates and
confirm a guess. Do not treat the digest as a confidentiality control; treat it as an audit
correlation key.

---

## What Tollgate does not do

Stating these explicitly so that nobody assumes protection that is not there:

- **No authentication or authorisation.** Tollgate trusts the tenant identifier it is given.
  Deciding who may act as which tenant is your application's job.
- **No prompt injection defence.** Content coming back from the model is returned verbatim.
- **No PII detection or redaction.** Prompts are sent to providers as-is.
- **No encryption at rest.** In-memory caches and logs are plain heap structures.
- **No output filtering.** If a model produces harmful content, Tollgate passes it through.

These are deliberate scope decisions, not oversights. A governance layer that also tried to be an
authorisation layer and a content filter would be trusted for none of the three.

---

## Supported versions

| Version | Supported |
| --- | --- |
| 0.1.x | Yes, security fixes only |
| < 0.1 | No |

Pre-1.0, only the latest minor version receives security fixes.

---

## Dependency policy

`tollgate-core` has no third-party runtime dependencies, which is the single largest security
benefit of the design: there is no transitive supply chain to audit. `tollgate-spring-boot-starter`
depends only on Spring Boot's own artifacts.

Dependabot is configured to open pull requests for vulnerable transitive dependencies in the
starter module.

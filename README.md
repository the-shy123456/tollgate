<div align="center">

# Tollgate

**Governance, cost control and semantic caching for LLM traffic — built for teams that already run on the JVM.**

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#)
[![Tests](https://img.shields.io/badge/tests-99%20passing-brightgreen)](#)
[![Java](https://img.shields.io/badge/java-17%2B-orange)](#)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Status](https://img.shields.io/badge/status-0.1.0%20pre--release-yellow)](#)

[English](README.md) | [简体中文](README.zh-CN.md)

</div>

---

## The problem

Every team that ships an LLM feature ends up writing the same code. A retry loop. A cache keyed
on the prompt string. A counter that tries to guess which team is burning the budget. A throwing
`if` statement when someone forgets to set a timeout. None of it is the feature. All of it is on
the critical path.

The Python ecosystem solved this with gateways and agent frameworks. The JVM got a set of client
SDKs and a framework that assumes you want to rebuild the plumbing yourself.

The result is predictable: nobody knows what the AI bill is until it arrives, nobody can tell
which product line spent it, and one runaway loop can consume a quarter's budget overnight.

**Tollgate is the layer between your code and the model.**

```
your service  →  Tollgate  →  OpenAI / Qwen / DeepSeek / Ollama / your own endpoint
```

It does four things, and it does them on every call:

| Capability | What it answers |
| --- | --- |
| **Semantic cache** | Have we already answered this? |
| **Policy routing** | Which model is the right one for *this* request? |
| **Budget and rate limits** | May this tenant spend more? |
| **Metering and audit** | Who spent what, and what was asked? |

---

## Why not just use a client SDK?

Because SDKs answer "how do I call a model". They do not answer any of the questions above, and
they are the wrong layer to answer them: governance has to survive a vendor swap, outlive a
pricing change, and apply uniformly across models that do not share an API shape.

Tollgate knows nothing about HTTP. It talks to `LlmProvider`, an interface you implement in a few
dozen lines. Transports churn every quarter; policy does not.

---

## 60-second start

**1. Add the dependency**

```xml
<dependency>
  <groupId>io.tollgate</groupId>
  <artifactId>tollgate-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

**2. Declare a provider** — any `LlmProvider` bean will do. For a first run with no API key:

```java
@Bean
LlmProvider provider() {
    return new SimulatedProvider("demo", List.of(
        ModelSpec.of("nano",     "demo", 0.05, 0.10,   8_000, 2,  300),
        ModelSpec.of("standard", "demo", 0.80, 2.40,  32_000, 3,  700),
        ModelSpec.of("frontier", "demo", 5.00, 15.00, 128_000, 5, 1_800)));
}
```

**3. Call it**

```java
@Service
public class SupportService {

    private final Tollgate tollgate;

    SupportService(Tollgate tollgate) {
        this.tollgate = tollgate;
    }

    String answer(String tenant, String question) {
        return tollgate.call(tenant, question).content();
    }
}
```

That is the whole integration. Caching, routing, metering and auditing are already active with
defaults that are safe for a first deployment.

**Or run the demo without writing anything:**

```bash
git clone https://github.com/tollgate-io/tollgate.git
cd tollgate
./mvnw -pl tollgate-example -am package -DskipTests
java -jar tollgate-example/target/tollgate-example-0.1.0.jar
```

---

## What it looks like

Real output from the bundled example:

```
1. Semantic cache - rephrasings hit, different questions do not
----------------------------------------------------------------
  Q: 如何申请退款                   -> hit=false model=standard cost=$0.000183
  Q: 请问我想申请退款呢                -> hit=true  model=standard cost=$0.000000
  Q: 退款流程是什么                  -> hit=false model=standard cost=$0.000186
  Q: 怎么重置登录密码                 -> hit=false model=standard cost=$0.000190
  cache hit rate: 25%  (entries: 3)

2. Routing - one prompt, three profiles
----------------------------------------------------------------
  CHEAPEST      -> model=nano      cost=$0.000008
  BALANCED      -> model=standard  cost=$0.000193
  BEST_QUALITY  -> model=frontier  cost=$0.001235

3. Quota - a tenant that runs out of budget
----------------------------------------------------------------
  call 1 -> ok    model=standard used=108/400 tokens
  call 2 -> ok    model=standard used=216/400 tokens
  call 3 -> ok    model=standard used=324/400 tokens
  call 4 -> REJECTED (Quota exhausted for tenant 'budget-team': requested 108 tokens)

4. Cost report
----------------------------------------------------------------
    Cost incurred    : $0.001995
    Cost avoided     : $0.000183
    Without Tollgate : $0.002178
    Saved            : 8.4%
```

Notice line two. `如何申请退款` and `请问我想申请退款呢` are different strings, the same question,
and they collapse onto one cache entry — without an embedding model, without a network call.

---

## Design principles

**Zero runtime dependencies in the core.** `tollgate-core` depends on the JDK and nothing else.
No Jackson, no Guava, no Netty, no SLF4J. A governance library that drags in a dependency tree is
a governance library that gets rejected at the architecture review.

**Correctness over hit rate.** A cache that serves the wrong answer is worse than no cache. Two
mechanisms exist purely to prevent that, and both are covered by tests:

- *Question framing is stripped where it is safe to strip it.* `如何申请退款`, `请问我想申请退款呢`
  and `申请退款` become one key. Content words are never touched, so `退款` and `退货` stay apart.
- *Literal values are gated.* `订单 12345 的物流` and `订单 67890 的物流` are 99% similar as strings
  and completely different as requests. Their digit fingerprints differ, so similarity matching
  is refused outright. See [ADR-0003](docs/design-decisions.md#adr-0003).

**Reserve before you spend.** Quota is deducted before the model is called, using a conservative
estimate, and never refunded. Over-reserving slightly is the intended behaviour: a tenant that
pays for a few unused tokens is a smaller problem than one that quietly exceeds its budget
because accounting ran after the fact.

**Governance must be reversible.** Every component — router, cache, embedder, audit logger,
quota store — is an interface with a working default and a documented seam. Teams adopt the
report first, then the budget, then the enforcement.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  call(request)                                               │
│                                                              │
│  1. canonicalise ──► 2. cache look-up ──► hit ──► return     │
│         │                    │ miss                          │
│         │                    ▼                               │
│         │              3. route                              │
│         │                    ▼                               │
│         │              4. reserve quota                      │
│         │                    ▼                               │
│         │              5. invoke (with fallback)             │
│         │                    ▼                               │
│         └────────────► 6. price, store, meter, audit         │
└──────────────────────────────────────────────────────────────┘
```

The order matters. Cache before routing means a hit never consumes quota and never touches a
provider — a cache hit is still served to a tenant that has exhausted its budget, because the
answer was already paid for. Reserve before invoke means the budget cannot be overrun by a
concurrent burst.

Full detail, including module boundaries and extension points, in
[docs/architecture.md](docs/architecture.md).

---

## Configuration

```yaml
tollgate:
  cache:
    enabled: true
    similarity-threshold: 0.75     # raise for fewer, safer hits
    ttl: 6h
    max-entries-per-tenant: 2000

  quota:
    enabled: false                 # turn on once you know what a normal month costs
    tokens-per-window: 1000000
    window: 30d
    max-tokens-per-second: 0       # 0 = unlimited
    action: reject                 # reject | degrade | log_only

  routing:
    quality-tolerance: 2           # tiers below the best model that stay acceptable
    cost-weight: 0.7
    latency-weight: 0.3
    max-fallbacks: 1

  audit:
    enabled: true
    capacity: 1000
```

Every property is documented in [docs/configuration.md](docs/configuration.md).

---

## Plugging in a real model

`LlmProvider` is the only interface you have to write:

```java
public final class MyProvider implements LlmProvider {

    @Override
    public String id() {
        return "my-provider";
    }

    @Override
    public List<ModelSpec> models() {
        return List.of(ModelSpec.of("my-model", id(), 0.5, 1.5, 32_000, 4, 900));
    }

    @Override
    public LlmResponse complete(ModelSpec model, LlmRequest request) {
        // call your vendor, map the response
        return LlmResponse.fromProvider(content, model.id(),
                promptTokens, completionTokens, latencyMillis, finishReason);
    }
}
```

Report real token usage. If your vendor omits it, estimate — returning zero makes the call free
in every report downstream, which is how budgets quietly stop meaning anything.

---

## Status

**0.1.0, pre-release.** The governance model is settled; the API surface is not. Expect renames
before 1.0. The module layout, the cache safety guarantees and the configuration keys are stable
enough to build on.

Known limitations, stated plainly rather than buried:

- The semantic cache is in-process and scans entries linearly beyond the exact-match fast path.
  It is sized for thousands of entries per tenant, not millions. Back it with a vector store
  before you outgrow that.
- Quota counters are per-instance. In a multi-instance deployment the effective budget is the
  configured budget times the instance count. A shared store is the intended extension.
- `HashingEmbeddingProvider` recognises near-duplicate wording, not paraphrase. It cannot know
  that "car" and "automobile" mean the same thing. Plug in a real embedding model for that.
- Routes are chosen from declared model metadata, not from measured latency. There is no probe
  loop, because probing costs the money the gateway exists to save.

---

## Roadmap

- [ ] Redis-backed `SemanticCache` and `QuotaStore` for multi-instance deployments
- [ ] Micrometer registry: hit rate, spend per tenant, quota rejects
- [ ] Actuator endpoint exposing the cost report
- [ ] Streaming responses (`Flow`/`Publisher`) with mid-stream cost accounting
- [ ] Prompt versioning and A/B assignment
- [ ] Reference providers: OpenAI, Qwen (DashScope), DeepSeek, Ollama

Discussions and votes happen in [GitHub Discussions](../../discussions).

---

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it explains
how to run the build, what the test expectations are, and the two design rules that are not
negotiable (zero third-party dependencies in core, and no cache hit that can cross a tenant or a
literal value).

```bash
./mvnw verify          # compile, test, package
```

---

## License

Apache License 2.0. See [LICENSE](LICENSE).

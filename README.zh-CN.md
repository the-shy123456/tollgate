<div align="center">

# Tollgate

**面向 JVM 团队的 LLM 流量治理、成本控制与语义缓存层。**

[![CI](https://github.com/the-shy123456/tollgate/actions/workflows/ci.yml/badge.svg)](https://github.com/the-shy123456/tollgate/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/java-17%2B-orange)](pom.xml)
[![Runtime deps](https://img.shields.io/badge/core%20runtime%20deps-none-brightgreen)](docs/design-decisions.md#adr-0001)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Status](https://img.shields.io/badge/status-0.1.0%20pre--release-yellow)](#)

[English](README.md) | [简体中文](README.zh-CN.md)

</div>

---

## 要解决的问题

每个上线 AI 功能的团队，最后都会写同一批代码：一个重试循环、一个用 prompt 原文做 key 的缓存、
一个说不清是哪个部门在花钱的计数器、一个超时忘了设就裸奔的调用。这些都不是业务功能，
但全都压在请求链路上。

Python 那边用网关和 Agent 框架把这件事解决了。JVM 这边得到的是一堆客户端 SDK，
以及一个假设你愿意自己搭管线的框架。

结果可以预料：账单来了才知道花了多少，说不清是哪条业务线花的，一个跑飞的重试循环
一夜能烧掉一个季度的预算。

**Tollgate 就是你代码和模型之间的那一层。**

```
你的服务  →  Tollgate  →  OpenAI / 通义 / DeepSeek / Ollama / 自建端点
```

它在每次调用上做四件事：

| 能力 | 回答的问题 |
| --- | --- |
| **语义缓存** | 这个问题我们是不是已经答过了？ |
| **策略路由** | 这一次请求，该用哪个模型？ |
| **预算与限流** | 这个租户还能不能再花？ |
| **计量与审计** | 谁花了多少，问了什么？ |

---

## 为什么不直接用客户端 SDK

因为 SDK 只回答"怎么调模型"，不回答上面四个问题，而且它也不是回答这些问题的正确层次：
治理逻辑要能扛住换厂商、要能比一次调价活得更久、要能对 API 形态完全不同的模型一视同仁。

Tollgate 完全不懂 HTTP。它只认 `LlmProvider` 这个接口，你自己实现，几十行。
传输层每季度都在变，治理策略不会。

---

## 60 秒上手

**1. 加依赖**

```xml
<dependency>
  <groupId>io.tollgate</groupId>
  <artifactId>tollgate-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

**2. 声明一个 provider**（第一次跑，不需要任何 API key）

```java
@Bean
LlmProvider provider() {
    return new SimulatedProvider("demo", List.of(
        ModelSpec.of("nano",     "demo", 0.05, 0.10,   8_000, 2,  300),
        ModelSpec.of("standard", "demo", 0.80, 2.40,  32_000, 3,  700),
        ModelSpec.of("frontier", "demo", 5.00, 15.00, 128_000, 5, 1_800)));
}
```

**3. 调用**

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

集成就这么多。缓存、路由、计量、审计已经按安全的默认值全部生效。

**或者一行代码不写，直接看效果：**

```bash
git clone https://github.com/the-shy123456/tollgate.git
cd tollgate
./mvnw -pl tollgate-example -am package -DskipTests
java -jar tollgate-example/target/tollgate-example-0.1.0.jar
```

---

## 跑起来是什么样

下面是示例工程的真实输出：

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

注意第二行。`如何申请退款` 和 `请问我想申请退款呢` 是两个不同的字符串、同一个问题，
它们塌缩到了同一条缓存记录上——没有调 embedding 模型，没有走网络。

---

## 设计原则

**core 模块零第三方运行时依赖。** `tollgate-core` 只依赖 JDK。没有 Jackson、没有 Guava、
没有 Netty、没有 SLF4J。一个治理库如果把依赖树拖进来，它就会在架构评审上被毙掉。

**正确性优先于命中率。** 返回错误答案的缓存，比没有缓存糟糕得多。有两套机制专门防这件事，
而且都有测试覆盖：

- **疑问框架词只在安全的位置剥离。** `如何申请退款`、`请问我想申请退款呢`、`申请退款` 归一到
  同一个 key。内容词永不触碰，所以 `退款` 和 `退货` 始终分开。
- **字面量加护栏。** `订单 12345 的物流` 和 `订单 67890 的物流` 字符串相似度 99%，
  但作为请求完全是两回事。它们的数字指纹不同，所以直接拒绝相似匹配。
  见 [ADR-0003](docs/design-decisions.md#adr-0003)。

**先预留，再花钱。** 配额在调模型之前就按保守估算扣掉，且从不退还。稍微多留一点是刻意的：
一个租户为自己没用上的几个 token 付钱，比一个租户因为"先调用后记账"而悄悄超支要好得多。

**治理必须可逆。** 路由、缓存、向量化、审计、配额，每一个都是带默认实现的接口，都留了
明确的替换口。团队的接入顺序应该是：先看报表，再定预算，最后才开强制。

---

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│  call(request)                                               │
│                                                              │
│  1. 归一化 ──► 2. 查缓存 ──► 命中 ──► 直接返回                 │
│         │              │ 未命中                                │
│         │              ▼                                     │
│         │          3. 路由                                    │
│         │              ▼                                     │
│         │          4. 预留配额                                 │
│         │              ▼                                     │
│         │          5. 调用（带降级兜底）                        │
│         │              ▼                                     │
│         └────────► 6. 计价、写缓存、记账、审计                  │
└──────────────────────────────────────────────────────────────┘
```

顺序不能乱。缓存在路由之前，意味着命中永远不消耗配额、不碰 provider——一个预算耗尽的租户
依然能拿到缓存答案，因为那笔钱早就付过了。配额在调用之前，意味着并发突发也冲不破预算。

模块边界与扩展点的完整说明见 [docs/architecture.md](docs/architecture.md)。

---

## 配置

```yaml
tollgate:
  cache:
    enabled: true
    similarity-threshold: 0.75     # 调高 = 命中更少但更安全
    ttl: 6h
    max-entries-per-tenant: 2000

  quota:
    enabled: false                 # 先跑一周报表，再打开
    tokens-per-window: 1000000
    window: 30d
    max-tokens-per-second: 0       # 0 = 不限
    action: reject                 # reject | degrade | log_only

  routing:
    quality-tolerance: 2           # 比最好模型低几档仍可接受
    cost-weight: 0.7
    latency-weight: 0.3
    max-fallbacks: 1

  audit:
    enabled: true
    capacity: 1000
```

全部配置项见 [docs/configuration.md](docs/configuration.md)。

---

## 接入真实模型

只需要实现 `LlmProvider`：

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
        // 调厂商，映射响应
        return LlmResponse.fromProvider(content, model.id(),
                promptTokens, completionTokens, latencyMillis, finishReason);
    }
}
```

务必上报真实的 token 用量。厂商没给就自己估——返回 0 会让这次调用在所有下游报表里变成免费，
预算是这样一点点失去意义的。

---

## 项目状态

**0.1.0，预发布。** 治理模型已经定型，API 表面还没。1.0 之前会有重命名。
模块划分、缓存安全保证、配置键已经稳定到可以基于它开发。

已知限制，直说而不是藏着：

- 语义缓存是进程内的，除精确匹配快路径外是线性扫描。它能撑到每租户几千条，撑不到百万级。
  超出之前请换向量库。
- 配额计数器是按实例算的。多实例部署时，实际预算是配置值乘以实例数。共享存储是预留的扩展点。
- `HashingEmbeddingProvider` 认的是"措辞近似"，不是"语义改写"。它不知道 car 和 automobile
  是一个意思。要这个能力请接入真实的 embedding 模型。
- 路由依据的是模型元数据里声明的延迟，不是实测值。这里没有探活循环，因为探活本身就在花
  这个网关要帮你省的钱。

---

## 路线图

- [ ] Redis 版 `SemanticCache` 与 `QuotaStore`，支持多实例
- [ ] Micrometer 指标：命中率、分租户花费、配额拒绝数
- [ ] Actuator 端点暴露成本报表
- [ ] 流式响应（`Flow`/`Publisher`）与中途计价
- [ ] Prompt 版本管理与 A/B 分流
- [ ] 官方 Provider：OpenAI、通义（DashScope）、DeepSeek、Ollama

讨论与投票在 [GitHub Discussions](../../discussions)。

---

## 参与贡献

欢迎 issue 和 PR。请先读 [CONTRIBUTING.md](CONTRIBUTING.md)——里面有怎么跑构建、测试要求是什么，
以及两条不可谈判的设计红线（core 零三方依赖；任何可能跨租户或跨字面量的缓存命中都不允许）。

```bash
./mvnw verify          # 编译、测试、打包
```

---

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。

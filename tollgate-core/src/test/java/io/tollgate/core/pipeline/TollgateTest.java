/*
 * Copyright 2026 Tollgate Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tollgate.core.pipeline;

import io.tollgate.core.audit.AuditEvent;
import io.tollgate.core.audit.InMemoryAuditLog;
import io.tollgate.core.exception.ProviderException;
import io.tollgate.core.exception.QuotaExceededException;
import io.tollgate.core.meter.CostReport;
import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.LlmResponse;
import io.tollgate.core.model.Message;
import io.tollgate.core.model.ModelSpec;
import io.tollgate.core.model.RoutingProfile;
import io.tollgate.core.provider.LlmProvider;
import io.tollgate.core.quota.InMemoryQuotaStore;
import io.tollgate.core.quota.QuotaAction;
import io.tollgate.core.quota.QuotaPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TollgateTest {

    private static final ModelSpec CHEAP =
            ModelSpec.of("cheap", "demo", 0.10, 0.20, 8_000, 2, 500);
    private static final ModelSpec MID =
            ModelSpec.of("mid", "demo", 1.00, 3.00, 32_000, 3, 800);
    private static final ModelSpec PREMIUM =
            ModelSpec.of("premium", "demo", 5.00, 15.00, 128_000, 5, 2_000);

    @Test
    @DisplayName("returns a priced response on the first call")
    void pricesTheFirstCall() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        LlmResponse response = tollgate.call(LlmRequest.builder()
                .tenant("acme").user("hello").maxTokens(16).build());

        assertThat(response.content()).isEqualTo("answer-cheap");
        assertThat(response.cacheHit()).isFalse();
        assertThat(response.promptTokens()).isEqualTo(100);
        assertThat(response.completionTokens()).isEqualTo(50);
        assertThat(response.costUsd()).isCloseTo(2.0e-5, within(1e-9));
        assertThat(response.traceId()).isNotBlank();
    }

    @Test
    @DisplayName("serves the second identical question from cache without calling the provider")
    void servesRepeatsFromCache() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        LlmResponse first = tollgate.call("acme", "如何申请退款");
        LlmResponse second = tollgate.call("acme", "请问我想申请退款呢");

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.content()).isEqualTo(first.content());
        assertThat(second.costUsd()).isZero();
        assertThat(tollgate.cacheStats().hitRate()).isCloseTo(0.5d, within(1e-9));
    }

    @Test
    @DisplayName("bypasses the cache when the request opts out")
    void bypassesCacheWhenAsked() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        tollgate.call(LlmRequest.builder().tenant("acme").user("hi").cacheable(false).build());
        tollgate.call(LlmRequest.builder().tenant("acme").user("hi").cacheable(false).build());

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("bypasses the cache for multi-turn conversations")
    void bypassesCacheForMultiTurn() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        LlmRequest multiTurn = LlmRequest.builder()
                .tenant("acme")
                .user("hi")
                .message(Message.assistant("hello"))
                .user("hi")
                .build();

        tollgate.call(multiTurn);
        tollgate.call(multiTurn);

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("never shares answers between tenants")
    void isolatesTenants() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        tollgate.call("acme", "如何申请退款");
        tollgate.call("globex", "如何申请退款");

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("never serves one order's answer for another order")
    void neverConfusesOrders() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        tollgate.call("acme", "订单 12345 的物流到哪了");
        LlmResponse second = tollgate.call("acme", "订单 67890 的物流到哪了");

        assertThat(second.cacheHit()).isFalse();
        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("routes by profile")
    void routesByProfile() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP, MID, PREMIUM));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        assertThat(tollgate.call(request(RoutingProfile.CHEAPEST, "q1")).model()).isEqualTo("cheap");
        assertThat(tollgate.call(request(RoutingProfile.BEST_QUALITY, "q2")).model()).isEqualTo("premium");
        assertThat(tollgate.call(request(RoutingProfile.BALANCED, "q3")).model()).isEqualTo("mid");
    }

    @Test
    @DisplayName("honours a pinned model")
    void honoursPinnedModel() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP, MID, PREMIUM));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        LlmRequest pinned = LlmRequest.builder()
                .tenant("acme").user("hi").model("premium").build();

        assertThat(tollgate.call(pinned).model()).isEqualTo("premium");
    }

    @Test
    @DisplayName("rejects a call when the tenant is out of budget")
    void rejectsWhenOverBudget() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        InMemoryQuotaStore quota = new InMemoryQuotaStore(
                new QuotaPolicy(15, Duration.ofDays(1), 0, QuotaAction.REJECT));
        Tollgate tollgate = Tollgate.builder().provider(provider).quota(quota, quota.policy()).build();

        tollgate.call(LlmRequest.builder().tenant("acme").user("hi").maxTokens(10).build());

        assertThatThrownBy(() -> tollgate.call(
                LlmRequest.builder().tenant("acme").user("different question").maxTokens(10).build()))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("acme");
    }

    @Test
    @DisplayName("serves a cache hit even when the tenant is out of budget")
    void servesCacheEvenWhenOverBudget() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        InMemoryQuotaStore quota = new InMemoryQuotaStore(
                new QuotaPolicy(30, Duration.ofDays(1), 0, QuotaAction.REJECT));
        Tollgate tollgate = Tollgate.builder().provider(provider).quota(quota, quota.policy()).build();

        LlmRequest request = LlmRequest.builder()
                .tenant("acme").user("如何申请退款").maxTokens(10).build();
        tollgate.call(request);

        LlmResponse cached = tollgate.call(request);

        assertThat(cached.cacheHit()).isTrue();
    }

    @Test
    @DisplayName("degrades to a cheaper model instead of rejecting when configured to")
    void degradesInsteadOfRejecting() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP, MID, PREMIUM));
        InMemoryQuotaStore quota = new InMemoryQuotaStore(
                new QuotaPolicy(20, Duration.ofDays(1), 0, QuotaAction.DEGRADE));
        Tollgate tollgate = Tollgate.builder().provider(provider).quota(quota, quota.policy()).build();

        LlmResponse first = tollgate.call(request(RoutingProfile.BEST_QUALITY));
        LlmResponse second = tollgate.call(LlmRequest.builder()
                .tenant("acme").user("a different question").maxTokens(10)
                .routingProfile(RoutingProfile.BEST_QUALITY).build());

        assertThat(first.model()).isEqualTo("premium");
        assertThat(second.model()).isEqualTo("cheap");
    }

    @Test
    @DisplayName("falls back to another model when a provider fails")
    void fallsBackWhenProviderFails() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP, MID, PREMIUM));
        provider.failFor("premium");
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        LlmResponse response = tollgate.call(request(RoutingProfile.BEST_QUALITY));

        assertThat(response.model()).isEqualTo("mid");
        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("surfaces a provider failure when no fallback exists")
    void surfacesFailureWhenNoFallbackExists() {
        StubProvider provider = new StubProvider("demo", List.of(PREMIUM));
        provider.failFor("premium");
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        assertThatThrownBy(() -> tollgate.call(request(RoutingProfile.BEST_QUALITY)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("premium");
    }

    @Test
    @DisplayName("reports incurred and avoided cost")
    void reportsIncurredAndAvoidedCost() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        tollgate.call("acme", "如何申请退款");
        tollgate.call("acme", "如何申请退款");
        tollgate.call("acme", "如何申请退款");

        CostReport report = tollgate.report();
        assertThat(report.totalCalls()).isEqualTo(3);
        assertThat(report.cacheHits()).isEqualTo(2);
        assertThat(report.totalCostUsd()).isCloseTo(2.0e-5, within(1e-9));
        assertThat(report.savedCostUsd()).isCloseTo(4.0e-5, within(1e-9));
        assertThat(report.hypotheticalCostUsd()).isCloseTo(6.0e-5, within(1e-9));
        assertThat(report.savingsRate()).isCloseTo(2d / 3d, within(1e-9));
        assertThat(report.savedTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("attributes spend per tenant")
    void attributesSpendPerTenant() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        tollgate.call("acme", "question one");
        tollgate.call("globex", "question two");
        tollgate.call("globex", "question three");

        CostReport report = tollgate.report();
        assertThat(report.byTenant()).containsOnlyKeys("acme", "globex");
        assertThat(report.byTenant().get("acme").calls()).isEqualTo(1);
        assertThat(report.byTenant().get("globex").calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("notifies usage listeners without storing prompt text")
    void auditsWithoutStoringPrompts() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        InMemoryAuditLog audit = new InMemoryAuditLog();
        Tollgate tollgate = Tollgate.builder().provider(provider).auditLogger(audit).build();

        tollgate.call("acme", "我的身份证号是 123456");

        List<AuditEvent> events = tollgate.auditTrail(10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).outcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
        assertThat(events.get(0).promptDigest()).hasSize(64);
        assertThat(events.get(0).promptDigest()).doesNotContain("123456");
        assertThat(events.get(0).tenant()).isEqualTo("acme");
    }

    @Test
    @DisplayName("records a rejection in the audit trail")
    void auditsRejections() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        InMemoryQuotaStore quota = new InMemoryQuotaStore(
                new QuotaPolicy(5, Duration.ofDays(1), 0, QuotaAction.REJECT));
        InMemoryAuditLog audit = new InMemoryAuditLog();
        Tollgate tollgate = Tollgate.builder()
                .provider(provider).quota(quota, quota.policy()).auditLogger(audit).build();

        assertThatThrownBy(() -> tollgate.call(
                LlmRequest.builder().tenant("acme").user("hi").maxTokens(10).build()))
                .isInstanceOf(QuotaExceededException.class);

        assertThat(tollgate.auditTrail(10))
                .extracting(AuditEvent::outcome)
                .containsExactly(AuditEvent.Outcome.QUOTA_REJECTED);
    }

    @Test
    @DisplayName("exposes quota state")
    void exposesQuotaState() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP));
        InMemoryQuotaStore quota = new InMemoryQuotaStore(
                new QuotaPolicy(100_000, Duration.ofDays(1), 0, QuotaAction.REJECT));
        Tollgate tollgate = Tollgate.builder().provider(provider).quota(quota, quota.policy()).build();

        tollgate.call(LlmRequest.builder().tenant("acme").user("hi").maxTokens(10).build());

        assertThat(tollgate.quota("acme").usedTokens()).isPositive();
    }

    @Test
    @DisplayName("requires at least one provider")
    void requiresAProvider() {
        assertThatThrownBy(() -> Tollgate.builder().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("lists every registered model")
    void listsRegisteredModels() {
        StubProvider provider = new StubProvider("demo", List.of(CHEAP, MID, PREMIUM));
        Tollgate tollgate = Tollgate.builder().provider(provider).build();

        assertThat(tollgate.models()).extracting(ModelSpec::id)
                .containsExactly("cheap", "mid", "premium");
    }

    private LlmRequest request(RoutingProfile profile) {
        return request(profile, "hello");
    }

    private LlmRequest request(RoutingProfile profile, String prompt) {
        return LlmRequest.builder()
                .tenant("acme")
                .user(prompt)
                .maxTokens(10)
                .routingProfile(profile)
                .build();
    }

    /** A provider that returns fixed token counts and can be told to fail for a given model. */
    static final class StubProvider implements LlmProvider {

        private final String id;
        private final List<ModelSpec> models;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> failedModels = new CopyOnWriteArrayList<>();

        StubProvider(String id, List<ModelSpec> models) {
            this.id = id;
            this.models = List.copyOf(new ArrayList<>(models));
        }

        void failFor(String modelId) {
            failedModels.add(modelId);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<ModelSpec> models() {
            return models;
        }

        @Override
        public LlmResponse complete(ModelSpec model, LlmRequest request) {
            calls.incrementAndGet();
            if (failedModels.contains(model.id())) {
                throw new IllegalStateException("simulated outage for " + model.id());
            }
            return LlmResponse.fromProvider("answer-" + model.id(), model.id(), 100, 50, 10L, "stop");
        }
    }
}

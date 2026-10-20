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
package io.tollgate.core.meter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class UsageRegistryTest {

    @Test
    @DisplayName("aggregates calls into a cost report")
    void aggregatesCalls() {
        UsageRegistry registry = new UsageRegistry();
        registry.onUsage(record("acme", "cheap", 100, 50, 0.001d, 0d, false));
        registry.onUsage(record("acme", "cheap", 100, 50, 0.001d, 0d, false));

        CostReport report = registry.report();

        assertThat(report.totalCalls()).isEqualTo(2);
        assertThat(report.totalTokens()).isEqualTo(300);
        assertThat(report.totalCostUsd()).isCloseTo(0.002d, within(1e-9));
        assertThat(report.cacheHitRate()).isZero();
    }

    @Test
    @DisplayName("separates billed from saved quantities on cache hits")
    void separatesBilledFromSaved() {
        UsageRegistry registry = new UsageRegistry();
        registry.onUsage(record("acme", "cheap", 100, 50, 0.001d, 0d, false));
        registry.onUsage(record("acme", "cheap", 0, 0, 0d, 0.001d, true));

        CostReport report = registry.report();

        assertThat(report.totalCostUsd()).isCloseTo(0.001d, within(1e-9));
        assertThat(report.savedCostUsd()).isCloseTo(0.001d, within(1e-9));
        assertThat(report.hypotheticalCostUsd()).isCloseTo(0.002d, within(1e-9));
        assertThat(report.savingsRate()).isCloseTo(0.5d, within(1e-9));
        assertThat(report.cacheHitRate()).isCloseTo(0.5d, within(1e-9));
    }

    @Test
    @DisplayName("breaks down by tenant, most expensive first")
    void breaksDownByTenant() {
        UsageRegistry registry = new UsageRegistry();
        registry.onUsage(record("small", "cheap", 10, 10, 0.001d, 0d, false));
        registry.onUsage(record("big", "premium", 10, 10, 1.000d, 0d, false));

        assertThat(registry.report().byTenant().keySet()).containsExactly("big", "small");
    }

    @Test
    @DisplayName("breaks down by model")
    void breaksDownByModel() {
        UsageRegistry registry = new UsageRegistry();
        registry.onUsage(record("acme", "cheap", 10, 10, 0.001d, 0d, false));
        registry.onUsage(record("acme", "premium", 10, 10, 1.000d, 0d, false));

        Map<String, ModelUsage> byModel = registry.report().byModel();
        assertThat(byModel).containsKeys("cheap", "premium");
        assertThat(byModel.get("premium").costUsd()).isCloseTo(1.0d, within(1e-9));
        assertThat(byModel.get("premium").averageCostPerCall()).isCloseTo(1.0d, within(1e-9));
    }

    @Test
    @DisplayName("reports a zeroed record for an unknown tenant")
    void reportsUnknownTenant() {
        UsageRegistry registry = new UsageRegistry();

        TenantUsage usage = registry.tenantUsage("ghost");

        assertThat(usage.calls()).isZero();
        assertThat(usage.costUsd()).isZero();
        assertThat(usage.cacheHitRate()).isZero();
    }

    @Test
    @DisplayName("clears all totals on reset")
    void clearsOnReset() {
        UsageRegistry registry = new UsageRegistry();
        registry.onUsage(record("acme", "cheap", 10, 10, 0.001d, 0d, false));

        registry.reset();

        assertThat(registry.report().totalCalls()).isZero();
        assertThat(registry.report().byTenant()).isEmpty();
    }

    @Test
    @DisplayName("renders a text report containing the key figures")
    void rendersTextReport() {
        UsageRegistry registry = new UsageRegistry();
        registry.onUsage(record("acme", "cheap", 100, 50, 0.001d, 0d, false));

        String text = registry.report().toText();

        assertThat(text).contains("Tollgate cost report").contains("acme").contains("cheap");
    }

    private static UsageRecord record(String tenant, String model, int promptTokens,
                                      int completionTokens, double cost, double savedCost,
                                      boolean cacheHit) {
        return new UsageRecord("trace", tenant, model, "demo", promptTokens, completionTokens,
                cacheHit ? promptTokens + completionTokens : 0, cost, savedCost, cacheHit,
                10L, System.currentTimeMillis(), Map.of());
    }
}

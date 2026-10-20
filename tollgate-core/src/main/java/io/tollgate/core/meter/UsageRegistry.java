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

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * An in-process {@link UsageListener} that keeps running totals and can produce a
 * {@link CostReport} on demand.
 *
 * <p>Aggregation uses adders rather than locks, so recording a call costs a few atomic
 * increments and never blocks. The trade-off is that a report taken while traffic is flowing is
 * eventually consistent: it may miss a call that is being recorded at that instant. For a cost
 * report that is entirely acceptable; for billing a customer to the cent it is not, which is why
 * long-term accounting belongs in a {@link UsageListener} that writes to durable storage.
 *
 * <p>This class is thread safe.
 */
public final class UsageRegistry implements UsageListener {

    private final Map<String, Counter> tenants = new ConcurrentHashMap<>();
    private final Map<String, Counter> models = new ConcurrentHashMap<>();
    private final Counter total = new Counter();

    @Override
    public void onUsage(UsageRecord record) {
        total.add(record);
        tenants.computeIfAbsent(record.tenant(), key -> new Counter()).add(record);
        models.computeIfAbsent(record.model(), key -> new Counter()).add(record);
    }

    /**
     * Produces a cost report from everything recorded so far.
     *
     * @return an immutable report, with tenant and model breakdowns ordered by cost descending
     */
    public CostReport report() {
        Map<String, TenantUsage> tenantUsages = new LinkedHashMap<>();
        tenants.entrySet().stream()
                .map(entry -> entry.getValue().toTenantUsage(entry.getKey()))
                .sorted(Comparator.comparingDouble(TenantUsage::costUsd).reversed()
                        .thenComparing(TenantUsage::tenant))
                .forEach(usage -> tenantUsages.put(usage.tenant(), usage));

        Map<String, ModelUsage> modelUsages = new LinkedHashMap<>();
        models.entrySet().stream()
                .map(entry -> entry.getValue().toModelUsage(entry.getKey()))
                .sorted(Comparator.comparingDouble(ModelUsage::costUsd).reversed()
                        .thenComparing(ModelUsage::model))
                .forEach(usage -> modelUsages.put(usage.model(), usage));

        return new CostReport(
                total.calls.sum(),
                total.cacheHits.sum(),
                total.promptTokens.sum(),
                total.completionTokens.sum(),
                total.savedTokens.sum(),
                total.costUsd.sum(),
                total.savedCostUsd.sum(),
                Collections.unmodifiableMap(tenantUsages),
                Collections.unmodifiableMap(modelUsages));
    }

    /**
     * Returns the aggregated usage of a single tenant.
     *
     * @param tenant the tenant identifier
     * @return the usage, or a zeroed record if the tenant never called
     */
    public TenantUsage tenantUsage(String tenant) {
        Counter counter = tenants.get(tenant);
        return counter == null ? new TenantUsage(tenant, 0, 0, 0, 0d, 0d)
                : counter.toTenantUsage(tenant);
    }

    /** Clears every recorded total. */
    public void reset() {
        tenants.clear();
        models.clear();
        total.clear();
    }

    private static final class Counter {

        private final LongAdder calls = new LongAdder();
        private final LongAdder cacheHits = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final LongAdder savedTokens = new LongAdder();
        private final DoubleAdder costUsd = new DoubleAdder();
        private final DoubleAdder savedCostUsd = new DoubleAdder();

        private void add(UsageRecord record) {
            calls.increment();
            if (record.cacheHit()) {
                cacheHits.increment();
            }
            promptTokens.add(record.promptTokens());
            completionTokens.add(record.completionTokens());
            savedTokens.add(record.savedTokens());
            costUsd.add(record.costUsd());
            savedCostUsd.add(record.savedCostUsd());
        }

        private void clear() {
            calls.reset();
            cacheHits.reset();
            promptTokens.reset();
            completionTokens.reset();
            savedTokens.reset();
            costUsd.reset();
            savedCostUsd.reset();
        }

        private TenantUsage toTenantUsage(String tenant) {
            return new TenantUsage(tenant, calls.sum(), cacheHits.sum(),
                    promptTokens.sum() + completionTokens.sum(), costUsd.sum(), savedCostUsd.sum());
        }

        private ModelUsage toModelUsage(String model) {
            return new ModelUsage(model, calls.sum(),
                    promptTokens.sum() + completionTokens.sum(), costUsd.sum());
        }
    }
}

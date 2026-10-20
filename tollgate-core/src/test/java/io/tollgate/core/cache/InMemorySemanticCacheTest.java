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
package io.tollgate.core.cache;

import io.tollgate.core.model.LlmResponse;
import io.tollgate.core.util.LiteralGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InMemorySemanticCacheTest {

    private static final float[] V1 = {1f, 0f, 0f};
    private static final float[] V1_NEAR = {0.95f, 0.31f, 0f};
    private static final float[] V_ORTHOGONAL = {0f, 1f, 0f};
    private static final float[] V3 = {0f, 0f, 1f};

    @Test
    @DisplayName("returns the entry for an exact canonical match")
    void findsExactMatch() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        Optional<CacheEntry> hit = lookup(cache, "acme", "fp", "申请退款", V1);

        assertThat(hit).isPresent();
        assertThat(hit.get().content()).isEqualTo("answer");
        assertThat(cache.stats().exactHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns a similar entry when the threshold is met")
    void findsSemanticMatch() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        Optional<CacheEntry> hit = lookup(cache, "acme", "fp", "申请退款流程", V1_NEAR);

        assertThat(hit).isPresent();
        assertThat(cache.stats().semanticHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects a match below the threshold")
    void rejectsWeakMatch() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        Optional<CacheEntry> hit = lookup(cache, "acme", "fp", "重置密码", V_ORTHOGONAL);

        assertThat(hit).isEmpty();
        assertThat(cache.stats().misses()).isEqualTo(1);
    }

    @Test
    @DisplayName("never serves an answer across different order ids, however similar the wording")
    void neverConfusesDifferentIds() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "订单 12345 的物流到哪了", V1, 120, 0.0024d));

        assertThat(lookup(cache, "acme", "fp", "订单 12345 的物流到哪了", V1_NEAR)).isPresent();
        assertThat(lookup(cache, "acme", "fp", "订单 67890 的物流到哪了", V1_NEAR)).isEmpty();
    }

    @Test
    @DisplayName("never serves one tenant's answer to another tenant")
    void isolatesTenants() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        assertThat(lookup(cache, "globex", "fp", "申请退款", V1)).isEmpty();
    }

    @Test
    @DisplayName("keeps instruction variants apart")
    void isolatesSystemPrompts() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp-a", "申请退款", V1, 120, 0.0024d));

        assertThat(lookup(cache, "acme", "fp-b", "申请退款", V1)).isEmpty();
        assertThat(lookup(cache, "acme", "fp-a", "申请退款", V1)).isPresent();
    }

    @Test
    @DisplayName("expires entries after the policy's time to live")
    void expiresEntries() {
        AtomicLong now = new AtomicLong(1_000_000L);
        CachePolicy policy = CachePolicy.builder().ttl(Duration.ofMinutes(10)).build();
        InMemorySemanticCache cache = new InMemorySemanticCache(policy, now::get);
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        now.addAndGet(Duration.ofMinutes(9).toMillis());
        assertThat(lookup(cache, "acme", "fp", "申请退款", V1)).isPresent();

        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertThat(lookup(cache, "acme", "fp", "申请退款", V1)).isEmpty();
    }

    @Test
    @DisplayName("evicts the oldest entries once a tenant exceeds its ceiling")
    void evictsBeyondCeiling() {
        CachePolicy policy = CachePolicy.builder().maxEntriesPerTenant(2).build();
        InMemorySemanticCache cache = new InMemorySemanticCache(policy);

        cache.put("acme", entry("fp", "q1", V1, 10, 0.001d));
        cache.put("acme", entry("fp", "q2", V_ORTHOGONAL, 10, 0.001d));
        cache.put("acme", entry("fp", "q3", V3, 10, 0.001d));

        assertThat(cache.stats().entries()).isEqualTo(2);
        assertThat(cache.stats().evictions()).isEqualTo(1);
        assertThat(lookup(cache, "acme", "fp", "q1", V1)).isEmpty();
        assertThat(lookup(cache, "acme", "fp", "q3", V3)).isPresent();
    }

    @Test
    @DisplayName("accumulates saved tokens and cost on hits")
    void accumulatesSavings() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        lookup(cache, "acme", "fp", "申请退款", V1);
        lookup(cache, "acme", "fp", "申请退款", V1);

        CacheStats stats = cache.stats();
        assertThat(stats.savedTokens()).isEqualTo(240);
        assertThat(stats.savedCostUsd()).isCloseTo(0.0048d, within(1e-9));
    }

    @Test
    @DisplayName("reports a hit rate over all lookups")
    void reportsHitRate() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        lookup(cache, "acme", "fp", "申请退款", V1);
        lookup(cache, "acme", "fp", "重置密码", V_ORTHOGONAL);

        assertThat(cache.stats().hitRate()).isCloseTo(0.5d, within(1e-9));
    }

    @Test
    @DisplayName("purges a tenant on request")
    void purgesTenant() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        cache.invalidateTenant("acme");

        assertThat(lookup(cache, "acme", "fp", "申请退款", V1)).isEmpty();
        assertThat(cache.stats().entries()).isZero();
    }

    @Test
    @DisplayName("does nothing when caching is disabled")
    void respectsDisabledPolicy() {
        InMemorySemanticCache cache = new InMemorySemanticCache(CachePolicy.disabled());
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d));

        assertThat(lookup(cache, "acme", "fp", "申请退款", V1)).isEmpty();
        assertThat(cache.stats().entries()).isZero();
    }

    @Test
    @DisplayName("does not overwrite an existing entry for the same question")
    void keepsFirstEntryForSameQuestion() {
        InMemorySemanticCache cache = newCache();
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d, "first"));
        cache.put("acme", entry("fp", "申请退款", V1, 120, 0.0024d, "second"));

        assertThat(cache.stats().entries()).isEqualTo(1);
        assertThat(lookup(cache, "acme", "fp", "申请退款", V1))
                .get()
                .extracting(CacheEntry::content)
                .isEqualTo("first");
    }

    private static InMemorySemanticCache newCache() {
        return new InMemorySemanticCache(CachePolicy.builder().similarityThreshold(0.9d).build());
    }

    private static Optional<CacheEntry> lookup(InMemorySemanticCache cache, String tenant,
                                               String fingerprint, String question, float[] embedding) {
        return cache.lookup(tenant, fingerprint, LiteralGuard.fingerprint(question), question, embedding);
    }

    private static CacheEntry entry(String fingerprint, String question, float[] embedding,
                                    int totalTokens, double cost) {
        return entry(fingerprint, question, embedding, totalTokens, cost, "answer");
    }

    private static CacheEntry entry(String fingerprint, String question, float[] embedding,
                                    int totalTokens, double cost, String content) {
        LlmResponse response = new LlmResponse(content, "demo-model", totalTokens / 2,
                totalTokens - totalTokens / 2, cost, false, 42L, "stop", "trace-1");
        return new CacheEntry(fingerprint, LiteralGuard.fingerprint(question), question,
                embedding, response);
    }
}

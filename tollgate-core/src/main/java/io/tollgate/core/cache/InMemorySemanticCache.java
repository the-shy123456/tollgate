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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * The default, in-process {@link SemanticCache}.
 *
 * <h2>Lookup strategy</h2>
 * <p>A lookup runs in two stages. First the canonical question is matched exactly against a hash
 * map, which is O(1) and resolves the overwhelming majority of real duplicates. Only when that
 * misses does the cache scan its entries and compare vectors, which is O(n) in the number of
 * entries held for that tenant.
 *
 * <p>The linear scan is a deliberate trade-off. An approximate nearest-neighbour index would
 * make lookups sub-linear but would add a large dependency and a tuning surface to a component
 * whose job is to save money, not to be a vector database. At the default ceiling of two thousand
 * entries per tenant a scan costs tens of microseconds, which is noise next to a model call.
 * Deployments that exceed that scale should back the cache with a dedicated vector store instead
 * of raising the ceiling.
 *
 * <h2>Eviction</h2>
 * <p>Entries are evicted in insertion order once a tenant exceeds its ceiling. This is FIFO, not
 * LRU, and that is intentional: LLM answers age rather than cycle, so the oldest entry is
 * usually the least trustworthy one anyway.
 *
 * <h2>Thread safety</h2>
 * <p>All state lives in concurrent collections and adders. Concurrent misses on the same
 * question may result in a duplicated upstream call, which is acceptable: the alternative is
 * holding a lock across a network call.
 */
public final class InMemorySemanticCache implements SemanticCache {

    private static final String KEY_SEPARATOR = "\u0000";

    private final CachePolicy policy;
    private final LongSupplier clock;
    private final Map<String, Partition> partitions = new ConcurrentHashMap<>();

    private final LongAdder exactHits = new LongAdder();
    private final LongAdder semanticHits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder savedTokens = new LongAdder();
    private final LongAdder savedCostMicros = new LongAdder();

    /** Creates a cache with the default policy. */
    public InMemorySemanticCache() {
        this(CachePolicy.defaults());
    }

    /**
     * Creates a cache with an explicit policy.
     *
     * @param policy the cache policy
     */
    public InMemorySemanticCache(CachePolicy policy) {
        this(policy, System::currentTimeMillis);
    }

    /**
     * Creates a cache with an explicit policy and clock.
     *
     * <p>The clock is injectable so that expiry behaviour can be tested without sleeping.
     *
     * @param policy the cache policy
     * @param clock  a millisecond clock
     */
    public InMemorySemanticCache(CachePolicy policy, LongSupplier clock) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<CacheEntry> lookup(String tenant, String systemFingerprint,
                                       String literalFingerprint, String canonicalQuestion,
                                       float[] embedding) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(literalFingerprint, "literalFingerprint must not be null");
        Objects.requireNonNull(canonicalQuestion, "canonicalQuestion must not be null");
        Objects.requireNonNull(embedding, "embedding must not be null");

        if (!policy.enabled()) {
            misses.increment();
            return Optional.empty();
        }
        Partition partition = partitions.get(tenant);
        if (partition == null) {
            misses.increment();
            return Optional.empty();
        }

        long now = clock.getAsLong();
        long ttlMillis = policy.ttl().toMillis();
        CacheEntry exact = partition.entries.get(
                key(systemFingerprint, literalFingerprint, canonicalQuestion));
        if (exact != null && exact.isFresh(now, ttlMillis)) {
            exactHits.increment();
            exact.recordHit();
            savedTokens.add(exact.originalTotalTokens());
            savedCostMicros.add(Math.round(exact.originalCostUsd() * 1_000_000d));
            return Optional.of(exact);
        }

        CacheEntry best = null;
        double bestScore = -1d;
        for (CacheEntry candidate : partition.entries.values()) {
            if (!candidate.systemFingerprint().equals(systemFingerprint)) {
                continue;
            }
            if (!candidate.literalFingerprint().equals(literalFingerprint)) {
                continue;
            }
            if (!candidate.isFresh(now, ttlMillis)) {
                continue;
            }
            double score = dot(embedding, candidate.embeddingRef());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && bestScore >= policy.similarityThreshold()) {
            semanticHits.increment();
            best.recordHit();
            savedTokens.add(best.originalTotalTokens());
            savedCostMicros.add(Math.round(best.originalCostUsd() * 1_000_000d));
            return Optional.of(best);
        }
        misses.increment();
        return Optional.empty();
    }

    @Override
    public void put(String tenant, CacheEntry entry) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (!policy.enabled()) {
            return;
        }
        Partition partition = partitions.computeIfAbsent(tenant, t -> new Partition());
        CacheEntry stamped = entry.storedAtMillis() > 0 ? entry : entry.withStoredAt(clock.getAsLong());
        String key = key(stamped.systemFingerprint(), stamped.literalFingerprint(),
                stamped.canonicalQuestion());
        if (partition.entries.putIfAbsent(key, stamped) != null) {
            return;
        }
        partition.order.add(key);
        while (partition.entries.size() > policy.maxEntriesPerTenant()) {
            String oldest = partition.order.poll();
            if (oldest == null) {
                break;
            }
            if (partition.entries.remove(oldest) != null) {
                evictions.increment();
            }
        }
    }

    @Override
    public CacheStats stats() {
        long entries = 0;
        for (Partition partition : partitions.values()) {
            entries += partition.entries.size();
        }
        return new CacheStats(exactHits.sum(), semanticHits.sum(), misses.sum(), entries,
                evictions.sum(), savedTokens.sum(), savedCostMicros.sum() / 1_000_000d);
    }

    @Override
    public void invalidateTenant(String tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        partitions.remove(tenant);
    }

    /**
     * Returns the policy this cache was built with.
     *
     * @return the policy
     */
    public CachePolicy policy() {
        return policy;
    }

    private static String key(String systemFingerprint, String literalFingerprint,
                              String canonicalQuestion) {
        return systemFingerprint + KEY_SEPARATOR + literalFingerprint
                + KEY_SEPARATOR + canonicalQuestion;
    }

    private static double dot(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        double sum = 0d;
        for (int i = 0; i < length; i++) {
            sum += (double) left[i] * right[i];
        }
        return sum;
    }

    private static final class Partition {
        private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
        private final Queue<String> order = new ConcurrentLinkedQueue<>();
    }
}

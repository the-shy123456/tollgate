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

/**
 * An immutable snapshot of cache behaviour.
 *
 * <p>Exact and semantic hits are reported separately because they mean different things. A high
 * exact-hit rate means the workload is templated and the cache is working as a deduplicator. A
 * high semantic-hit rate means the workload has genuine paraphrase and the embedding model is
 * earning its keep. Watching the ratio tells you which lever to pull.
 *
 * @param exactHits    lookups served by an exact canonical match
 * @param semanticHits lookups served by vector similarity
 * @param misses       lookups that found nothing usable
 * @param entries      the number of entries currently held across all tenants
 * @param evictions    entries dropped to respect the per-tenant ceiling
 * @param savedTokens  tokens avoided by cache hits
 * @param savedCostUsd money avoided by cache hits, priced at the original models' rates
 */
public record CacheStats(
        long exactHits,
        long semanticHits,
        long misses,
        long entries,
        long evictions,
        long savedTokens,
        double savedCostUsd) {

    /**
     * Returns an empty snapshot.
     *
     * @return all-zero statistics
     */
    public static CacheStats empty() {
        return new CacheStats(0, 0, 0, 0, 0, 0, 0d);
    }

    /** @return the total number of lookups that were served from cache */
    public long hits() {
        return exactHits + semanticHits;
    }

    /** @return the total number of lookups performed */
    public long lookups() {
        return hits() + misses;
    }

    /**
     * Returns the share of lookups that were served from cache.
     *
     * @return a ratio in {@code [0, 1]}, or {@code 0} when no lookups have happened
     */
    public double hitRate() {
        long total = lookups();
        return total == 0 ? 0d : (double) hits() / total;
    }
}

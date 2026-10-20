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

/**
 * Aggregated usage for one tenant.
 *
 * @param tenant        the tenant identifier
 * @param calls         total calls, including cache hits
 * @param cacheHits     calls served from cache
 * @param tokens        billed tokens
 * @param costUsd       money actually spent
 * @param savedCostUsd  money avoided by the cache
 */
public record TenantUsage(
        String tenant,
        long calls,
        long cacheHits,
        long tokens,
        double costUsd,
        double savedCostUsd) {

    /** @return the share of calls served from cache */
    public double cacheHitRate() {
        return calls == 0 ? 0d : (double) cacheHits / calls;
    }
}

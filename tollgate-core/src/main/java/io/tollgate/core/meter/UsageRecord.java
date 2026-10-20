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

import java.util.Map;

/**
 * One completed call, as seen by the metering layer.
 *
 * <p>This is the unit of truth for every cost report. It is produced once per call, after the
 * response has been priced, and it is the only place where "what did this cost" is decided.
 * Everything downstream — dashboards, invoices, alerts — derives from these records.
 *
 * <p>Billed and saved quantities are kept in separate fields rather than being netted together.
 * A cache hit reports zero billed tokens and a non-zero saved count, which is what makes
 * {@code totalCost / (totalCost + savedCost)} a meaningful number. Netting them would hide the
 * very effect the gateway exists to produce.
 *
 * @param traceId          correlation identifier shared with the audit log
 * @param tenant           who paid
 * @param model            which model ran
 * @param providerId       which provider served it
 * @param promptTokens     billed prompt tokens
 * @param completionTokens billed completion tokens
 * @param savedTokens      tokens that would have been billed but were not
 * @param costUsd          cost actually incurred
 * @param savedCostUsd     cost avoided, {@code 0} for a real call
 * @param cacheHit         whether the answer came from cache
 * @param latencyMillis    wall-clock duration, {@code 0} for cache hits
 * @param timestampMillis  when the call completed
 * @param tags             business labels carried over from the request
 */
public record UsageRecord(
        String traceId,
        String tenant,
        String model,
        String providerId,
        int promptTokens,
        int completionTokens,
        int savedTokens,
        double costUsd,
        double savedCostUsd,
        boolean cacheHit,
        long latencyMillis,
        long timestampMillis,
        Map<String, String> tags) {

    public UsageRecord {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** @return total billed tokens */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}

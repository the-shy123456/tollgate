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
package io.tollgate.core.quota;

import java.util.Objects;

/**
 * A point-in-time view of one tenant's budget.
 *
 * @param tenant             the tenant identifier
 * @param usedTokens         tokens consumed in the current window
 * @param limitTokens        the budget for the window
 * @param windowResetsAtMillis when the current window ends, in epoch milliseconds
 * @param rejectedCalls      calls rejected since the window began
 */
public record QuotaSnapshot(
        String tenant,
        long usedTokens,
        long limitTokens,
        long windowResetsAtMillis,
        long rejectedCalls) {

    public QuotaSnapshot {
        Objects.requireNonNull(tenant, "tenant must not be null");
    }

    /** @return tokens still available in this window, never negative */
    public long remainingTokens() {
        return Math.max(0L, limitTokens - usedTokens);
    }

    /**
     * Returns the share of budget consumed.
     *
     * @return a ratio, potentially above {@code 1} when the policy allows overrun
     */
    public double utilization() {
        return limitTokens <= 0 ? 0d : (double) usedTokens / limitTokens;
    }

    /** @return whether every token in the window has been spent */
    public boolean exhausted() {
        return usedTokens >= limitTokens;
    }
}

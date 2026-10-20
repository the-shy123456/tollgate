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

import java.time.Duration;

/**
 * A token budget for a single tenant.
 *
 * <p>Two independent limits are expressed here, and both matter:
 *
 * <ul>
 *   <li><b>Budget</b> ({@link #tokensPerWindow()}) answers "how much may this tenant spend this
 *       month". It protects the invoice.</li>
 *   <li><b>Rate</b> ({@link #maxTokensPerSecond()}) answers "how fast may this tenant spend it".
 *       It protects everything else: provider rate limits, downstream queues, and the ability of
 *       one runaway script to consume a month of budget in a minute.</li>
 * </ul>
 *
 * <p>Enforcing only the budget is a classic mistake. A retry loop inside a for-loop can burn an
 * entire quarterly budget in an afternoon, and nothing stops it until the following morning.
 *
 * @param tokensPerWindow  maximum tokens allowed per window
 * @param window           the length of the budget window
 * @param maxTokensPerSecond maximum sustained token rate, {@code 0} for unlimited
 * @param action           what to do when a limit is hit
 */
public record QuotaPolicy(long tokensPerWindow, Duration window, long maxTokensPerSecond,
                          QuotaAction action) {

    public QuotaPolicy {
        if (tokensPerWindow <= 0) {
            throw new IllegalArgumentException("tokensPerWindow must be positive");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (maxTokensPerSecond < 0) {
            throw new IllegalArgumentException("maxTokensPerSecond must not be negative");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
    }

    /**
     * Creates a policy that enforces a monthly budget and rejects overruns.
     *
     * @param tokensPerMonth the monthly token allowance
     * @return the policy
     */
    public static QuotaPolicy monthly(long tokensPerMonth) {
        return new QuotaPolicy(tokensPerMonth, Duration.ofDays(30), 0L, QuotaAction.REJECT);
    }

    /**
     * Creates a policy that enforces a daily budget and rejects overruns.
     *
     * @param tokensPerDay the daily token allowance
     * @return the policy
     */
    public static QuotaPolicy daily(long tokensPerDay) {
        return new QuotaPolicy(tokensPerDay, Duration.ofDays(1), 0L, QuotaAction.REJECT);
    }

    /**
     * Returns a copy with a sustained rate limit applied.
     *
     * @param tokensPerSecond the maximum sustained token rate
     * @return a new policy
     */
    public QuotaPolicy withRateLimit(long tokensPerSecond) {
        return new QuotaPolicy(tokensPerWindow, window, tokensPerSecond, action);
    }

    /**
     * Returns a copy with a different overrun action.
     *
     * @param newAction the action to take on overrun
     * @return a new policy
     */
    public QuotaPolicy withAction(QuotaAction newAction) {
        return new QuotaPolicy(tokensPerWindow, window, maxTokensPerSecond, newAction);
    }

    /** @return whether a sustained rate limit is configured */
    public boolean hasRateLimit() {
        return maxTokensPerSecond > 0;
    }
}

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
 * A complete cost picture across every call Tollgate has observed.
 *
 * <h2>Reading this report</h2>
 * <p>The useful comparison is not {@link #totalCostUsd()} on its own, it is that number against
 * {@link #hypotheticalCostUsd()} — what the same traffic would have cost without caching and
 * routing. A gateway that reports spend but cannot report the spend it prevented is only an
 * expensive log file.
 *
 * @param totalCalls       completed calls
 * @param cacheHits        calls served from cache
 * @param promptTokens     billed prompt tokens
 * @param completionTokens billed completion tokens
 * @param savedTokens      tokens avoided by cache hits
 * @param totalCostUsd     money actually spent
 * @param savedCostUsd     money avoided by cache hits, priced at original rates
 * @param byTenant         per-tenant breakdown, ordered by cost descending
 * @param byModel          per-model breakdown, ordered by cost descending
 */
public record CostReport(
        long totalCalls,
        long cacheHits,
        long promptTokens,
        long completionTokens,
        long savedTokens,
        double totalCostUsd,
        double savedCostUsd,
        Map<String, TenantUsage> byTenant,
        Map<String, ModelUsage> byModel) {

    /** @return total billed tokens */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * Returns what the same traffic would have cost with no cache.
     *
     * @return the counterfactual cost in US dollars
     */
    public double hypotheticalCostUsd() {
        return totalCostUsd + savedCostUsd;
    }

    /**
     * Returns the share of the counterfactual bill that caching avoided.
     *
     * @return a ratio in {@code [0, 1]}, or {@code 0} when nothing was spent
     */
    public double savingsRate() {
        double hypothetical = hypotheticalCostUsd();
        return hypothetical <= 0d ? 0d : savedCostUsd / hypothetical;
    }

    /** @return the share of calls served from cache */
    public double cacheHitRate() {
        return totalCalls == 0 ? 0d : (double) cacheHits / totalCalls;
    }

    /**
     * Renders the report as a fixed-width table suitable for a terminal.
     *
     * @return a multi-line human-readable summary
     */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tollgate cost report").append(System.lineSeparator());
        sb.append("--------------------------------------------------").append(System.lineSeparator());
        sb.append(String.format("  Calls            : %d%n", totalCalls));
        sb.append(String.format("  Cache hits       : %d (%.1f%%)%n", cacheHits, cacheHitRate() * 100d));
        sb.append(String.format("  Tokens billed    : %d%n", totalTokens()));
        sb.append(String.format("  Tokens saved     : %d%n", savedTokens));
        sb.append(String.format("  Cost incurred    : $%.6f%n", totalCostUsd));
        sb.append(String.format("  Cost avoided     : $%.6f%n", savedCostUsd));
        sb.append(String.format("  Without Tollgate : $%.6f%n", hypotheticalCostUsd()));
        sb.append(String.format("  Saved            : %.1f%%%n", savingsRate() * 100d));

        if (!byTenant.isEmpty()) {
            sb.append(System.lineSeparator()).append("  By tenant").append(System.lineSeparator());
            for (TenantUsage usage : byTenant.values()) {
                sb.append(String.format("    %-20s calls=%-6d cost=$%-10.6f saved=$%.6f%n",
                        usage.tenant(), usage.calls(), usage.costUsd(), usage.savedCostUsd()));
            }
        }
        if (!byModel.isEmpty()) {
            sb.append(System.lineSeparator()).append("  By model").append(System.lineSeparator());
            for (ModelUsage usage : byModel.values()) {
                sb.append(String.format("    %-24s calls=%-6d tokens=%-9d cost=$%.6f%n",
                        usage.model(), usage.calls(), usage.tokens(), usage.costUsd()));
            }
        }
        return sb.toString();
    }

    /**
     * Renders the report as a Markdown snippet, for pasting into an issue or a pull request.
     *
     * @return a Markdown table summarising spend
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Metric | Value |").append(System.lineSeparator());
        sb.append("| --- | --- |").append(System.lineSeparator());
        sb.append(String.format("| Calls | %d |%n", totalCalls));
        sb.append(String.format("| Cache hit rate | %.1f%% |%n", cacheHitRate() * 100d));
        sb.append(String.format("| Cost incurred | $%.6f |%n", totalCostUsd));
        sb.append(String.format("| Cost avoided | $%.6f |%n", savedCostUsd));
        sb.append(String.format("| Savings rate | %.1f%% |%n", savingsRate() * 100d));
        return sb.toString();
    }

    @Override
    public String toString() {
        return toText();
    }
}

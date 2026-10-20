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
 * Aggregated usage for one model.
 *
 * <p>This is the report that answers "which model is eating the budget". It usually produces a
 * surprise: a model can handle a small share of calls and still dominate spend, because output
 * tokens on a frontier model cost two orders of magnitude more than input tokens on a small one.
 *
 * @param model   the model identifier
 * @param calls   total calls served by this model
 * @param tokens  billed tokens
 * @param costUsd money spent on this model
 */
public record ModelUsage(
        String model,
        long calls,
        long tokens,
        double costUsd) {

    /**
     * Returns the average cost per call.
     *
     * @return the mean cost in US dollars, or {@code 0} when there were no calls
     */
    public double averageCostPerCall() {
        return calls == 0 ? 0d : costUsd / calls;
    }
}

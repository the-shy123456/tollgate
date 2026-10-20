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
package io.tollgate.core.route;

import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.ModelSpec;

import java.util.Comparator;
import java.util.List;

/**
 * Routes to the model with the lowest expected latency.
 *
 * <p>Expected latency is a declared figure, not a measurement. Tollgate does not run its own
 * probe loop: measuring real latency requires actually calling every candidate, which costs the
 * money the gateway exists to save. Teams that want adaptive routing should feed measured
 * p50/p99 values into {@link ModelSpec#expectedLatencyMillis()} from their own telemetry.
 *
 * <p>Ties are broken by cost.
 */
public final class LatencyFirstRouter extends AbstractModelRouter {

    @Override
    public String name() {
        return "latency-first";
    }

    @Override
    public ModelSpec route(LlmRequest request, List<ModelSpec> candidates) {
        List<ModelSpec> pool = eligible(request, candidates);
        return pool.stream()
                .min(Comparator
                        .comparingLong(ModelSpec::expectedLatencyMillis)
                        .thenComparingDouble(model -> estimatedCost(request, model)))
                .orElseThrow();
    }
}

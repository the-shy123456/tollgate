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
 * Routes to the highest quality tier that fits, breaking ties on price.
 *
 * <p>Quality tiers are coarse integer bands supplied by whoever registers a model, not a
 * benchmark score. That is a deliberate simplification: vendors publish benchmarks, but those
 * numbers rarely transfer to your prompts, so a hand-assigned band that your team agrees on is
 * more actionable than a leaderboard position.
 *
 * <p>Ties are broken by cost so that two models in the same band do not produce a coin-flip
 * routing decision from one request to the next.
 */
public final class QualityFirstRouter extends AbstractModelRouter {

    @Override
    public String name() {
        return "quality-first";
    }

    @Override
    public ModelSpec route(LlmRequest request, List<ModelSpec> candidates) {
        List<ModelSpec> pool = eligible(request, candidates);
        return pool.stream()
                .min(Comparator
                        .comparingInt(ModelSpec::qualityTier).reversed()
                        .thenComparingDouble(model -> estimatedCost(request, model)))
                .orElseThrow();
    }
}

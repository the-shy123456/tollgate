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

import io.tollgate.core.exception.NoEligibleModelException;
import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.ModelSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The default router: treat quality as a threshold, then optimise for cost and latency.
 *
 * <h2>Why not a weighted sum of all three</h2>
 * <p>The obvious design — normalise quality, cost and latency, then take a weighted sum — has a
 * defect that only shows up on real price lists. Min-max normalisation maps the cheapest
 * candidate to {@code 1} and the most expensive to {@code 0} regardless of the actual gap. With
 * only two candidates in the pool, a fifty-fold price difference and a five percent difference
 * both become the same {@code 0}/{@code 1} pair. The cost term then dominates permanently, the
 * quality term can never overcome it, and "balanced" silently becomes "cheapest".
 *
 * <h2>What this does instead</h2>
 * <p>Quality becomes a hard filter rather than a term in a sum. Any model within
 * {@code qualityTierTolerance} of the best available tier is considered acceptable, and the
 * choice among acceptable models is made purely on cost and latency, which are both negative
 * quantities that normalise sensibly.
 *
 * <p>This is easier to reason about in production as well. "We will not use a model more than
 * two tiers below the best available" is a sentence an operator can check. "We will accept a
 * quality loss if it buys a 0.37 weighted improvement" is not.
 *
 * <p>The tolerance is deliberately relative to the current pool, not absolute. If a deployment
 * only registers two mid-tier models, both remain acceptable; the router does not start
 * rejecting everything because nothing reaches tier five.
 */
public final class BalancedRouter extends AbstractModelRouter {

    /** Default tolerance, in quality tiers, below the best available candidate. */
    public static final int DEFAULT_QUALITY_TOLERANCE = 2;

    /** Default weight assigned to cost when ranking acceptable models. */
    public static final double DEFAULT_COST_WEIGHT = 0.7d;

    /** Default weight assigned to latency when ranking acceptable models. */
    public static final double DEFAULT_LATENCY_WEIGHT = 0.3d;

    private final int qualityTolerance;
    private final double costWeight;
    private final double latencyWeight;

    /** Creates a router with the default weights. */
    public BalancedRouter() {
        this(DEFAULT_QUALITY_TOLERANCE, DEFAULT_COST_WEIGHT, DEFAULT_LATENCY_WEIGHT);
    }

    /**
     * Creates a router with explicit tuning.
     *
     * @param qualityTolerance how many tiers below the best candidate remain acceptable
     * @param costWeight       relative weight of cost when ranking acceptable models
     * @param latencyWeight    relative weight of latency when ranking acceptable models
     */
    public BalancedRouter(int qualityTolerance, double costWeight, double latencyWeight) {
        if (qualityTolerance < 0) {
            throw new IllegalArgumentException("qualityTolerance must not be negative");
        }
        if (costWeight < 0 || latencyWeight < 0) {
            throw new IllegalArgumentException("weights must not be negative");
        }
        if (costWeight + latencyWeight == 0) {
            throw new IllegalArgumentException("at least one weight must be positive");
        }
        this.qualityTolerance = qualityTolerance;
        this.costWeight = costWeight;
        this.latencyWeight = latencyWeight;
    }

    @Override
    public String name() {
        return "balanced";
    }

    @Override
    public ModelSpec route(LlmRequest request, List<ModelSpec> candidates) {
        List<ModelSpec> pool = eligible(request, candidates);
        if (pool.size() == 1) {
            return pool.get(0);
        }

        int bestTier = pool.stream().mapToInt(ModelSpec::qualityTier).max().orElse(1);
        int minimumTier = Math.max(1, bestTier - qualityTolerance);

        List<ModelSpec> acceptable = new ArrayList<>();
        for (ModelSpec model : pool) {
            if (model.qualityTier() >= minimumTier) {
                acceptable.add(model);
            }
        }
        if (acceptable.isEmpty()) {
            acceptable = pool;
        }
        if (acceptable.size() == 1) {
            return acceptable.get(0);
        }

        double minCost = Double.MAX_VALUE;
        double maxCost = -Double.MAX_VALUE;
        long minLatency = Long.MAX_VALUE;
        long maxLatency = Long.MIN_VALUE;
        for (ModelSpec model : acceptable) {
            double cost = estimatedCost(request, model);
            minCost = Math.min(minCost, cost);
            maxCost = Math.max(maxCost, cost);
            minLatency = Math.min(minLatency, model.expectedLatencyMillis());
            maxLatency = Math.max(maxLatency, model.expectedLatencyMillis());
        }

        ModelSpec best = null;
        double bestScore = -Double.MAX_VALUE;
        for (ModelSpec model : acceptable) {
            double costScore = inverseNormalize(estimatedCost(request, model), minCost, maxCost);
            double latencyScore = inverseNormalize(model.expectedLatencyMillis(), minLatency, maxLatency);
            double score = costWeight * costScore + latencyWeight * latencyScore;
            if (score > bestScore
                    || (score == bestScore && best != null && model.qualityTier() > best.qualityTier())) {
                bestScore = score;
                best = model;
            }
        }
        if (best == null) {
            throw new NoEligibleModelException("No acceptable model produced a score");
        }
        return best;
    }

    private static double inverseNormalize(double value, double min, double max) {
        if (max <= min) {
            return 1d;
        }
        return (max - value) / (max - min);
    }
}

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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Shared plumbing for the built-in routers.
 *
 * <p>Two concerns are common to every routing strategy and are therefore implemented once here:
 * deciding which models are physically able to serve a request, and estimating what a candidate
 * would cost before anything is called.
 */
public abstract class AbstractModelRouter implements ModelRouter {

    /**
     * Filters the candidate pool down to models that can serve the request.
     *
     * <p>A model is eligible only if its context window can hold the prompt <em>and</em> the
     * completion budget the caller asked for. Checking the prompt alone would let a request
     * through that the model is guaranteed to truncate.
     *
     * @param request    the request being routed
     * @param candidates every registered model
     * @return the eligible models, in the order they were given
     * @throws NoEligibleModelException if nothing fits
     */
    protected List<ModelSpec> eligible(LlmRequest request, List<ModelSpec> candidates) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");

        int required = request.estimatedPromptTokens() + request.maxTokens();
        List<ModelSpec> result = new ArrayList<>(candidates.size());
        for (ModelSpec candidate : candidates) {
            if (candidate.fits(required)) {
                result.add(candidate);
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        if (candidates.isEmpty()) {
            throw new NoEligibleModelException("No models are registered. Register at least one provider.");
        }
        int largest = candidates.stream().mapToInt(ModelSpec::contextWindow).max().orElse(0);
        throw new NoEligibleModelException(
                "No model can hold a request of " + required + " tokens (prompt "
                        + request.estimatedPromptTokens() + " + completion budget "
                        + request.maxTokens() + "). The largest context window available is "
                        + largest + " tokens.");
    }

    /**
     * Estimates what a candidate would cost for this request.
     *
     * <p>The completion side is estimated at the caller's full budget rather than at the likely
     * output length. Routing must not assume the model will be terse: a cheap-input model with
     * an expensive output price is a trap, and pricing the worst case is what exposes it.
     *
     * @param request the request being routed
     * @param model   the candidate
     * @return the estimated cost in US dollars
     */
    protected double estimatedCost(LlmRequest request, ModelSpec model) {
        return model.costOf(request.estimatedPromptTokens(), request.maxTokens());
    }

    /**
     * Returns the candidate with the lowest estimated cost.
     *
     * @param request    the request being routed
     * @param candidates the eligible candidates, must not be empty
     * @return the cheapest candidate
     */
    protected ModelSpec cheapest(LlmRequest request, List<ModelSpec> candidates) {
        return candidates.stream()
                .min(Comparator.comparingDouble(model -> estimatedCost(request, model)))
                .orElseThrow(() -> new NoEligibleModelException("Candidate list is empty"));
    }
}

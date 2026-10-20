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

import java.util.List;

/**
 * Dispatches to a built-in router based on the request's {@link io.tollgate.core.model.RoutingProfile}.
 *
 * <p>This is the router Tollgate installs by default. It exists so that callers can express
 * intent ("this is a throwaway classification, be cheap") without having to know which strategy
 * object implements that intent.
 *
 * <p>The four strategies are stateless and therefore shared across calls.
 */
public final class ProfileAwareRouter implements ModelRouter {

    private final ModelRouter costFirst = new CostFirstRouter();
    private final ModelRouter qualityFirst = new QualityFirstRouter();
    private final ModelRouter latencyFirst = new LatencyFirstRouter();
    private final ModelRouter balanced;

    /** Creates a router backed by a {@link BalancedRouter} with default weights. */
    public ProfileAwareRouter() {
        this(new BalancedRouter());
    }

    /**
     * Creates a router with a custom balanced strategy.
     *
     * @param balanced the router used for {@link io.tollgate.core.model.RoutingProfile#BALANCED}
     */
    public ProfileAwareRouter(ModelRouter balanced) {
        if (balanced == null) {
            throw new IllegalArgumentException("balanced router must not be null");
        }
        this.balanced = balanced;
    }

    @Override
    public String name() {
        return "profile-aware";
    }

    @Override
    public ModelSpec route(LlmRequest request, List<ModelSpec> candidates) {
        return switch (request.routingProfile()) {
            case CHEAPEST -> costFirst.route(request, candidates);
            case BEST_QUALITY -> qualityFirst.route(request, candidates);
            case LATENCY_FIRST -> latencyFirst.route(request, candidates);
            case BALANCED -> balanced.route(request, candidates);
        };
    }
}

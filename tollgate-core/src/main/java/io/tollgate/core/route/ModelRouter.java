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
 * Chooses which model should serve a request.
 *
 * <p>A router is a pure function of the request and the candidate pool. It performs no I/O, keeps
 * no state and never calls a model. That constraint is what makes routing decisions reproducible
 * in tests and explainable in post-mortems.
 *
 * <p>Implementations must be thread safe.
 */
public interface ModelRouter {

    /**
     * Returns a short name used in logs and metrics.
     *
     * @return the router name
     */
    String name();

    /**
     * Picks the best candidate for a request.
     *
     * <p>Implementations must only return candidates that can actually serve the request: the
     * context window has to accommodate the prompt plus the requested completion budget.
     *
     * @param request    the request being routed
     * @param candidates every model registered across all providers
     * @return the chosen model, never {@code null}
     * @throws io.tollgate.core.exception.NoEligibleModelException if no candidate fits
     */
    ModelSpec route(LlmRequest request, List<ModelSpec> candidates);
}

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
 * Routes to the cheapest model that fits.
 *
 * <p>This is the right choice for high-volume, low-stakes traffic: classification, extraction,
 * summarisation of routine documents, and anything where a wrong-but-cheap answer is recoverable.
 */
public final class CostFirstRouter extends AbstractModelRouter {

    @Override
    public String name() {
        return "cost-first";
    }

    @Override
    public ModelSpec route(LlmRequest request, List<ModelSpec> candidates) {
        return cheapest(request, eligible(request, candidates));
    }
}

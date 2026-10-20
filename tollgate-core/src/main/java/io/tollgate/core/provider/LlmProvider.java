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
package io.tollgate.core.provider;

import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.LlmResponse;
import io.tollgate.core.model.ModelSpec;

import java.util.List;

/**
 * A source of model completions.
 *
 * <p>Tollgate is deliberately transport agnostic. A provider may wrap an HTTP API, a local
 * runtime such as Ollama, a message queue, or a test double. The governance pipeline only cares
 * about two things: which models exist and how to invoke one of them.
 *
 * <p>Implementations must be thread safe. {@link #complete} is called concurrently and may block
 * for as long as the underlying model takes to respond.
 */
public interface LlmProvider {

    /**
     * Returns the stable identifier of this provider.
     *
     * @return a non-null, non-blank identifier
     */
    String id();

    /**
     * Returns every model this provider can serve.
     *
     * <p>The list is the router's candidate pool. It should be cheap to call and safe to call
     * concurrently; implementations that fetch the list over the network should cache it.
     *
     * @return an immutable list of model specifications, never {@code null}
     */
    List<ModelSpec> models();

    /**
     * Executes a completion.
     *
     * <p>Implementations are responsible for reporting accurate token usage. If a vendor does not
     * report usage, the implementation should estimate it rather than return zero, because zero
     * tokens would silently make the call free in every downstream report.
     *
     * @param model   the model to invoke, guaranteed to be one of {@link #models()}
     * @param request the request to serve
     * @return the raw provider response, before pricing
     * @throws io.tollgate.core.exception.ProviderException if the upstream call fails
     */
    LlmResponse complete(ModelSpec model, LlmRequest request);
}

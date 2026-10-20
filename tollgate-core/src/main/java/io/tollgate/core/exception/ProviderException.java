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
package io.tollgate.core.exception;

/**
 * Raised when a provider fails to serve a request.
 *
 * <p>Failures are wrapped rather than propagated raw so that the governance pipeline can tell
 * the difference between "the model refused" and "our own bookkeeping broke", and so that
 * retries and fallbacks can be applied uniformly across heterogeneous providers.
 */
public class ProviderException extends TollgateException {

    private static final long serialVersionUID = 1L;

    private final String providerId;
    private final String modelId;

    /**
     * Creates the exception.
     *
     * @param providerId the failing provider
     * @param modelId    the failing model
     * @param message    the detail message
     * @param cause      the underlying failure, may be {@code null}
     */
    public ProviderException(String providerId, String modelId, String message, Throwable cause) {
        super("Provider '" + providerId + "' failed for model '" + modelId + "': " + message, cause);
        this.providerId = providerId;
        this.modelId = modelId;
    }

    /** @return the failing provider */
    public String providerId() {
        return providerId;
    }

    /** @return the failing model */
    public String modelId() {
        return modelId;
    }
}

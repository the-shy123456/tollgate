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
package io.tollgate.core.model;

import java.util.Objects;

/**
 * The outcome of a single model invocation, as observed by Tollgate.
 *
 * <p>Besides the model output, a response carries everything needed to explain the bill:
 * which model actually ran, how many tokens it consumed, what that cost, whether the answer
 * came from cache, and how long it took.
 *
 * @param content          the generated text, never {@code null}
 * @param model            the id of the model that produced the content
 * @param promptTokens     tokens consumed by the prompt, as reported by the provider
 * @param completionTokens tokens consumed by the completion, as reported by the provider
 * @param costUsd          the computed cost in US dollars
 * @param cacheHit         whether the content was served from the semantic cache
 * @param latencyMillis    wall-clock duration of the call, {@code 0} for cache hits
 * @param finishReason     the provider's stop reason, or {@code null} when unknown
 * @param traceId          the identifier correlating this call across metering and audit logs
 */
public record LlmResponse(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        double costUsd,
        boolean cacheHit,
        long latencyMillis,
        String finishReason,
        String traceId) {

    public LlmResponse {
        Objects.requireNonNull(content, "content must not be null");
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (costUsd < 0) {
            throw new IllegalArgumentException("cost must not be negative");
        }
    }

    /**
     * Creates a provider-side response before Tollgate has priced it.
     *
     * @param content          the generated text
     * @param model            the model id
     * @param promptTokens     prompt tokens reported by the provider
     * @param completionTokens completion tokens reported by the provider
     * @param latencyMillis    wall-clock duration
     * @param finishReason     the provider's stop reason
     * @return an unpriced response
     */
    public static LlmResponse fromProvider(String content, String model, int promptTokens,
                                           int completionTokens, long latencyMillis,
                                           String finishReason) {
        return new LlmResponse(content, model, promptTokens, completionTokens, 0d,
                false, latencyMillis, finishReason, null);
    }

    /** @return the total tokens billed for this call */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * Returns a copy with pricing and trace information attached.
     *
     * @param newCostUsd the computed cost
     * @param newTraceId the trace identifier
     * @return a new response instance
     */
    public LlmResponse priced(double newCostUsd, String newTraceId) {
        return new LlmResponse(content, model, promptTokens, completionTokens, newCostUsd,
                cacheHit, latencyMillis, finishReason, newTraceId);
    }

    /**
     * Returns a copy marked as served from cache.
     *
     * <p>A cache hit costs nothing at the provider and therefore reports zero tokens and zero
     * cost. The original token counts are intentionally discarded so that aggregate billing
     * statistics do not double count tokens that were saved.
     *
     * @param newTraceId the trace identifier for the cached call
     * @return a new response instance
     */
    public LlmResponse asCacheHit(String newTraceId) {
        return new LlmResponse(content, model, 0, 0, 0d, true, 0L, "cache_hit", newTraceId);
    }

    /**
     * Returns a copy attributed to a different model.
     *
     * @param newModel the model id to attribute
     * @return a new response instance
     */
    public LlmResponse withModel(String newModel) {
        return new LlmResponse(content, newModel, promptTokens, completionTokens, costUsd,
                cacheHit, latencyMillis, finishReason, traceId);
    }
}

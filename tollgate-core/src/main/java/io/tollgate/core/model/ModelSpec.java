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
import java.util.Set;

/**
 * Static, declarative facts about a model: what it costs, how big its context is, how good it is
 * and how fast it usually responds.
 *
 * <p>Tollgate never hard-codes vendor knowledge. Price lists change, models are retired, and new
 * tiers appear every quarter, so every number here is supplied by whoever registers the model.
 * Keeping this data as plain values rather than behaviour is what lets the router treat a local
 * Llama endpoint and a frontier API model as interchangeable candidates.
 *
 * @param id                     the model identifier understood by the provider
 * @param providerId             the id of the {@link LlmProvider} that serves this model
 * @param inputPricePerMillion   USD per one million prompt tokens
 * @param outputPricePerMillion  USD per one million completion tokens
 * @param contextWindow          the maximum number of tokens the model accepts in one request
 * @param qualityTier            a coarse quality band in {@code [1, 5]}, where 5 is best
 * @param expectedLatencyMillis  the typical end-to-end latency used for latency-aware routing
 * @param capabilities           free-form capability labels such as {@code json} or {@code vision}
 */
public record ModelSpec(
        String id,
        String providerId,
        double inputPricePerMillion,
        double outputPricePerMillion,
        int contextWindow,
        int qualityTier,
        long expectedLatencyMillis,
        Set<String> capabilities) {

    public ModelSpec {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (inputPricePerMillion < 0 || outputPricePerMillion < 0) {
            throw new IllegalArgumentException("prices must not be negative");
        }
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (qualityTier < 1 || qualityTier > 5) {
            throw new IllegalArgumentException("qualityTier must be within [1, 5] but was " + qualityTier);
        }
        if (expectedLatencyMillis < 0) {
            throw new IllegalArgumentException("expectedLatencyMillis must not be negative");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /**
     * Creates a specification with no capability labels.
     *
     * @param id                    the model identifier
     * @param providerId            the owning provider
     * @param inputPricePerMillion  USD per one million prompt tokens
     * @param outputPricePerMillion USD per one million completion tokens
     * @param contextWindow         maximum accepted tokens
     * @param qualityTier           quality band in {@code [1, 5]}
     * @param expectedLatencyMillis typical latency
     * @return an immutable specification
     */
    public static ModelSpec of(String id, String providerId, double inputPricePerMillion,
                               double outputPricePerMillion, int contextWindow, int qualityTier,
                               long expectedLatencyMillis) {
        return new ModelSpec(id, providerId, inputPricePerMillion, outputPricePerMillion,
                contextWindow, qualityTier, expectedLatencyMillis, Set.of());
    }

    /**
     * Prices a call against this model.
     *
     * @param promptTokens     prompt tokens consumed
     * @param completionTokens completion tokens consumed
     * @return the cost in US dollars
     */
    public double costOf(int promptTokens, int completionTokens) {
        double input = promptTokens / 1_000_000d * inputPricePerMillion;
        double output = completionTokens / 1_000_000d * outputPricePerMillion;
        return input + output;
    }

    /**
     * Reports whether this model can accept a prompt of the given size.
     *
     * @param promptTokens the prompt size to test
     * @return {@code true} if the prompt fits inside the context window
     */
    public boolean fits(int promptTokens) {
        return promptTokens <= contextWindow;
    }

    /**
     * Reports whether this model declares the given capability.
     *
     * @param capability the capability label
     * @return {@code true} if the label is present
     */
    public boolean has(String capability) {
        return capabilities.contains(capability);
    }
}

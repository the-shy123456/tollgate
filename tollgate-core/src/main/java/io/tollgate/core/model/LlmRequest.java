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

import io.tollgate.core.util.TextNormalizer;
import io.tollgate.core.util.TokenEstimator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable description of a single model invocation.
 *
 * <p>Requests are created through {@link #builder()}. Everything Tollgate needs in order to
 * govern a call lives here: who is paying ({@link #tenant()}), what they are asking, how much
 * they are willing to spend, and whether the answer may be served from cache.
 *
 * <p>Instances are immutable and safe for use from multiple threads.
 */
public final class LlmRequest {

    private final String tenant;
    private final List<Message> messages;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Map<String, String> tags;
    private final boolean cacheable;
    private final RoutingProfile routingProfile;
    private final Duration timeout;

    private LlmRequest(Builder builder) {
        this.tenant = builder.tenant;
        this.messages = List.copyOf(builder.messages);
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tags));
        this.cacheable = builder.cacheable;
        this.routingProfile = builder.routingProfile;
        this.timeout = builder.timeout;
    }

    /**
     * Creates a new builder.
     *
     * @return a fresh builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a single-turn request from a plain prompt.
     *
     * @param tenant the billing and quota owner
     * @param prompt the user prompt
     * @return a request with default settings
     */
    public static LlmRequest of(String tenant, String prompt) {
        return builder().tenant(tenant).user(prompt).build();
    }

    /** @return the quota and billing owner of this call, never {@code null} */
    public String tenant() {
        return tenant;
    }

    /** @return the ordered conversation, never empty */
    public List<Message> messages() {
        return messages;
    }

    /** @return an explicitly pinned model id, or {@code null} to let the router decide */
    public String model() {
        return model;
    }

    /** @return sampling temperature */
    public double temperature() {
        return temperature;
    }

    /** @return the maximum number of tokens the caller accepts in the completion */
    public int maxTokens() {
        return maxTokens;
    }

    /** @return free-form business labels carried through to metering and audit records */
    public Map<String, String> tags() {
        return tags;
    }

    /** @return whether the response may be served from, and stored in, the semantic cache */
    public boolean cacheable() {
        return cacheable;
    }

    /** @return the caller's routing preference */
    public RoutingProfile routingProfile() {
        return routingProfile;
    }

    /** @return the per-call timeout hint */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the last user message, which is what a single-turn cache key is built from.
     *
     * @return the last user message content, or {@code null} if the conversation has none
     */
    public String lastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Message.Role.USER) {
                return messages.get(i).content();
            }
        }
        return null;
    }

    /**
     * Reports whether this conversation is eligible for semantic caching.
     *
     * <p>Semantic caching matches on meaning, and meaning is only stable for self-contained
     * single-turn questions. A multi-turn conversation cannot be cached by prompt similarity
     * because the same sentence means different things depending on what came before it, so
     * multi-turn requests deliberately bypass the cache instead of risking a wrong answer.
     *
     * @return {@code true} if the conversation contains exactly one user message
     */
    public boolean isSingleTurn() {
        int userMessages = 0;
        for (Message message : messages) {
            if (message.role() == Message.Role.USER) {
                userMessages++;
            }
        }
        return userMessages == 1;
    }

    /**
     * Builds the canonical text used as the cache key.
     *
     * <p>System messages are included because two calls with the same question but different
     * instructions must not share an answer.
     *
     * @return the normalised text, never {@code null}
     */
    public String cacheKeyText() {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message.role() == Message.Role.SYSTEM) {
                sb.append(TextNormalizer.normalize(message.content())).append('\n');
            }
        }
        String user = lastUserMessage();
        if (user != null) {
            sb.append(TextNormalizer.normalize(user));
        }
        return sb.toString();
    }

    /**
     * Estimates the prompt-side token cost for quota reservation.
     *
     * @return a conservative, non-negative estimate
     */
    public int estimatedPromptTokens() {
        int total = 0;
        for (Message message : messages) {
            total += TokenEstimator.estimate(message.content()) + TokenEstimator.MESSAGE_OVERHEAD;
        }
        return total;
    }

    /**
     * Returns a copy of this request whose cacheability has been overridden.
     *
     * @param cacheable the new flag value
     * @return a new request instance
     */
    public LlmRequest withCacheable(boolean cacheable) {
        return toBuilder().cacheable(cacheable).build();
    }

    /**
     * Returns a builder pre-populated with this request's values.
     *
     * @return a mutable copy of this request
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.tenant = this.tenant;
        builder.messages = new ArrayList<>(this.messages);
        builder.model = this.model;
        builder.temperature = this.temperature;
        builder.maxTokens = this.maxTokens;
        builder.tags = new LinkedHashMap<>(this.tags);
        builder.cacheable = this.cacheable;
        builder.routingProfile = this.routingProfile;
        builder.timeout = this.timeout;
        return builder;
    }

    @Override
    public String toString() {
        return "LlmRequest{tenant='" + tenant + "', messages=" + messages.size()
                + ", model='" + model + "', profile=" + routingProfile + '}';
    }

    /** Mutable builder for {@link LlmRequest}. */
    public static final class Builder {

        private String tenant;
        private List<Message> messages = new ArrayList<>();
        private String model;
        private double temperature = 0.7d;
        private int maxTokens = 1024;
        private Map<String, String> tags = new LinkedHashMap<>();
        private boolean cacheable = true;
        private RoutingProfile routingProfile = RoutingProfile.BALANCED;
        private Duration timeout = Duration.ofSeconds(60);

        private Builder() {
        }

        /**
         * Sets the quota and billing owner.
         *
         * @param tenant a stable identifier such as a team or product name
         * @return this builder
         */
        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        /**
         * Appends a message to the conversation.
         *
         * @param message the message to append
         * @return this builder
         */
        public Builder message(Message message) {
            this.messages.add(Objects.requireNonNull(message, "message must not be null"));
            return this;
        }

        /**
         * Appends a user message.
         *
         * @param content the user input
         * @return this builder
         */
        public Builder user(String content) {
            return message(Message.user(content));
        }

        /**
         * Appends a system message.
         *
         * @param content the instruction text
         * @return this builder
         */
        public Builder system(String content) {
            return message(Message.system(content));
        }

        /**
         * Pins the request to a specific model, bypassing routing.
         *
         * @param model the model id
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the sampling temperature.
         *
         * @param temperature a value in {@code [0, 2]}
         * @return this builder
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Sets the completion token ceiling.
         *
         * @param maxTokens a positive number of tokens
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Adds a business label.
         *
         * @param key   the label name
         * @param value the label value
         * @return this builder
         */
        public Builder tag(String key, String value) {
            this.tags.put(key, value);
            return this;
        }

        /**
         * Sets whether the response may be cached.
         *
         * @param cacheable {@code true} to allow semantic caching
         * @return this builder
         */
        public Builder cacheable(boolean cacheable) {
            this.cacheable = cacheable;
            return this;
        }

        /**
         * Sets the routing preference.
         *
         * @param routingProfile the preference
         * @return this builder
         */
        public Builder routingProfile(RoutingProfile routingProfile) {
            this.routingProfile = routingProfile;
            return this;
        }

        /**
         * Sets the per-call timeout hint.
         *
         * @param timeout the timeout duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Validates and builds the request.
         *
         * @return an immutable request
         * @throws IllegalArgumentException if the tenant is blank, the conversation is empty,
         *                                  the temperature is out of range, or the token ceiling
         *                                  is not positive
         */
        public LlmRequest build() {
            if (tenant == null || tenant.isBlank()) {
                throw new IllegalArgumentException("tenant must not be blank");
            }
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("at least one message is required");
            }
            if (temperature < 0d || temperature > 2d) {
                throw new IllegalArgumentException("temperature must be within [0, 2] but was " + temperature);
            }
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive but was " + maxTokens);
            }
            return new LlmRequest(this);
        }
    }
}

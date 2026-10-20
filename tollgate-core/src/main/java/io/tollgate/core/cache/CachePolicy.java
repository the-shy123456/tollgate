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
package io.tollgate.core.cache;

import java.time.Duration;

/**
 * Tuning knobs for the semantic cache.
 *
 * <p>The default threshold of {@value #DEFAULT_SIMILARITY_THRESHOLD} is deliberately
 * conservative. A false positive here means a user receives an answer to a question they did
 * not ask, which is a far worse outcome than a cache miss that costs a fraction of a cent.
 * Tune it downward only after measuring against real traffic.
 */
public final class CachePolicy {

    /** Similarity above which two questions are considered the same. */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.75d;

    /** Default entry lifetime. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(6);

    /** Default per-tenant entry ceiling. */
    public static final int DEFAULT_MAX_ENTRIES_PER_TENANT = 2_000;

    private final boolean enabled;
    private final double similarityThreshold;
    private final Duration ttl;
    private final int maxEntriesPerTenant;

    private CachePolicy(Builder builder) {
        this.enabled = builder.enabled;
        this.similarityThreshold = builder.similarityThreshold;
        this.ttl = builder.ttl;
        this.maxEntriesPerTenant = builder.maxEntriesPerTenant;
    }

    /**
     * Creates a policy builder.
     *
     * @return a builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates the default policy.
     *
     * @return a policy with conservative defaults
     */
    public static CachePolicy defaults() {
        return builder().build();
    }

    /**
     * Creates a policy that disables caching entirely.
     *
     * @return a disabled policy
     */
    public static CachePolicy disabled() {
        return builder().enabled(false).build();
    }

    /** @return whether caching is active */
    public boolean enabled() {
        return enabled;
    }

    /** @return the cosine similarity threshold in {@code (0, 1]} */
    public double similarityThreshold() {
        return similarityThreshold;
    }

    /** @return the entry time to live */
    public Duration ttl() {
        return ttl;
    }

    /** @return the maximum number of entries retained per tenant */
    public int maxEntriesPerTenant() {
        return maxEntriesPerTenant;
    }

    @Override
    public String toString() {
        return "CachePolicy{enabled=" + enabled + ", threshold=" + similarityThreshold
                + ", ttl=" + ttl + ", maxEntriesPerTenant=" + maxEntriesPerTenant + '}';
    }

    /** Mutable builder for {@link CachePolicy}. */
    public static final class Builder {

        private boolean enabled = true;
        private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
        private Duration ttl = DEFAULT_TTL;
        private int maxEntriesPerTenant = DEFAULT_MAX_ENTRIES_PER_TENANT;

        private Builder() {
        }

        /**
         * Enables or disables caching.
         *
         * @param enabled the new flag value
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the cosine similarity threshold.
         *
         * @param similarityThreshold a value in {@code (0, 1]}
         * @return this builder
         */
        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /**
         * Sets the entry time to live.
         *
         * @param ttl a positive duration; a zero duration means entries never expire by age
         * @return this builder
         */
        public Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        /**
         * Sets the per-tenant entry ceiling.
         *
         * @param maxEntriesPerTenant a positive entry count
         * @return this builder
         */
        public Builder maxEntriesPerTenant(int maxEntriesPerTenant) {
            this.maxEntriesPerTenant = maxEntriesPerTenant;
            return this;
        }

        /**
         * Validates and builds the policy.
         *
         * @return an immutable policy
         * @throws IllegalArgumentException if the threshold is outside {@code (0, 1]}, the TTL is
         *                                  negative, or the entry ceiling is not positive
         */
        public CachePolicy build() {
            if (similarityThreshold <= 0d || similarityThreshold > 1d) {
                throw new IllegalArgumentException(
                        "similarityThreshold must be within (0, 1] but was " + similarityThreshold);
            }
            if (ttl == null || ttl.isNegative()) {
                throw new IllegalArgumentException("ttl must not be negative");
            }
            if (maxEntriesPerTenant <= 0) {
                throw new IllegalArgumentException(
                        "maxEntriesPerTenant must be positive but was " + maxEntriesPerTenant);
            }
            return new CachePolicy(this);
        }
    }
}

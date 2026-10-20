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
package io.tollgate.spring;

import io.tollgate.core.quota.QuotaAction;
import io.tollgate.core.quota.QuotaPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Tollgate, bound from the {@code tollgate.*} namespace.
 *
 * <p>Defaults are chosen to be safe for a first deployment: caching on, quotas off until a store
 * is supplied, auditing limited to an in-memory ring. Nothing here requires an API key or a
 * network call, so a Spring Boot application can add the starter and immediately observe what
 * its LLM traffic would cost.
 */
@ConfigurationProperties(prefix = "tollgate")
public class TollgateProperties {

    /** Whether Tollgate auto-configuration is active. */
    private boolean enabled = true;

    private final Cache cache = new Cache();
    private final Quota quota = new Quota();
    private final Routing routing = new Routing();
    private final Audit audit = new Audit();

    /** @return whether auto-configuration is active */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether auto-configuration is active.
     *
     * @param enabled the new flag value
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the cache settings */
    public Cache getCache() {
        return cache;
    }

    /** @return the quota settings */
    public Quota getQuota() {
        return quota;
    }

    /** @return the routing settings */
    public Routing getRouting() {
        return routing;
    }

    /** @return the audit settings */
    public Audit getAudit() {
        return audit;
    }

    /** Cache settings. */
    public static class Cache {

        /** Whether semantic caching is enabled. */
        private boolean enabled = true;

        /** Cosine similarity above which two questions are considered the same. */
        private double similarityThreshold = 0.75d;

        /** How long a cached answer stays valid. */
        private Duration ttl = Duration.ofHours(6);

        /** Maximum cached entries retained per tenant. */
        private int maxEntriesPerTenant = 2_000;

        /** @return whether caching is enabled */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether caching is enabled.
         *
         * @param enabled the new flag value
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the similarity threshold */
        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        /**
         * Sets the similarity threshold.
         *
         * @param similarityThreshold a value in {@code (0, 1]}
         */
        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        /** @return the entry time to live */
        public Duration getTtl() {
            return ttl;
        }

        /**
         * Sets the entry time to live.
         *
         * @param ttl the duration
         */
        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        /** @return the per-tenant entry ceiling */
        public int getMaxEntriesPerTenant() {
            return maxEntriesPerTenant;
        }

        /**
         * Sets the per-tenant entry ceiling.
         *
         * @param maxEntriesPerTenant the ceiling
         */
        public void setMaxEntriesPerTenant(int maxEntriesPerTenant) {
            this.maxEntriesPerTenant = maxEntriesPerTenant;
        }
    }

    /** Quota settings. */
    public static class Quota {

        /** Whether quota enforcement is enabled. Requires a {@code QuotaStore} bean. */
        private boolean enabled;

        /** Tokens allowed per window. */
        private long tokensPerWindow = 1_000_000L;

        /** The length of the budget window. */
        private Duration window = Duration.ofDays(30);

        /** Sustained token rate ceiling per second, zero for unlimited. */
        private long maxTokensPerSecond;

        /** What to do when a tenant exceeds its budget. */
        private QuotaAction action = QuotaAction.REJECT;

        /**
         * Converts these settings into a runtime policy.
         *
         * @return an immutable policy
         */
        public QuotaPolicy toPolicy() {
            return new QuotaPolicy(tokensPerWindow, window, maxTokensPerSecond, action);
        }

        /** @return whether quota enforcement is enabled */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether quota enforcement is enabled.
         *
         * @param enabled the new flag value
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the tokens allowed per window */
        public long getTokensPerWindow() {
            return tokensPerWindow;
        }

        /**
         * Sets the tokens allowed per window.
         *
         * @param tokensPerWindow the allowance
         */
        public void setTokensPerWindow(long tokensPerWindow) {
            this.tokensPerWindow = tokensPerWindow;
        }

        /** @return the budget window */
        public Duration getWindow() {
            return window;
        }

        /**
         * Sets the budget window.
         *
         * @param window the duration
         */
        public void setWindow(Duration window) {
            this.window = window;
        }

        /** @return the sustained rate ceiling */
        public long getMaxTokensPerSecond() {
            return maxTokensPerSecond;
        }

        /**
         * Sets the sustained rate ceiling.
         *
         * @param maxTokensPerSecond tokens per second, zero for unlimited
         */
        public void setMaxTokensPerSecond(long maxTokensPerSecond) {
            this.maxTokensPerSecond = maxTokensPerSecond;
        }

        /** @return the overrun action */
        public QuotaAction getAction() {
            return action;
        }

        /**
         * Sets the overrun action.
         *
         * @param action the action
         */
        public void setAction(QuotaAction action) {
            this.action = action;
        }
    }

    /** Routing settings. */
    public static class Routing {

        /** How many quality tiers below the best available remain acceptable. */
        private int qualityTolerance = 2;

        /** Relative weight of cost when ranking acceptable models. */
        private double costWeight = 0.7d;

        /** Relative weight of latency when ranking acceptable models. */
        private double latencyWeight = 0.3d;

        /** How many alternative models may be tried when a provider fails. */
        private int maxFallbacks = 1;

        /** @return the quality tolerance */
        public int getQualityTolerance() {
            return qualityTolerance;
        }

        /**
         * Sets the quality tolerance.
         *
         * @param qualityTolerance the tolerance in tiers
         */
        public void setQualityTolerance(int qualityTolerance) {
            this.qualityTolerance = qualityTolerance;
        }

        /** @return the cost weight */
        public double getCostWeight() {
            return costWeight;
        }

        /**
         * Sets the cost weight.
         *
         * @param costWeight the weight
         */
        public void setCostWeight(double costWeight) {
            this.costWeight = costWeight;
        }

        /** @return the latency weight */
        public double getLatencyWeight() {
            return latencyWeight;
        }

        /**
         * Sets the latency weight.
         *
         * @param latencyWeight the weight
         */
        public void setLatencyWeight(double latencyWeight) {
            this.latencyWeight = latencyWeight;
        }

        /** @return the fallback budget */
        public int getMaxFallbacks() {
            return maxFallbacks;
        }

        /**
         * Sets the fallback budget.
         *
         * @param maxFallbacks the number of alternatives to try
         */
        public void setMaxFallbacks(int maxFallbacks) {
            this.maxFallbacks = maxFallbacks;
        }
    }

    /** Audit settings. */
    public static class Audit {

        /** Whether auditing is enabled. */
        private boolean enabled = true;

        /** How many events the in-memory ring retains. */
        private int capacity = 1_000;

        /** @return whether auditing is enabled */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether auditing is enabled.
         *
         * @param enabled the new flag value
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the ring capacity */
        public int getCapacity() {
            return capacity;
        }

        /**
         * Sets the ring capacity.
         *
         * @param capacity the number of events to retain
         */
        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }
}

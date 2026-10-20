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
package io.tollgate.core.quota;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The default, in-process {@link QuotaStore}.
 *
 * <h2>Concurrency</h2>
 * <p>Each tenant owns a small mutable state object guarded by its own monitor. Contention is
 * therefore bounded by the number of concurrent calls <em>for the same tenant</em>, which is
 * exactly the contention that must exist anyway: two calls cannot both spend the last token.
 * Different tenants never block each other.
 *
 * <p>The critical section is a handful of arithmetic operations. It deliberately does not
 * include the model call, so a slow provider cannot hold a tenant's quota lock.
 *
 * <h2>Scope</h2>
 * <p>State lives in this process only. In a multi-instance deployment each instance keeps its own
 * counter, so a tenant's effective budget is the configured budget multiplied by the number of
 * instances. That is a real limitation, documented rather than hidden; a shared
 * {@link QuotaStore} backed by Redis is the intended extension point.
 */
public final class InMemoryQuotaStore implements QuotaStore {

    private final QuotaPolicy policy;
    private final LongSupplier clock;
    private final Map<String, TenantState> states = new ConcurrentHashMap<>();

    /**
     * Creates a store with the given policy.
     *
     * @param policy the quota policy applied to every tenant
     */
    public InMemoryQuotaStore(QuotaPolicy policy) {
        this(policy, System::currentTimeMillis);
    }

    /**
     * Creates a store with an explicit clock.
     *
     * @param policy the quota policy applied to every tenant
     * @param clock  a millisecond clock, injectable for tests
     */
    public InMemoryQuotaStore(QuotaPolicy policy, LongSupplier clock) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean tryConsume(String tenant, long tokens) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be positive but was " + tokens);
        }

        long now = clock.getAsLong();
        TenantState state = states.computeIfAbsent(tenant, t -> new TenantState(now, policy));
        synchronized (state) {
            state.rollWindow(now, policy.window().toMillis());
            if (policy.hasRateLimit()) {
                state.refill(now, policy.maxTokensPerSecond());
                if (state.rateTokens < tokens) {
                    state.rejectedCalls++;
                    return false;
                }
            }
            if (state.usedTokens + tokens > policy.tokensPerWindow()) {
                state.rejectedCalls++;
                return false;
            }
            state.usedTokens += tokens;
            if (policy.hasRateLimit()) {
                state.rateTokens -= tokens;
            }
            return true;
        }
    }

    @Override
    public QuotaSnapshot snapshot(String tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        long now = clock.getAsLong();
        TenantState state = states.get(tenant);
        if (state == null) {
            return new QuotaSnapshot(tenant, 0L, policy.tokensPerWindow(),
                    now + policy.window().toMillis(), 0L);
        }
        synchronized (state) {
            state.rollWindow(now, policy.window().toMillis());
            long resetsAt = state.windowStartMillis + policy.window().toMillis();
            return new QuotaSnapshot(tenant, state.usedTokens, policy.tokensPerWindow(),
                    resetsAt, state.rejectedCalls);
        }
    }

    @Override
    public void reset(String tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        states.remove(tenant);
    }

    /**
     * Returns the policy this store enforces.
     *
     * @return the policy
     */
    public QuotaPolicy policy() {
        return policy;
    }

    private static final class TenantState {

        private long windowStartMillis;
        private long usedTokens;
        private long rejectedCalls;
        private double rateTokens;
        private long lastRefillMillis;

        private TenantState(long now, QuotaPolicy policy) {
            this.windowStartMillis = now;
            this.lastRefillMillis = now;
            this.rateTokens = policy.maxTokensPerSecond();
        }

        private void rollWindow(long now, long windowMillis) {
            if (windowMillis > 0 && now - windowStartMillis >= windowMillis) {
                windowStartMillis = now;
                usedTokens = 0;
                rejectedCalls = 0;
            }
        }

        private void refill(long now, long maxPerSecond) {
            double elapsedSeconds = (now - lastRefillMillis) / 1000d;
            if (elapsedSeconds > 0) {
                rateTokens = Math.min(maxPerSecond, rateTokens + elapsedSeconds * maxPerSecond);
                lastRefillMillis = now;
            }
        }
    }
}

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
package io.tollgate.core.pipeline;

import io.tollgate.core.audit.AuditEvent;
import io.tollgate.core.audit.AuditLogger;
import io.tollgate.core.cache.CacheEntry;
import io.tollgate.core.cache.CachePolicy;
import io.tollgate.core.cache.CacheStats;
import io.tollgate.core.cache.EmbeddingProvider;
import io.tollgate.core.cache.HashingEmbeddingProvider;
import io.tollgate.core.cache.InMemorySemanticCache;
import io.tollgate.core.cache.SemanticCache;
import io.tollgate.core.exception.NoEligibleModelException;
import io.tollgate.core.exception.ProviderException;
import io.tollgate.core.exception.QuotaExceededException;
import io.tollgate.core.meter.CostReport;
import io.tollgate.core.meter.UsageListener;
import io.tollgate.core.meter.UsageRecord;
import io.tollgate.core.meter.UsageRegistry;
import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.LlmResponse;
import io.tollgate.core.model.Message;
import io.tollgate.core.model.ModelSpec;
import io.tollgate.core.model.RoutingProfile;
import io.tollgate.core.provider.LlmProvider;
import io.tollgate.core.quota.QuotaPolicy;
import io.tollgate.core.quota.QuotaSnapshot;
import io.tollgate.core.quota.QuotaStore;
import io.tollgate.core.route.ModelRouter;
import io.tollgate.core.route.ProfileAwareRouter;
import io.tollgate.core.util.LiteralGuard;
import io.tollgate.core.util.PromptDigest;
import io.tollgate.core.util.QueryCanonicalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The front door of Tollgate: one object that governs every model call.
 *
 * <h2>Pipeline order</h2>
 * <p>A call passes through five stages, and the order is not arbitrary:
 *
 * <ol>
 *   <li><b>Canonicalise and look up the cache.</b> Doing this before routing means a cache hit
 *       never consumes quota, never touches a provider and never appears in a model's bill. It
 *       also means a hit is served even when the tenant is out of budget, which is the correct
 *       behaviour: the answer was already paid for.</li>
 *   <li><b>Route.</b> Choose a model from everything registered, honouring the caller's profile
 *       and the context window.</li>
 *   <li><b>Reserve quota.</b> Reserve before calling. Reservations are estimates and are never
 *       refunded, which biases the accounting in the safe direction.</li>
 *   <li><b>Invoke, with fallback.</b> If a provider fails, the request is re-routed across the
 *       remaining candidates rather than being handed back to the application.</li>
 *   <li><b>Price, store, meter, audit.</b> Pricing uses the provider's reported token counts, not
 *       the estimate, so the bill reflects what was actually consumed.</li>
 * </ol>
 *
 * <h2>What this class is not</h2>
 * <p>It is not a client library. It does not know how to talk to OpenAI, Anthropic or Ollama.
 * That is deliberate: transports churn every few months, governance policy does not. Implementing
 * {@link LlmProvider} for your vendor of choice is a few dozen lines and keeps this module free
 * of anything that would need updating when a vendor renames an endpoint.
 *
 * <p>Instances are thread safe and are intended to be created once and shared.
 */
public final class Tollgate {

    private final Map<String, LlmProvider> providers;
    private final List<ModelSpec> models;
    private final ModelRouter router;
    private final SemanticCache cache;
    private final EmbeddingProvider embeddingProvider;
    private final CachePolicy cachePolicy;
    private final QuotaStore quotaStore;
    private final QuotaPolicy quotaPolicy;
    private final AuditLogger auditLogger;
    private final UsageRegistry usageRegistry;
    private final List<UsageListener> extraListeners;
    private final int maxFallbacks;
    private final AtomicLong sequence = new AtomicLong();

    private Tollgate(Builder builder) {
        this.providers = Map.copyOf(builder.providers);
        this.router = builder.router;
        this.cachePolicy = builder.cachePolicy;
        this.embeddingProvider = builder.embeddingProvider;
        this.cache = builder.cache != null ? builder.cache : new InMemorySemanticCache(builder.cachePolicy);
        this.quotaStore = builder.quotaStore;
        this.quotaPolicy = builder.quotaPolicy;
        this.auditLogger = builder.auditLogger;
        this.usageRegistry = builder.usageRegistry;
        this.extraListeners = List.copyOf(builder.listeners);
        this.maxFallbacks = builder.maxFallbacks;

        List<ModelSpec> collected = new ArrayList<>();
        for (LlmProvider provider : this.providers.values()) {
            collected.addAll(provider.models());
        }
        collected.sort(Comparator.comparing(ModelSpec::id));
        this.models = List.copyOf(collected);
    }

    /**
     * Creates a builder.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Executes a governed call.
     *
     * @param request the request to serve
     * @return the response, priced and audited
     * @throws QuotaExceededException    if the tenant is out of budget under a rejecting policy
     * @throws NoEligibleModelException  if no registered model can serve the request
     * @throws ProviderException         if every candidate failed
     */
    public LlmResponse call(LlmRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String traceId = nextTraceId();
        String tenant = request.tenant();

        String canonicalQuestion = canonicalQuestion(request);
        String literalFingerprint = LiteralGuard.fingerprint(canonicalQuestion);
        boolean cacheEligible = isCacheEligible(request, canonicalQuestion);
        float[] embedding = null;

        if (cacheEligible) {
            embedding = embeddingProvider.embed(canonicalQuestion);
            String fingerprint = systemFingerprint(request);
            Optional<CacheEntry> hit = cache.lookup(tenant, fingerprint, literalFingerprint,
                    canonicalQuestion, embedding);
            if (hit.isPresent()) {
                CacheEntry entry = hit.get();
                LlmResponse cached = entry.original().asCacheHit(traceId);
                publish(request, cached, entry.originalTotalTokens(), entry.originalCostUsd());
                audit(request, cached, AuditEvent.Outcome.CACHE_HIT);
                return cached;
            }
        }

        ModelSpec model = resolveModel(request);

        if (quotaStore != null) {
            long reservation = request.estimatedPromptTokens() + request.maxTokens();
            boolean granted = quotaStore.tryConsume(tenant, reservation);
            if (!granted) {
                switch (quotaPolicy.action()) {
                    case REJECT -> {
                        auditRejection(request);
                        throw new QuotaExceededException(tenant, reservation);
                    }
                    case DEGRADE -> model = degrade(request, model);
                    case LOG_ONLY -> {
                    }
                }
            }
        }

        LlmResponse response = invokeWithFallback(model, request, traceId);

        double cost = model.costOf(response.promptTokens(), response.completionTokens());
        response = response.priced(cost, traceId);

        if (cacheEligible) {
            CacheEntry entry = new CacheEntry(systemFingerprint(request), literalFingerprint,
                    canonicalQuestion, embedding, response);
            cache.put(tenant, entry);
        }

        publish(request, response, 0, 0d);
        audit(request, response, AuditEvent.Outcome.SUCCESS);
        return response;
    }

    /**
     * Convenience overload for a single-turn prompt.
     *
     * @param tenant the billing and quota owner
     * @param prompt the user prompt
     * @return the priced response
     */
    public LlmResponse call(String tenant, String prompt) {
        return call(LlmRequest.of(tenant, prompt));
    }

    /**
     * Returns every model registered across all providers.
     *
     * @return an immutable, id-sorted list
     */
    public List<ModelSpec> models() {
        return models;
    }

    /**
     * Returns the cumulative cost report.
     *
     * @return an immutable report
     */
    public CostReport report() {
        return usageRegistry.report();
    }

    /**
     * Returns cumulative cache statistics.
     *
     * @return an immutable snapshot
     */
    public CacheStats cacheStats() {
        return cache.stats();
    }

    /**
     * Returns the budget state of a tenant.
     *
     * @param tenant the tenant identifier
     * @return the snapshot, or {@code null} when no quota store is configured
     */
    public QuotaSnapshot quota(String tenant) {
        return quotaStore == null ? null : quotaStore.snapshot(tenant);
    }

    /**
     * Returns the audit trail, newest first.
     *
     * @param limit the maximum number of events
     * @return an immutable list
     */
    public List<AuditEvent> auditTrail(int limit) {
        return auditLogger.recent(limit);
    }

    /**
     * Drops all cached answers for a tenant.
     *
     * <p>Call this when tenant data is deleted, so that cached answers do not outlive the data
     * they were derived from.
     *
     * @param tenant the tenant to purge
     */
    public void invalidateTenant(String tenant) {
        cache.invalidateTenant(tenant);
    }

    private ModelSpec resolveModel(LlmRequest request) {
        if (request.model() != null) {
            return models.stream()
                    .filter(model -> model.id().equals(request.model()))
                    .findFirst()
                    .orElseThrow(() -> new NoEligibleModelException(
                            "Model '" + request.model() + "' is pinned on the request but is not registered. "
                                    + "Known models: " + models.stream().map(ModelSpec::id).toList()));
        }
        return router.route(request, models);
    }

    private ModelSpec degrade(LlmRequest request, ModelSpec current) {
        List<ModelSpec> others = models.stream()
                .filter(model -> !model.id().equals(current.id()))
                .toList();
        if (others.isEmpty()) {
            return current;
        }
        LlmRequest cheapestRequest = request.toBuilder()
                .routingProfile(RoutingProfile.CHEAPEST)
                .build();
        try {
            ModelSpec cheaper = router.route(cheapestRequest, others);
            return cheaper;
        } catch (NoEligibleModelException ex) {
            return current;
        }
    }

    private LlmResponse invokeWithFallback(ModelSpec model, LlmRequest request, String traceId) {
        List<ModelSpec> remaining = new ArrayList<>(models);
        RuntimeException lastFailure = null;
        ModelSpec candidate = model;

        for (int attempt = 0; attempt <= maxFallbacks; attempt++) {
            final String candidateId = candidate.id();
            remaining.removeIf(spec -> spec.id().equals(candidateId));
            LlmProvider provider = providers.get(candidate.providerId());
            if (provider == null) {
                lastFailure = new ProviderException(candidate.providerId(), candidate.id(),
                        "No provider registered under this id", null);
            } else {
                try {
                    return provider.complete(candidate, request);
                } catch (RuntimeException ex) {
                    lastFailure = new ProviderException(candidate.providerId(), candidate.id(),
                            String.valueOf(ex.getMessage()), ex);
                }
            }
            if (remaining.isEmpty() || request.model() != null) {
                break;
            }
            try {
                candidate = router.route(request, remaining);
            } catch (NoEligibleModelException ex) {
                break;
            }
        }
        auditFailure(request);
        throw lastFailure != null ? lastFailure
                : new ProviderException("unknown", model.id(), "Call failed with no recorded cause", null);
    }

    private boolean isCacheEligible(LlmRequest request, String canonicalQuestion) {
        return cachePolicy.enabled()
                && request.cacheable()
                && request.isSingleTurn()
                && !canonicalQuestion.isEmpty();
    }

    private String canonicalQuestion(LlmRequest request) {
        String question = request.lastUserMessage();
        if (question == null) {
            return "";
        }
        return QueryCanonicalizer.canonicalize(question);
    }

    private String systemFingerprint(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Message message : request.messages()) {
            if (message.role() == Message.Role.SYSTEM) {
                sb.append(message.content()).append('\u0000');
            }
        }
        if (sb.length() == 0) {
            return "-";
        }
        return PromptDigest.shortSha256(sb.toString(), 16);
    }

    private void publish(LlmRequest request, LlmResponse response, int savedTokens, double savedCostUsd) {
        String providerId = models.stream()
                .filter(model -> model.id().equals(response.model()))
                .map(ModelSpec::providerId)
                .findFirst()
                .orElse("unknown");

        UsageRecord record = new UsageRecord(
                response.traceId(),
                request.tenant(),
                response.model(),
                providerId,
                response.promptTokens(),
                response.completionTokens(),
                savedTokens,
                response.costUsd(),
                savedCostUsd,
                response.cacheHit(),
                response.latencyMillis(),
                System.currentTimeMillis(),
                request.tags());

        usageRegistry.onUsage(record);
        for (UsageListener listener : extraListeners) {
            try {
                listener.onUsage(record);
            } catch (RuntimeException ignored) {
                // A broken listener must never fail a call that already succeeded.
            }
        }
    }

    private void audit(LlmRequest request, LlmResponse response, AuditEvent.Outcome outcome) {
        try {
            auditLogger.record(new AuditEvent(
                    response.traceId(),
                    request.tenant(),
                    response.model(),
                    PromptDigest.sha256(canonicalQuestion(request)),
                    outcome,
                    response.promptTokens(),
                    response.completionTokens(),
                    response.costUsd(),
                    response.latencyMillis(),
                    System.currentTimeMillis(),
                    request.tags()));
        } catch (RuntimeException ignored) {
            // Auditing is best effort on the request path.
        }
    }

    private void auditRejection(LlmRequest request) {
        try {
            auditLogger.record(new AuditEvent(
                    nextTraceId(), request.tenant(), "-",
                    PromptDigest.sha256(canonicalQuestion(request)),
                    AuditEvent.Outcome.QUOTA_REJECTED, 0, 0, 0d, 0L,
                    System.currentTimeMillis(), request.tags()));
        } catch (RuntimeException ignored) {
            // Auditing is best effort on the request path.
        }
    }

    private void auditFailure(LlmRequest request) {
        try {
            auditLogger.record(new AuditEvent(
                    nextTraceId(), request.tenant(), "-",
                    PromptDigest.sha256(canonicalQuestion(request)),
                    AuditEvent.Outcome.PROVIDER_ERROR, 0, 0, 0d, 0L,
                    System.currentTimeMillis(), request.tags()));
        } catch (RuntimeException ignored) {
            // Auditing is best effort on the request path.
        }
    }

    private String nextTraceId() {
        return Long.toHexString(System.currentTimeMillis()) + '-'
                + Long.toString(sequence.incrementAndGet(), 36);
    }

    /** Mutable builder for {@link Tollgate}. */
    public static final class Builder {

        private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
        private final List<UsageListener> listeners = new CopyOnWriteArrayList<>();
        private final UsageRegistry usageRegistry = new UsageRegistry();

        private ModelRouter router = new ProfileAwareRouter();
        private CachePolicy cachePolicy = CachePolicy.defaults();
        private EmbeddingProvider embeddingProvider = new HashingEmbeddingProvider();
        private SemanticCache cache;
        private QuotaStore quotaStore;
        private QuotaPolicy quotaPolicy = QuotaPolicy.monthly(1_000_000L);
        private AuditLogger auditLogger = AuditLogger.noop();
        private int maxFallbacks = 1;

        private Builder() {
        }

        /**
         * Registers a provider and all of its models.
         *
         * @param provider the provider to register
         * @return this builder
         */
        public Builder provider(LlmProvider provider) {
            Objects.requireNonNull(provider, "provider must not be null");
            this.providers.put(provider.id(), provider);
            return this;
        }

        /**
         * Replaces the routing strategy.
         *
         * @param router the router to use
         * @return this builder
         */
        public Builder router(ModelRouter router) {
            this.router = Objects.requireNonNull(router, "router must not be null");
            return this;
        }

        /**
         * Replaces the cache policy.
         *
         * @param cachePolicy the policy to use
         * @return this builder
         */
        public Builder cachePolicy(CachePolicy cachePolicy) {
            this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy must not be null");
            return this;
        }

        /**
         * Replaces the embedding provider used for semantic matching.
         *
         * @param embeddingProvider the provider to use
         * @return this builder
         */
        public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
            this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider must not be null");
            return this;
        }

        /**
         * Replaces the cache implementation, for example to share one across siblings.
         *
         * @param cache the cache to use
         * @return this builder
         */
        public Builder cache(SemanticCache cache) {
            this.cache = cache;
            return this;
        }

        /**
         * Enables quota enforcement.
         *
         * @param quotaStore  the store holding tenant budgets
         * @param quotaPolicy the policy describing limits and overrun behaviour
         * @return this builder
         */
        public Builder quota(QuotaStore quotaStore, QuotaPolicy quotaPolicy) {
            this.quotaStore = Objects.requireNonNull(quotaStore, "quotaStore must not be null");
            this.quotaPolicy = Objects.requireNonNull(quotaPolicy, "quotaPolicy must not be null");
            return this;
        }

        /**
         * Replaces the audit logger.
         *
         * @param auditLogger the logger to use
         * @return this builder
         */
        public Builder auditLogger(AuditLogger auditLogger) {
            this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger must not be null");
            return this;
        }

        /**
         * Adds a listener notified of every completed call.
         *
         * @param listener the listener to add
         * @return this builder
         */
        public Builder listener(UsageListener listener) {
            this.listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
            return this;
        }

        /**
         * Sets how many additional models may be tried when a provider fails.
         *
         * @param maxFallbacks the number of fallback attempts, {@code 0} to disable
         * @return this builder
         */
        public Builder maxFallbacks(int maxFallbacks) {
            if (maxFallbacks < 0) {
                throw new IllegalArgumentException("maxFallbacks must not be negative");
            }
            this.maxFallbacks = maxFallbacks;
            return this;
        }

        /**
         * Validates and builds the gateway.
         *
         * @return a configured gateway
         * @throws IllegalStateException if no provider was registered
         */
        public Tollgate build() {
            if (providers.isEmpty()) {
                throw new IllegalStateException("At least one LlmProvider must be registered");
            }
            return new Tollgate(this);
        }
    }
}

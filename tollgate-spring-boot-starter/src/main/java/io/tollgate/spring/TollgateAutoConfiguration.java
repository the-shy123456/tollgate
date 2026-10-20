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

import io.tollgate.core.audit.AuditLogger;
import io.tollgate.core.audit.InMemoryAuditLog;
import io.tollgate.core.cache.CachePolicy;
import io.tollgate.core.cache.EmbeddingProvider;
import io.tollgate.core.cache.HashingEmbeddingProvider;
import io.tollgate.core.cache.InMemorySemanticCache;
import io.tollgate.core.cache.SemanticCache;
import io.tollgate.core.meter.UsageRegistry;
import io.tollgate.core.pipeline.Tollgate;
import io.tollgate.core.provider.LlmProvider;
import io.tollgate.core.quota.QuotaStore;
import io.tollgate.core.route.BalancedRouter;
import io.tollgate.core.route.ModelRouter;
import io.tollgate.core.route.ProfileAwareRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Wires a {@link Tollgate} instance into a Spring Boot application.
 *
 * <h2>Contract</h2>
 * <p>The application supplies one or more {@link LlmProvider} beans. Every other component is
 * created with a sane default and can be replaced by simply declaring a bean of the same type:
 * routing, embeddings, cache, quota store, audit logger and usage registry are all participating
 * in normal {@link ConditionalOnMissingBean} back-off.
 *
 * <p>This means adoption is incremental. A team can start by adding the starter and a provider,
 * observe the cost report for a week, then introduce a quota store once they know what a normal
 * month looks like. Governance that has to be fully designed up front never gets deployed.
 */
@AutoConfiguration
@EnableConfigurationProperties(TollgateProperties.class)
@ConditionalOnProperty(prefix = "tollgate", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TollgateAutoConfiguration {

    /**
     * Creates the routing strategy.
     *
     * @param properties the Tollgate properties
     * @return a profile-aware router backed by a balanced strategy
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelRouter tollgateModelRouter(TollgateProperties properties) {
        TollgateProperties.Routing routing = properties.getRouting();
        return new ProfileAwareRouter(new BalancedRouter(
                routing.getQualityTolerance(),
                routing.getCostWeight(),
                routing.getLatencyWeight()));
    }

    /**
     * Creates the embedding provider.
     *
     * @return the dependency-free hashing embedder
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingProvider tollgateEmbeddingProvider() {
        return new HashingEmbeddingProvider();
    }

    /**
     * Creates the semantic cache.
     *
     * @param properties the Tollgate properties
     * @return an in-memory cache honouring the configured policy
     */
    @Bean
    @ConditionalOnMissingBean
    public SemanticCache tollgateSemanticCache(TollgateProperties properties) {
        TollgateProperties.Cache cache = properties.getCache();
        CachePolicy policy = CachePolicy.builder()
                .enabled(cache.isEnabled())
                .similarityThreshold(cache.getSimilarityThreshold())
                .ttl(cache.getTtl())
                .maxEntriesPerTenant(cache.getMaxEntriesPerTenant())
                .build();
        return new InMemorySemanticCache(policy);
    }

    /**
     * Creates the usage registry.
     *
     * @return a fresh registry that also acts as a usage listener
     */
    @Bean
    @ConditionalOnMissingBean
    public UsageRegistry tollgateUsageRegistry() {
        return new UsageRegistry();
    }

    /**
     * Creates the audit logger.
     *
     * @param properties the Tollgate properties
     * @return an in-memory ring when auditing is enabled, a no-op logger otherwise
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogger tollgateAuditLogger(TollgateProperties properties) {
        if (!properties.getAudit().isEnabled()) {
            return AuditLogger.noop();
        }
        return new InMemoryAuditLog(properties.getAudit().getCapacity());
    }

    /**
     * Creates the gateway itself.
     *
     * @param providers    every provider bean in the context
     * @param properties   the Tollgate properties
     * @param router       the routing strategy
     * @param embeddings   the embedding provider
     * @param cache        the semantic cache
     * @param registry     the usage registry
     * @param auditLogger  the audit logger
     * @param quotaStores  an optional quota store; quotas are disabled when absent
     * @param listeners    any additional usage listeners
     * @return a configured gateway
     */
    @Bean
    @ConditionalOnMissingBean
    public Tollgate tollgate(ObjectProvider<LlmProvider> providers,
                             TollgateProperties properties,
                             ModelRouter router,
                             EmbeddingProvider embeddings,
                             SemanticCache cache,
                             UsageRegistry registry,
                             AuditLogger auditLogger,
                             ObjectProvider<QuotaStore> quotaStores,
                             ObjectProvider<io.tollgate.core.meter.UsageListener> listeners) {

        List<LlmProvider> providerList = providers.orderedStream().toList();
        if (providerList.isEmpty()) {
            throw new IllegalStateException("""
                    Tollgate found no LlmProvider bean in the application context.

                    Declare at least one, for example:

                        @Bean
                        LlmProvider openAi() {
                            return new OpenAiProvider(apiKey, List.of(modelSpec));
                        }

                    For a quick local experiment, use io.tollgate.core.provider.SimulatedProvider.""");
        }

        CachePolicy policy = CachePolicy.builder()
                .enabled(properties.getCache().isEnabled())
                .similarityThreshold(properties.getCache().getSimilarityThreshold())
                .ttl(properties.getCache().getTtl())
                .maxEntriesPerTenant(properties.getCache().getMaxEntriesPerTenant())
                .build();

        Tollgate.Builder builder = Tollgate.builder()
                .router(router)
                .embeddingProvider(embeddings)
                .cache(cache)
                .cachePolicy(policy)
                .auditLogger(auditLogger)
                .maxFallbacks(properties.getRouting().getMaxFallbacks())
                .listener(registry);

        for (LlmProvider provider : providerList) {
            builder.provider(provider);
        }
        listeners.orderedStream()
                .filter(listener -> !(listener instanceof UsageRegistry))
                .forEach(builder::listener);

        QuotaStore quotaStore = quotaStores.getIfAvailable();
        if (properties.getQuota().isEnabled()) {
            if (quotaStore == null) {
                throw new IllegalStateException("""
                        tollgate.quota.enabled is true but no QuotaStore bean was found.

                        Declare one, for example:

                            @Bean
                            QuotaStore quotaStore() {
                                return new InMemoryQuotaStore(QuotaPolicy.monthly(1_000_000));
                            }""");
            }
            builder.quota(quotaStore, properties.getQuota().toPolicy());
        }

        return builder.build();
    }
}

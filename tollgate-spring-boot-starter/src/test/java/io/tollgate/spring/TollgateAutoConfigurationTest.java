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

import io.tollgate.core.cache.EmbeddingProvider;
import io.tollgate.core.cache.HashingEmbeddingProvider;
import io.tollgate.core.cache.SemanticCache;
import io.tollgate.core.model.ModelSpec;
import io.tollgate.core.pipeline.Tollgate;
import io.tollgate.core.provider.LlmProvider;
import io.tollgate.core.provider.SimulatedProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TollgateAutoConfigurationTest {

    private static final ModelSpec DEMO_MODEL =
            ModelSpec.of("demo-small", "demo", 0.1, 0.2, 8_000, 2, 500);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TollgateAutoConfiguration.class));

    @Test
    @DisplayName("creates a gateway when a provider bean is present")
    void createsGatewayWithProvider() {
        runner.withBean(LlmProvider.class, () -> new SimulatedProvider("demo", List.of(DEMO_MODEL)))
                .run(context -> {
                    assertThat(context).hasSingleBean(Tollgate.class);
                    assertThat(context).hasSingleBean(EmbeddingProvider.class);
                    assertThat(context).hasSingleBean(SemanticCache.class);
                });
    }

    @Test
    @DisplayName("fails with an actionable message when no provider is declared")
    void failsWithoutProvider() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure()
                .hasMessageContaining("no LlmProvider bean"));
    }

    @Test
    @DisplayName("backs off when the application disables Tollgate")
    void backsOffWhenDisabled() {
        runner.withPropertyValues("tollgate.enabled=false")
                .withBean(LlmProvider.class, () -> new SimulatedProvider("demo", List.of(DEMO_MODEL)))
                .run(context -> assertThat(context).doesNotHaveBean(Tollgate.class));
    }

    @Test
    @DisplayName("honours a user-supplied embedding provider")
    void honoursUserEmbeddingProvider() {
        EmbeddingProvider custom = new HashingEmbeddingProvider(64);

        runner.withBean(LlmProvider.class, () -> new SimulatedProvider("demo", List.of(DEMO_MODEL)))
                .withBean(EmbeddingProvider.class, () -> custom)
                .run(context -> assertThat(context.getBean(EmbeddingProvider.class)).isSameAs(custom));
    }

    @Test
    @DisplayName("binds cache settings from the environment")
    void bindsCacheSettings() {
        runner.withPropertyValues(
                        "tollgate.cache.similarity-threshold=0.91",
                        "tollgate.cache.max-entries-per-tenant=7")
                .withBean(LlmProvider.class, () -> new SimulatedProvider("demo", List.of(DEMO_MODEL)))
                .run(context -> {
                    TollgateProperties properties = context.getBean(TollgateProperties.class);
                    assertThat(properties.getCache().getSimilarityThreshold()).isEqualTo(0.91d);
                    assertThat(properties.getCache().getMaxEntriesPerTenant()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("routes and reports through the auto-configured gateway")
    void routesAndReports() {
        runner.withBean(LlmProvider.class, () -> new SimulatedProvider("demo", List.of(DEMO_MODEL)))
                .run(context -> {
                    Tollgate tollgate = context.getBean(Tollgate.class);
                    tollgate.call("acme", "如何申请退款");
                    tollgate.call("acme", "如何申请退款");

                    assertThat(tollgate.report().totalCalls()).isEqualTo(2);
                    assertThat(tollgate.report().cacheHits()).isEqualTo(1);
                });
    }
}

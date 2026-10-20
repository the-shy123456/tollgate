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
package io.tollgate.core.route;

import io.tollgate.core.exception.NoEligibleModelException;
import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.ModelSpec;
import io.tollgate.core.model.RoutingProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterTest {

    private static final ModelSpec CHEAP =
            ModelSpec.of("cheap", "demo", 0.10, 0.20, 8_000, 2, 500);
    private static final ModelSpec MID =
            ModelSpec.of("mid", "demo", 1.00, 3.00, 32_000, 3, 800);
    private static final ModelSpec PREMIUM =
            ModelSpec.of("premium", "demo", 5.00, 15.00, 128_000, 5, 2_000);

    private static final List<ModelSpec> POOL = List.of(CHEAP, MID, PREMIUM);

    private final LlmRequest request = LlmRequest.builder()
            .tenant("acme")
            .user("hello")
            .maxTokens(100)
            .build();

    @Test
    @DisplayName("cost-first picks the cheapest eligible model")
    void costFirstPicksCheapest() {
        assertThat(new CostFirstRouter().route(request, POOL).id()).isEqualTo("cheap");
    }

    @Test
    @DisplayName("quality-first picks the highest tier")
    void qualityFirstPicksBest() {
        assertThat(new QualityFirstRouter().route(request, POOL).id()).isEqualTo("premium");
    }

    @Test
    @DisplayName("latency-first picks the fastest model")
    void latencyFirstPicksFastest() {
        assertThat(new LatencyFirstRouter().route(request, POOL).id()).isEqualTo("cheap");
    }

    @Test
    @DisplayName("balanced stays within the quality tolerance, then minimises cost")
    void balancedRespectsQualityFloor() {
        ModelSpec chosen = new BalancedRouter().route(request, POOL);

        assertThat(chosen.id()).isEqualTo("mid");
        assertThat(chosen.qualityTier()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("balanced tolerates a uniformly low-quality pool instead of rejecting it")
    void balancedToleratesLowQualityPool() {
        List<ModelSpec> lowTier = List.of(
                ModelSpec.of("a", "demo", 1.0, 2.0, 8_000, 1, 100),
                ModelSpec.of("b", "demo", 2.0, 4.0, 8_000, 2, 200));

        ModelSpec chosen = new BalancedRouter().route(request, lowTier);

        assertThat(chosen.id()).isEqualTo("a");
    }

    @Test
    @DisplayName("profile-aware router honours the requested profile")
    void profileAwareHonoursProfile() {
        ProfileAwareRouter router = new ProfileAwareRouter();

        assertThat(router.route(cheapestRequest(), POOL).id()).isEqualTo("cheap");
        assertThat(router.route(bestQualityRequest(), POOL).id()).isEqualTo("premium");
        assertThat(router.route(fastestRequest(), POOL).id()).isEqualTo("cheap");
    }

    @Test
    @DisplayName("excludes models whose context window cannot hold prompt plus budget")
    void excludesModelsThatCannotFit() {
        LlmRequest huge = LlmRequest.builder()
                .tenant("acme")
                .user("a".repeat(40_000))
                .maxTokens(1_000)
                .build();

        assertThat(new CostFirstRouter().route(huge, POOL).id()).isIn("mid", "premium");
    }

    @Test
    @DisplayName("fails loudly when no model can hold the request")
    void failsWhenNothingFits() {
        LlmRequest enormous = LlmRequest.builder()
                .tenant("acme")
                .user("a".repeat(40_000))
                .maxTokens(200_000)
                .build();

        assertThatThrownBy(() -> new CostFirstRouter().route(enormous, POOL))
                .isInstanceOf(NoEligibleModelException.class)
                .hasMessageContaining("context window");
    }

    @Test
    @DisplayName("fails loudly when no models are registered")
    void failsWhenPoolIsEmpty() {
        assertThatThrownBy(() -> new CostFirstRouter().route(request, List.of()))
                .isInstanceOf(NoEligibleModelException.class)
                .hasMessageContaining("No models are registered");
    }

    @Test
    @DisplayName("returns the only candidate without scoring")
    void returnsSingleCandidate() {
        assertThat(new BalancedRouter().route(request, List.of(MID)).id()).isEqualTo("mid");
    }

    private LlmRequest cheapestRequest() {
        return request.toBuilder().routingProfile(RoutingProfile.CHEAPEST).build();
    }

    private LlmRequest bestQualityRequest() {
        return request.toBuilder().routingProfile(RoutingProfile.BEST_QUALITY).build();
    }

    private LlmRequest fastestRequest() {
        return request.toBuilder().routingProfile(RoutingProfile.LATENCY_FIRST).build();
    }
}

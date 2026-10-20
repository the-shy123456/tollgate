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
package io.tollgate.example;

import io.tollgate.core.model.ModelSpec;
import io.tollgate.core.provider.LlmProvider;
import io.tollgate.core.provider.SimulatedProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registers the model catalogue used by the demo.
 *
 * <p>Kept separate from {@link DemoApplication} so that the provider does not depend on the
 * gateway which depends on the provider — a cycle Spring Boot rightly refuses to resolve.
 *
 * <p>Replacing this class with one that returns a real provider is the only change needed to
 * point the demo at a live model.
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfiguration {

    /**
     * Creates the simulated provider backing the demo.
     *
     * <p>The catalogue deliberately spans a wide price and quality range so that routing
     * decisions are visible in the output.
     *
     * @return a provider with three tiers
     */
    @Bean
    public LlmProvider simulatedProvider() {
        return new SimulatedProvider("simulated", List.of(
                ModelSpec.of("nano", "simulated", 0.05, 0.10, 8_000, 2, 300),
                ModelSpec.of("standard", "simulated", 0.80, 2.40, 32_000, 3, 700),
                ModelSpec.of("frontier", "simulated", 5.00, 15.00, 128_000, 5, 1_800)));
    }
}

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

import io.tollgate.core.exception.QuotaExceededException;
import io.tollgate.core.meter.CostReport;
import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.LlmResponse;
import io.tollgate.core.model.RoutingProfile;
import io.tollgate.core.pipeline.Tollgate;
import io.tollgate.core.provider.LlmProvider;
import io.tollgate.core.quota.InMemoryQuotaStore;
import io.tollgate.core.quota.QuotaAction;
import io.tollgate.core.quota.QuotaPolicy;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.Duration;

/**
 * A self-contained walkthrough of Tollgate.
 *
 * <p>Runs against {@link SimulatedProvider}, so it needs no API key and no network access. The
 * goal is to make the three governance behaviours visible in a terminal: duplicate questions
 * being served from cache, the same prompt being routed to different models by profile, and a
 * tenant being stopped when its budget runs out.
 */
@SpringBootApplication
public class DemoApplication implements CommandLineRunner {

    private static final String LINE = "=".repeat(64);

    private final Tollgate tollgate;
    private final LlmProvider provider;

    public DemoApplication(Tollgate tollgate, LlmProvider provider) {
        this.tollgate = tollgate;
        this.provider = provider;
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DemoApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);
    }

    @Override
    public void run(String... args) {
        header("Tollgate demo - governing LLM traffic from a Spring Boot app");
        semanticCacheScenario();
        routingScenario();
        quotaScenario();
        costReport();
        footer();
    }

    private void semanticCacheScenario() {
        section("1. Semantic cache - rephrasings hit, different questions do not");

        ask("acme", "如何申请退款");
        ask("acme", "请问我想申请退款呢");
        ask("acme", "退款流程是什么");
        ask("acme", "怎么重置登录密码");

        System.out.printf("  cache hit rate: %.0f%%  (entries: %d)%n%n",
                tollgate.cacheStats().hitRate() * 100d, tollgate.cacheStats().entries());
    }

    private void routingScenario() {
        section("2. Routing - one prompt, three profiles");

        route(RoutingProfile.CHEAPEST, "summarise this support ticket in one line");
        route(RoutingProfile.BALANCED, "summarise this support ticket in one line");
        route(RoutingProfile.BEST_QUALITY, "summarise this support ticket in one line");

        System.out.println();
    }

    private void quotaScenario() {
        section("3. Quota - a tenant that runs out of budget");

        InMemoryQuotaStore quota = new InMemoryQuotaStore(
                new QuotaPolicy(400, Duration.ofDays(30), 0, QuotaAction.REJECT));

        Tollgate metered = Tollgate.builder()
                .provider(provider)
                .quota(quota, quota.policy())
                .build();

        for (int i = 1; i <= 4; i++) {
            try {
                LlmResponse response = metered.call(LlmRequest.builder()
                        .tenant("budget-team")
                        .user("question number " + i)
                        .maxTokens(100)
                        .cacheable(false)
                        .build());
                System.out.printf("  call %d -> ok    model=%s used=%d/%d tokens%n",
                        i, response.model(),
                        metered.quota("budget-team").usedTokens(),
                        metered.quota("budget-team").limitTokens());
            } catch (QuotaExceededException ex) {
                System.out.printf("  call %d -> REJECTED (%s)%n", i, ex.getMessage());
            }
        }
        System.out.println();
    }

    private void costReport() {
        section("4. Cost report");
        CostReport report = tollgate.report();
        for (String line : report.toText().split(System.lineSeparator())) {
            System.out.println("  " + line);
        }
        System.out.println();
    }

    private void ask(String tenant, String prompt) {
        LlmResponse response = tollgate.call(tenant, prompt);
        System.out.printf("  Q: %-24s -> hit=%-5s model=%-8s cost=$%.6f%n",
                prompt, response.cacheHit(), response.model(), response.costUsd());
    }

    private void route(RoutingProfile profile, String prompt) {
        LlmResponse response = tollgate.call(LlmRequest.builder()
                .tenant("acme")
                .user(prompt)
                .routingProfile(profile)
                .cacheable(false)
                .maxTokens(200)
                .build());
        System.out.printf("  %-13s -> model=%-9s cost=$%.6f%n",
                profile, response.model(), response.costUsd());
    }

    private void header(String title) {
        System.out.println(LINE);
        System.out.println("  " + title);
        System.out.println(LINE);
        System.out.println();
    }

    private void section(String title) {
        System.out.println(title);
        System.out.println("-".repeat(64));
    }

    private void footer() {
        System.out.println(LINE);
        System.out.println("  Next steps");
        System.out.println("    1. Replace SimulatedProvider with a real LlmProvider.");
        System.out.println("    2. Keep the defaults for a week and read the cost report.");
        System.out.println("    3. Then set a budget you can defend.");
        System.out.println(LINE);
    }
}

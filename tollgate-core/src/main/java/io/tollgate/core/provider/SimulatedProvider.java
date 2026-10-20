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
package io.tollgate.core.provider;

import io.tollgate.core.model.LlmRequest;
import io.tollgate.core.model.LlmResponse;
import io.tollgate.core.model.Message;
import io.tollgate.core.model.ModelSpec;
import io.tollgate.core.util.TokenEstimator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A deterministic, offline {@link LlmProvider} for examples, tests and local development.
 *
 * <p>It exists so that a newcomer can clone the repository and watch the entire governance
 * pipeline — caching, routing, quotas, metering — work end to end without an API key, an account,
 * or a network connection. Requiring a credit card before the first successful run is a reliable
 * way to lose half of your potential contributors.
 *
 * <p>Token counts are estimated with {@link TokenEstimator}, so the numbers are plausible rather
 * than exact. Answers are synthetic and generated from the prompt, which makes them stable across
 * runs and therefore usable in assertions.
 *
 * <p>This class is thread safe.
 */
public final class SimulatedProvider implements LlmProvider {

    private final String id;
    private final List<ModelSpec> models;
    private final boolean simulateLatency;

    /**
     * Creates a provider.
     *
     * @param id               the provider identifier
     * @param models           the models this provider serves
     * @param simulateLatency  whether to sleep for roughly the declared model latency
     */
    public SimulatedProvider(String id, List<ModelSpec> models, boolean simulateLatency) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.models = List.copyOf(models);
        this.simulateLatency = simulateLatency;
    }

    /**
     * Creates a provider that does not sleep.
     *
     * @param id     the provider identifier
     * @param models the models this provider serves
     */
    public SimulatedProvider(String id, List<ModelSpec> models) {
        this(id, models, false);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<ModelSpec> models() {
        return models;
    }

    @Override
    public LlmResponse complete(ModelSpec model, LlmRequest request) {
        long start = System.nanoTime();
        if (simulateLatency && model.expectedLatencyMillis() > 0) {
            long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, model.expectedLatencyMillis() / 4));
            try {
                Thread.sleep(Math.max(1, model.expectedLatencyMillis() / 20 + jitter));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        String prompt = collectPrompt(request);
        int promptTokens = request.estimatedPromptTokens();
        String answer = synthesize(model, request, prompt);
        int completionTokens = TokenEstimator.estimate(answer);

        long latencyMillis = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        return LlmResponse.fromProvider(answer, model.id(), promptTokens, completionTokens,
                latencyMillis, "stop");
    }

    private static String collectPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Message message : request.messages()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(message.content());
        }
        return sb.toString();
    }

    private static String synthesize(ModelSpec model, LlmRequest request, String prompt) {
        String focus = prompt.length() <= 40 ? prompt : prompt.substring(0, 40) + "…";
        String persona = switch (model.qualityTier()) {
            case 5 -> "以最高质量档作答";
            case 4 -> "以高质量档作答";
            case 3 -> "以均衡档作答";
            default -> "以经济档作答";
        };
        return "[" + model.id() + "] " + persona + "：关于「" + focus + "」，"
                + "这是 Tollgate 内置模拟器生成的占位回答。"
                + "接入真实 Provider（OpenAI、通义、DeepSeek、Ollama 等）后，此处将返回模型原始输出。"
                + "当前租户为 " + request.tenant() + "，采样温度 " + request.temperature() + "。";
    }
}

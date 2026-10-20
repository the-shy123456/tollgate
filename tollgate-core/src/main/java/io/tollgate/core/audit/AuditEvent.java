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
package io.tollgate.core.audit;

import java.util.Map;

/**
 * An immutable record of one governed call, suitable for compliance review.
 *
 * <h2>Privacy posture</h2>
 * <p>The prompt is never stored in plain text. {@link #promptDigest()} holds a SHA-256 digest
 * that can prove two calls asked the same thing without revealing what was asked. This matters
 * more than it sounds: an audit log that contains customer questions becomes a second copy of
 * your most sensitive data, subject to the same retention and deletion obligations as the
 * primary store, and usually with weaker controls.
 *
 * <p>Teams that genuinely need prompt retention for debugging should enable it explicitly and
 * scope it to a short retention window, rather than getting it by default.
 *
 * @param traceId          the correlation identifier
 * @param tenant           who made the call
 * @param model            which model served it
 * @param promptDigest     SHA-256 of the canonical prompt, or {@code null} when auditing is off
 * @param outcome          how the call ended
 * @param promptTokens     billed prompt tokens
 * @param completionTokens billed completion tokens
 * @param costUsd          cost incurred
 * @param latencyMillis    wall-clock duration
 * @param timestampMillis  when the call completed
 * @param tags             business labels carried over from the request
 */
public record AuditEvent(
        String traceId,
        String tenant,
        String model,
        String promptDigest,
        Outcome outcome,
        int promptTokens,
        int completionTokens,
        double costUsd,
        long latencyMillis,
        long timestampMillis,
        Map<String, String> tags) {

    /** How a governed call ended. */
    public enum Outcome {
        /** The model produced an answer. */
        SUCCESS,
        /** The answer came from the semantic cache. */
        CACHE_HIT,
        /** The call was rejected because the tenant had no budget left. */
        QUOTA_REJECTED,
        /** The provider failed and no fallback succeeded. */
        PROVIDER_ERROR
    }

    public AuditEvent {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}

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

import java.util.List;

/**
 * Records what the system did with each request.
 *
 * <p>Auditing is separated from metering on purpose, even though both observe the same call.
 * Metering answers "what did this cost" and is read by finance. Auditing answers "what did the
 * model say to whom, and under which policy" and is read by compliance. Conflating them produces
 * a data structure that satisfies neither: too detailed for billing, too coarse for an auditor.
 *
 * <p>Implementations must be thread safe. {@link #record} runs on the request thread and must not
 * block on I/O.
 */
public interface AuditLogger {

    /**
     * Records a completed call.
     *
     * @param event the audit event
     */
    void record(AuditEvent event);

    /**
     * Returns the most recent events, newest first.
     *
     * @param limit the maximum number of events to return
     * @return an immutable list, never {@code null}
     */
    List<AuditEvent> recent(int limit);

    /**
     * Returns a logger that discards everything.
     *
     * @return a no-op audit logger
     */
    static AuditLogger noop() {
        return new AuditLogger() {
            @Override
            public void record(AuditEvent event) {
            }

            @Override
            public List<AuditEvent> recent(int limit) {
                return List.of();
            }
        };
    }
}

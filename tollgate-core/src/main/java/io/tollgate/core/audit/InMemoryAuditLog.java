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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, in-memory {@link AuditLogger} that keeps the most recent events.
 *
 * <p>This is a development and single-node diagnostic tool, not a compliance archive. An audit
 * trail that evaporates on restart does not survive the audit it exists for. The class is
 * provided because the alternative — forcing every user to stand up a log pipeline before they
 * can try the library — is worse, and because a bounded ring is exactly what a test needs.
 *
 * <p>Production deployments should implement {@link AuditLogger} against their existing log
 * infrastructure, which already has retention, access control and tamper evidence.
 */
public final class InMemoryAuditLog implements AuditLogger {

    /** The default number of events retained. */
    public static final int DEFAULT_CAPACITY = 1_000;

    private final int capacity;
    private final Deque<AuditEvent> events = new ArrayDeque<>();

    /** Creates a log holding {@link #DEFAULT_CAPACITY} events. */
    public InMemoryAuditLog() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a log holding a fixed number of events.
     *
     * @param capacity the maximum number of retained events, must be positive
     */
    public InMemoryAuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive but was " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public void record(AuditEvent event) {
        if (event == null) {
            return;
        }
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > capacity) {
                events.removeLast();
            }
        }
    }

    @Override
    public List<AuditEvent> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (events) {
            List<AuditEvent> result = new ArrayList<>(Math.min(limit, events.size()));
            for (AuditEvent event : events) {
                if (result.size() >= limit) {
                    break;
                }
                result.add(event);
            }
            return List.copyOf(result);
        }
    }

    /** @return the number of events currently retained */
    public int size() {
        synchronized (events) {
            return events.size();
        }
    }
}

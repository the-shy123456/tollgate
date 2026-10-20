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
package io.tollgate.core.cache;

import java.util.Optional;

/**
 * A store of previously produced answers, addressable by question similarity.
 *
 * <p>Caches are always partitioned by tenant. This is a security boundary, not a performance
 * optimisation: two tenants asking the same question must never receive each other's answer,
 * because the answer may embed tenant-specific context from the system prompt or from data the
 * model had access to.
 *
 * <p>Implementations must be thread safe.
 */
public interface SemanticCache {

    /**
     * Looks up an answer for the given question.
     *
     * <p>Implementations try an exact match on the canonical question first and fall back to
     * vector similarity. Exact matching is both faster and safer, and it catches the majority of
     * production duplicates once stopwords have been stripped.
     *
     * <p>Similarity matching must be gated on the literal fingerprint. Two questions that differ
     * only in an order number are nearly identical as strings and completely different as
     * requests; serving one for the other discloses data.
     *
     * @param tenant             the owning tenant, never {@code null}
     * @param systemFingerprint  a fingerprint of the system prompt, used to keep instruction
     *                           variants apart
     * @param literalFingerprint the literal values present in the question
     * @param canonicalQuestion  the canonical question text
     * @param embedding          the question embedding
     * @return the matching entry, or empty when nothing sufficiently similar is cached
     */
    Optional<CacheEntry> lookup(String tenant, String systemFingerprint, String literalFingerprint,
                                String canonicalQuestion, float[] embedding);

    /**
     * Stores an answer.
     *
     * @param tenant the owning tenant, never {@code null}
     * @param entry  the entry to store
     */
    void put(String tenant, CacheEntry entry);

    /**
     * Returns cumulative statistics for this cache.
     *
     * @return an immutable snapshot, never {@code null}
     */
    CacheStats stats();

    /**
     * Drops every entry belonging to the given tenant.
     *
     * <p>Applications call this when tenant data is deleted, so that cached answers cannot
     * outlive the data they were derived from.
     *
     * @param tenant the tenant to purge
     */
    void invalidateTenant(String tenant);
}

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

import io.tollgate.core.model.LlmResponse;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single cached answer together with the metadata needed to decide whether it still applies.
 *
 * <p>Entries keep the original {@link LlmResponse} rather than a copy of its fields. That is what
 * makes the savings report honest: a hit can be priced exactly as the call it replaced, using the
 * model and token counts that were actually billed at the time, instead of being guessed from
 * current prices.
 */
public final class CacheEntry {

    private final String systemFingerprint;
    private final String literalFingerprint;
    private final String canonicalQuestion;
    private final float[] embedding;
    private final LlmResponse original;
    private final long storedAtMillis;

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicLong savedTokens = new AtomicLong();
    private final AtomicLong savedCostMicros = new AtomicLong();

    /**
     * Creates a cache entry with an unstamped storage time.
     *
     * <p>Callers never supply a timestamp or a TTL. The cache stamps the entry when it stores it
     * and applies its own policy when deciding freshness, which keeps the entry and the policy
     * from ever disagreeing — an entry built with a one-hour TTL cannot outlive a cache configured
     * for ten minutes, because the entry has no opinion on the matter.
     *
     * @param systemFingerprint a stable fingerprint of the system prompt this answer belongs to
     * @param literalFingerprint the literal values present in the question, see
     *                          {@link io.tollgate.core.util.LiteralGuard}
     * @param canonicalQuestion the canonical question that produced the answer
     * @param embedding         the question embedding, already normalised
     * @param original          the response being cached
     */
    public CacheEntry(String systemFingerprint, String literalFingerprint, String canonicalQuestion,
                      float[] embedding, LlmResponse original) {
        this(systemFingerprint, literalFingerprint, canonicalQuestion, embedding, original, 0L);
    }

    private CacheEntry(String systemFingerprint, String literalFingerprint, String canonicalQuestion,
                       float[] embedding, LlmResponse original, long storedAtMillis) {
        this.systemFingerprint = Objects.requireNonNull(systemFingerprint, "systemFingerprint");
        this.literalFingerprint = Objects.requireNonNull(literalFingerprint, "literalFingerprint");
        this.canonicalQuestion = Objects.requireNonNull(canonicalQuestion, "canonicalQuestion");
        this.embedding = Objects.requireNonNull(embedding, "embedding").clone();
        this.original = Objects.requireNonNull(original, "original");
        this.storedAtMillis = storedAtMillis;
    }

    /**
     * Returns a copy stamped with the given storage time.
     *
     * @param millis the storage time in epoch milliseconds
     * @return a new entry sharing the same payload
     */
    public CacheEntry withStoredAt(long millis) {
        return new CacheEntry(systemFingerprint, literalFingerprint, canonicalQuestion, embedding,
                original, millis);
    }

    /**
     * Returns the literal values present in the question.
     *
     * <p>Two questions may only be matched by similarity when their literal fingerprints are
     * equal. Without this guard, "订单 12345 的物流" and "订单 67890 的物流" would match, and the
     * second caller would receive the first caller's tracking status.
     *
     * @return the fingerprint, empty when the question has no digits
     */
    public String literalFingerprint() {
        return literalFingerprint;
    }

    /** @return the system prompt fingerprint this entry belongs to */
    public String systemFingerprint() {
        return systemFingerprint;
    }

    /** @return the canonical question this entry answers */
    public String canonicalQuestion() {
        return canonicalQuestion;
    }

    /** @return a defensive copy of the question embedding */
    public float[] embedding() {
        return embedding.clone();
    }

    /**
     * Returns the backing embedding array without copying.
     *
     * <p>Package private on purpose: the cache scans this array on every lookup and cloning it
     * would allocate on the hot path. Callers inside the package must treat it as read-only.
     *
     * @return the internal embedding array, never {@code null}
     */
    float[] embeddingRef() {
        return embedding;
    }

    /** @return the cached answer text */
    public String content() {
        return original.content();
    }

    /** @return the model that originally produced the answer */
    public String model() {
        return original.model();
    }

    /** @return the response that was cached */
    public LlmResponse original() {
        return original;
    }

    /** @return the time the entry was stored, in epoch milliseconds */
    public long storedAtMillis() {
        return storedAtMillis;
    }

    /** @return the token count of the original call */
    public int originalTotalTokens() {
        return original.totalTokens();
    }

    /** @return the cost of the original call in US dollars */
    public double originalCostUsd() {
        return original.costUsd();
    }

    /**
     * Reports whether the entry is still within its time to live.
     *
     * @param nowMillis the current time in epoch milliseconds
     * @param ttlMillis the cache policy's time to live; zero or negative means never expires
     * @return {@code true} if the entry may still be served
     */
    public boolean isFresh(long nowMillis, long ttlMillis) {
        return ttlMillis <= 0 || nowMillis - storedAtMillis < ttlMillis;
    }

    /**
     * Records a hit and accumulates what it saved.
     */
    void recordHit() {
        hits.incrementAndGet();
        savedTokens.addAndGet(original.totalTokens());
        savedCostMicros.addAndGet(Math.round(original.costUsd() * 1_000_000d));
    }

    /** @return the number of times this entry has been served */
    public int hits() {
        return hits.get();
    }

    /** @return the cumulative tokens this entry has saved */
    public long savedTokens() {
        return savedTokens.get();
    }

    /** @return the cumulative cost this entry has saved, in US dollars */
    public double savedCostUsd() {
        return savedCostMicros.get() / 1_000_000d;
    }
}

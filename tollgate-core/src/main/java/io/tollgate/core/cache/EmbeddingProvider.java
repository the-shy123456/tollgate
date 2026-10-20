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

/**
 * Turns text into a numeric vector whose cosine similarity approximates semantic similarity.
 *
 * <p>Tollgate ships with {@link HashingEmbeddingProvider}, a dependency-free approximation that
 * is good enough for development and for workloads where prompts are templated. Production
 * deployments that need true paraphrase matching should plug in a real embedding model; the
 * interface exists precisely so that swapping one in is a one-line change.
 *
 * <p>Implementations must be thread safe.
 */
public interface EmbeddingProvider {

    /**
     * Returns the length of the vectors produced by this provider.
     *
     * @return a positive dimension count
     */
    int dimensions();

    /**
     * Embeds an already canonicalised question.
     *
     * <p>Callers pass text produced by
     * {@link io.tollgate.core.util.QueryCanonicalizer#canonicalize(String)} so that every
     * provider sees the same input and no implementation has to repeat the normalisation work.
     *
     * @param canonicalQuestion the canonical form of the question, never {@code null}
     * @return a vector of length {@link #dimensions()}, never {@code null}
     */
    float[] embed(String canonicalQuestion);

    /**
     * Returns a human-readable name used in metrics and logs.
     *
     * @return the provider name
     */
    default String name() {
        return getClass().getSimpleName();
    }
}

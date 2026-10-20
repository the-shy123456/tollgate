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

import io.tollgate.core.util.QueryCanonicalizer;

import java.util.List;
import java.util.Objects;

/**
 * A dependency-free embedding provider based on signed feature hashing.
 *
 * <p>Every feature produced by {@link QueryCanonicalizer#features(String)} is hashed into one of
 * {@code dimensions} buckets, with the sign of the hash deciding whether it contributes
 * positively or negatively. Signing cancels most of the distortion introduced by collisions,
 * and L2 normalisation makes the dot product of two vectors equal to their cosine similarity.
 *
 * <h2>What this is and is not</h2>
 * <p>This is not a semantic model. It cannot know that "car" and "automobile" mean the same
 * thing. What it does do extremely well is recognise near-duplicate wording, which is the
 * dominant duplication pattern in production traffic: form letters, FAQ questions, ticket
 * templates and support macros. Combined with stopword stripping it also catches trivial
 * rephrasings such as "退款怎么弄" versus "如何申请退款".
 *
 * <p>If your workload involves genuine paraphrase, plug in a real embedding model through
 * {@link EmbeddingProvider}. The cache does not care where the vectors come from.
 *
 * <p>This class is immutable and thread safe.
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    /** The default vector size. Balances collision rate against memory per cached entry. */
    public static final int DEFAULT_DIMENSIONS = 256;

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private final int dimensions;

    /** Creates a provider with {@link #DEFAULT_DIMENSIONS}. */
    public HashingEmbeddingProvider() {
        this(DEFAULT_DIMENSIONS);
    }

    /**
     * Creates a provider with an explicit vector size.
     *
     * @param dimensions the number of buckets, must be positive
     */
    public HashingEmbeddingProvider(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive but was " + dimensions);
        }
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String canonicalQuestion) {
        Objects.requireNonNull(canonicalQuestion, "canonicalQuestion must not be null");
        float[] vector = new float[dimensions];
        List<String> features = QueryCanonicalizer.features(canonicalQuestion);
        if (features.isEmpty()) {
            return vector;
        }
        for (String feature : features) {
            int hash = fnv1a(feature);
            int bucket = (hash & 0x7fffffff) % dimensions;
            float sign = ((hash >>> 24) & 1) == 0 ? 1f : -1f;
            vector[bucket] += sign;
        }
        return l2Normalize(vector);
    }

    @Override
    public String name() {
        return "hashing-" + dimensions;
    }

    private static float[] l2Normalize(float[] vector) {
        double sumOfSquares = 0d;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares == 0d) {
            return vector;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }

    private static int fnv1a(String text) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}

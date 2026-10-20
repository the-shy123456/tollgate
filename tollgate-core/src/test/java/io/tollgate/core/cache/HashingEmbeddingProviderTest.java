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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HashingEmbeddingProviderTest {

    private final HashingEmbeddingProvider provider = new HashingEmbeddingProvider();

    @Test
    @DisplayName("produces vectors of the configured size")
    void producesConfiguredDimensions() {
        assertThat(provider.dimensions()).isEqualTo(HashingEmbeddingProvider.DEFAULT_DIMENSIONS);
        assertThat(provider.embed("refund").length).isEqualTo(HashingEmbeddingProvider.DEFAULT_DIMENSIONS);
        assertThat(new HashingEmbeddingProvider(64).embed("refund").length).isEqualTo(64);
    }

    @Test
    @DisplayName("is deterministic across calls")
    void isDeterministic() {
        float[] first = provider.embed("申请退款");
        float[] second = provider.embed("申请退款");

        assertThat(first).containsExactly(second);
    }

    @Test
    @DisplayName("gives identical text a similarity of 1")
    void identicalTextIsPerfectlySimilar() {
        float[] vector = provider.embed("申请退款");

        assertThat(cosine(vector, vector)).isCloseTo(1d, within(1e-5));
    }

    @Test
    @DisplayName("gives near-duplicate text a high similarity")
    void nearDuplicateTextIsHighlySimilar() {
        float[] base = provider.embed("申请退款");
        float[] variant = provider.embed("申请退款流程");

        assertThat(cosine(base, variant)).isGreaterThan(0.7);
    }

    @Test
    @DisplayName("gives unrelated text a low similarity")
    void unrelatedTextIsDissimilar() {
        float[] refund = provider.embed("申请退款");
        float[] password = provider.embed("重置登录密码");

        assertThat(cosine(refund, password)).isLessThan(0.3);
    }

    @Test
    @DisplayName("separates subtly different intents")
    void separatesSubtlyDifferentIntents() {
        float[] refund = provider.embed("申请退款");
        float[] exchange = provider.embed("申请换货");

        assertThat(cosine(refund, exchange)).isLessThan(0.75);
    }

    @Test
    @DisplayName("returns a zero vector for empty input")
    void returnsZeroVectorForEmptyInput() {
        float[] vector = provider.embed("");

        assertThat(vector).containsOnly(0f);
    }

    @Test
    @DisplayName("produces unit-length vectors")
    void producesUnitVectors() {
        float[] vector = provider.embed("how do i apply for a refund");

        double norm = 0d;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1d, within(1e-5));
    }

    @Test
    @DisplayName("reports a descriptive name")
    void reportsName() {
        assertThat(provider.name()).isEqualTo("hashing-" + HashingEmbeddingProvider.DEFAULT_DIMENSIONS);
    }

    @Test
    @DisplayName("canonicalisation brings rephrasings together before embedding")
    void canonicalisationBringsRephrasingsTogether() {
        float[] left = provider.embed(QueryCanonicalizer.canonicalize("如何申请退款"));
        float[] right = provider.embed(QueryCanonicalizer.canonicalize("请问我想申请退款呢"));

        assertThat(cosine(left, right)).isCloseTo(1d, within(1e-5));
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0d;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += (double) left[i] * right[i];
        }
        return dot;
    }
}

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
package io.tollgate.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCanonicalizerTest {

    @Test
    @DisplayName("collapses question-framing words so trivial rephrasings meet")
    void stripsQuestionFraming() {
        String canonical = QueryCanonicalizer.canonicalize("如何申请退款");

        assertThat(canonical).isEqualTo("申请退款");
        assertThat(QueryCanonicalizer.canonicalize("请问如何申请退款呢")).isEqualTo(canonical);
        assertThat(QueryCanonicalizer.canonicalize("我想申请退款")).isEqualTo(canonical);
        assertThat(QueryCanonicalizer.canonicalize("申请退款").equals(canonical)).isTrue();
    }

    @Test
    @DisplayName("keeps distinct business intents apart")
    void keepsDistinctIntentsApart() {
        assertThat(QueryCanonicalizer.canonicalize("退款"))
                .isNotEqualTo(QueryCanonicalizer.canonicalize("退货"));
        assertThat(QueryCanonicalizer.canonicalize("申请退款"))
                .isNotEqualTo(QueryCanonicalizer.canonicalize("申请换货"));
    }

    @Test
    @DisplayName("strips English stopwords")
    void stripsEnglishStopwords() {
        String canonical = QueryCanonicalizer.canonicalize("How do I apply for a refund?");
        assertThat(canonical).doesNotContain("how").doesNotContain("the").doesNotContain("a ");
        assertThat(canonical).contains("apply").contains("refund");
    }

    @Test
    @DisplayName("falls back to the normalised text when everything was a stopword")
    void fallsBackWhenEverythingIsAStopword() {
        assertThat(QueryCanonicalizer.canonicalize("请问")).isNotEmpty();
    }

    @Test
    @DisplayName("handles null and empty input")
    void handlesNullAndEmpty() {
        assertThat(QueryCanonicalizer.canonicalize(null)).isEmpty();
        assertThat(QueryCanonicalizer.canonicalize("")).isEmpty();
        assertThat(QueryCanonicalizer.canonicalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("produces unigrams and bigrams for CJK text")
    void producesCjkFeatures() {
        List<String> features = QueryCanonicalizer.features("退款");

        assertThat(features).contains("退", "款", "退款");
    }

    @Test
    @DisplayName("produces whole words and trigrams for Latin text")
    void producesLatinFeatures() {
        List<String> features = QueryCanonicalizer.features("refund");

        assertThat(features).contains("refund", "ref", "efu", "fun", "und");
    }

    @Test
    @DisplayName("returns an empty feature list for blank input")
    void returnsNoFeaturesForBlankInput() {
        assertThat(QueryCanonicalizer.features("")).isEmpty();
        assertThat(QueryCanonicalizer.features(null)).isEmpty();
    }

    @Test
    @DisplayName("never slices through a content word")
    void neverSlicesThroughContentWord() {
        assertThat(QueryCanonicalizer.canonicalize("申请退款")).isEqualTo("申请退款");
        assertThat(QueryCanonicalizer.canonicalize("请假流程")).isEqualTo("请假流程");
    }

    @Test
    @DisplayName("strips trailing particles")
    void stripsTrailingParticles() {
        assertThat(QueryCanonicalizer.canonicalize("退款吗")).isEqualTo("退款");
        assertThat(QueryCanonicalizer.canonicalize("退款流程呢")).isEqualTo("退款流程");
    }

    @Test
    @DisplayName("exposes the built-in filler sets for documentation")
    void exposesFillerSets() {
        assertThat(QueryCanonicalizer.leadingFillers()).contains("请问", "我想", "帮我");
        assertThat(QueryCanonicalizer.trailingParticles()).contains("吗", "呢", "吧");
    }
}

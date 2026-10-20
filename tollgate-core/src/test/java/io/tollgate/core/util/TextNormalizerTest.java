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

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {

    @Test
    @DisplayName("lower-cases and strips trailing punctuation")
    void normalizesCaseAndPunctuation() {
        assertThat(TextNormalizer.normalize("How to Refund?!")).isEqualTo("how to refund");
    }

    @Test
    @DisplayName("folds full-width characters into their ASCII equivalents")
    void foldsFullWidthCharacters() {
        assertThat(TextNormalizer.normalize("ＡＢＣ")).isEqualTo("abc");
        assertThat(TextNormalizer.normalize("１２３")).isEqualTo("123");
    }

    @Test
    @DisplayName("removes Chinese punctuation")
    void removesChinesePunctuation() {
        assertThat(TextNormalizer.normalize("申请退款。")).isEqualTo("申请退款");
        assertThat(TextNormalizer.normalize("退款，怎么办？")).isEqualTo("退款 怎么办");
    }

    @Test
    @DisplayName("collapses runs of whitespace into a single space")
    void collapsesWhitespace() {
        assertThat(TextNormalizer.normalize("a   b\n\tc")).isEqualTo("a b c");
    }

    @Test
    @DisplayName("returns an empty string for null and blank input")
    void handlesNullAndBlank() {
        assertThat(TextNormalizer.normalize(null)).isEmpty();
        assertThat(TextNormalizer.normalize("")).isEmpty();
        assertThat(TextNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("is idempotent")
    void isIdempotent() {
        String once = TextNormalizer.normalize("How to Refund?!");
        assertThat(TextNormalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("never produces leading or trailing separators")
    void neverLeavesDanglingSeparators() {
        assertThat(TextNormalizer.normalize("   hello   ")).isEqualTo("hello");
        assertThat(TextNormalizer.normalize("!!!hello!!!")).isEqualTo("hello");
    }
}

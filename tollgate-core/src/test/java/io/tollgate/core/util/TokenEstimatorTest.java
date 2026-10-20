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

class TokenEstimatorTest {

    @Test
    @DisplayName("estimates roughly one token per CJK character")
    void estimatesCjkText() {
        assertThat(TokenEstimator.estimate("申请退款")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("我想申请退款")).isEqualTo(6);
    }

    @Test
    @DisplayName("estimates roughly one token per four Latin characters")
    void estimatesLatinText() {
        assertThat(TokenEstimator.estimate("refund")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("abcdefgh")).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero for empty input")
    void returnsZeroForEmptyInput() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("   ")).isZero();
    }

    @Test
    @DisplayName("ignores whitespace when counting")
    void ignoresWhitespace() {
        assertThat(TokenEstimator.estimate("a b c d")).isEqualTo(1);
    }

    @Test
    @DisplayName("is monotonic in input length")
    void isMonotonic() {
        int shortText = TokenEstimator.estimate("退款");
        int longText = TokenEstimator.estimate("退款" + "退款退款退款退款");

        assertThat(longText).isGreaterThan(shortText);
    }
}

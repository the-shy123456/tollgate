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

class LiteralGuardTest {

    @Test
    @DisplayName("extracts a single identifier")
    void extractsSingleIdentifier() {
        assertThat(LiteralGuard.fingerprint("订单 12345 的物流到哪了")).isEqualTo("12345");
    }

    @Test
    @DisplayName("extracts several identifiers in order")
    void extractsSeveralIdentifiers() {
        assertThat(LiteralGuard.fingerprint("订单 12345 和 67890")).isEqualTo("12345,67890");
    }

    @Test
    @DisplayName("returns an empty fingerprint when there are no digits")
    void returnsEmptyWithoutDigits() {
        assertThat(LiteralGuard.fingerprint("申请退款")).isEmpty();
        assertThat(LiteralGuard.fingerprint("how do i apply for a refund")).isEmpty();
        assertThat(LiteralGuard.hasLiterals("申请退款")).isFalse();
    }

    @Test
    @DisplayName("distinguishes two identifiers that differ by one digit")
    void distinguishesNearIdenticalIdentifiers() {
        assertThat(LiteralGuard.fingerprint("订单 12345"))
                .isNotEqualTo(LiteralGuard.fingerprint("订单 12346"));
    }

    @Test
    @DisplayName("handles null and empty input")
    void handlesNullAndEmpty() {
        assertThat(LiteralGuard.fingerprint(null)).isEmpty();
        assertThat(LiteralGuard.fingerprint("")).isEmpty();
    }

    @Test
    @DisplayName("reports the presence of literals")
    void reportsPresence() {
        assertThat(LiteralGuard.hasLiterals("订单 12345")).isTrue();
        assertThat(LiteralGuard.hasLiterals("订单")).isFalse();
    }
}

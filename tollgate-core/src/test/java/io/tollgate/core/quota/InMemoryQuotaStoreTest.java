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
package io.tollgate.core.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryQuotaStoreTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    @DisplayName("grants reservations while budget remains")
    void grantsWithinBudget() {
        InMemoryQuotaStore store = store(1_000, Duration.ofMinutes(1), 0);

        assertThat(store.tryConsume("acme", 600)).isTrue();
        assertThat(store.tryConsume("acme", 400)).isTrue();
    }

    @Test
    @DisplayName("refuses a reservation that would exceed the budget")
    void refusesBeyondBudget() {
        InMemoryQuotaStore store = store(1_000, Duration.ofMinutes(1), 0);
        store.tryConsume("acme", 600);

        assertThat(store.tryConsume("acme", 500)).isFalse();
        assertThat(store.snapshot("acme").usedTokens()).isEqualTo(600);
    }

    @Test
    @DisplayName("counts refusals in the snapshot")
    void countsRefusals() {
        InMemoryQuotaStore store = store(100, Duration.ofMinutes(1), 0);
        store.tryConsume("acme", 100);
        store.tryConsume("acme", 1);
        store.tryConsume("acme", 1);

        assertThat(store.snapshot("acme").rejectedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("resets the budget when the window rolls over")
    void resetsOnWindowRollover() {
        InMemoryQuotaStore store = store(1_000, Duration.ofMinutes(1), 0);
        store.tryConsume("acme", 1_000);

        now.addAndGet(Duration.ofMinutes(2).toMillis());

        assertThat(store.tryConsume("acme", 1_000)).isTrue();
        assertThat(store.snapshot("acme").usedTokens()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("enforces a sustained rate limit independent of the budget")
    void enforcesRateLimit() {
        InMemoryQuotaStore store = store(1_000_000, Duration.ofDays(30), 100);

        assertThat(store.tryConsume("acme", 100)).isTrue();
        assertThat(store.tryConsume("acme", 1)).isFalse();

        now.addAndGet(1_000L);
        assertThat(store.tryConsume("acme", 100)).isTrue();
    }

    @Test
    @DisplayName("keeps tenants independent")
    void keepsTenantsIndependent() {
        InMemoryQuotaStore store = store(100, Duration.ofMinutes(1), 0);
        store.tryConsume("acme", 100);

        assertThat(store.tryConsume("globex", 100)).isTrue();
    }

    @Test
    @DisplayName("reports a full budget for a tenant that never called")
    void reportsFullBudgetForUnknownTenant() {
        InMemoryQuotaStore store = store(5_000, Duration.ofDays(1), 0);

        QuotaSnapshot snapshot = store.snapshot("newcomer");

        assertThat(snapshot.usedTokens()).isZero();
        assertThat(snapshot.remainingTokens()).isEqualTo(5_000);
        assertThat(snapshot.exhausted()).isFalse();
    }

    @Test
    @DisplayName("reports utilisation against the budget")
    void reportsUtilisation() {
        InMemoryQuotaStore store = store(1_000, Duration.ofMinutes(1), 0);
        store.tryConsume("acme", 250);

        assertThat(store.snapshot("acme").utilization()).isCloseTo(0.25d,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("clears a tenant on reset")
    void resetsTenant() {
        InMemoryQuotaStore store = store(100, Duration.ofMinutes(1), 0);
        store.tryConsume("acme", 100);

        store.reset("acme");

        assertThat(store.snapshot("acme").usedTokens()).isZero();
        assertThat(store.tryConsume("acme", 100)).isTrue();
    }

    @Test
    @DisplayName("rejects a non-positive reservation")
    void rejectsInvalidReservation() {
        InMemoryQuotaStore store = store(100, Duration.ofMinutes(1), 0);

        assertThatThrownBy(() -> store.tryConsume("acme", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private InMemoryQuotaStore store(long tokensPerWindow, Duration window, long rate) {
        QuotaPolicy policy = new QuotaPolicy(tokensPerWindow, window, rate, QuotaAction.REJECT);
        return new InMemoryQuotaStore(policy, now::get);
    }
}

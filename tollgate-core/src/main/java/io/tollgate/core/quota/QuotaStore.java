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

/**
 * Tracks and enforces per-tenant token budgets.
 *
 * <p>The contract is deliberately small. Tollgate reserves tokens <em>before</em> the model is
 * called, using an estimate, and never refunds afterwards. Over-reserving slightly is the
 * intended behaviour: a tenant that pays for a few tokens it did not use is a far smaller problem
 * than a tenant that quietly exceeds its budget because accounting ran after the fact.
 *
 * <p>Implementations must be thread safe.
 */
public interface QuotaStore {

    /**
     * Attempts to reserve tokens for a call.
     *
     * @param tenant the tenant making the call
     * @param tokens the number of tokens to reserve, must be positive
     * @return {@code true} if the reservation succeeded and budget was deducted
     */
    boolean tryConsume(String tenant, long tokens);

    /**
     * Returns the current budget state for a tenant.
     *
     * <p>Tenants that have never made a call report their full budget as available.
     *
     * @param tenant the tenant identifier
     * @return an immutable snapshot
     */
    QuotaSnapshot snapshot(String tenant);

    /**
     * Clears the recorded usage for a tenant, as if it had never called.
     *
     * <p>Intended for administrative resets and for tests. It is not a way to bypass a limit
     * under load; concurrent calls may still observe the pre-reset state.
     *
     * @param tenant the tenant to reset
     */
    void reset(String tenant);
}

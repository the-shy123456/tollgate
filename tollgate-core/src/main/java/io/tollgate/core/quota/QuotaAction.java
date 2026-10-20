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
 * What happens when a tenant runs out of budget.
 *
 * <p>The choice is genuinely operational rather than technical. Rejecting protects the budget at
 * the cost of a broken user experience; degrading keeps the feature alive at the cost of answer
 * quality. Most teams discover which one they actually want the first time a month-end budget
 * runs dry during a demo.
 */
public enum QuotaAction {

    /**
     * Reject the call and raise {@link io.tollgate.core.exception.QuotaExceededException}.
     *
     * <p>Appropriate for internal tooling and batch jobs, where a caller can retry tomorrow.
     */
    REJECT,

    /**
     * Down-route the call to the cheapest eligible model instead of failing.
     *
     * <p>The call still consumes budget, because a cheap model is not a free model. Degrading
     * buys time, it does not create money.
     */
    DEGRADE,

    /**
     * Allow the call and record the overrun.
     *
     * <p>Use this during the measurement phase, before a budget is enforced, to find out what
     * traffic actually costs without interrupting it.
     */
    LOG_ONLY
}

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
package io.tollgate.core.exception;

/**
 * Raised when a tenant has exhausted its token budget and the quota policy is set to reject.
 *
 * <p>Applications can catch this exception to return a friendly message, queue the work, or
 * switch to a cheaper path. When the policy is set to degrade instead of reject, this exception
 * is never thrown and the call is down-routed to a cheaper model.
 */
public class QuotaExceededException extends TollgateException {

    private static final long serialVersionUID = 1L;

    private final String tenant;
    private final long requestedTokens;

    /**
     * Creates the exception.
     *
     * @param tenant          the tenant whose budget is exhausted
     * @param requestedTokens the number of tokens the call would have consumed
     */
    public QuotaExceededException(String tenant, long requestedTokens) {
        super("Quota exhausted for tenant '" + tenant + "': requested " + requestedTokens + " tokens");
        this.tenant = tenant;
        this.requestedTokens = requestedTokens;
    }

    /** @return the tenant whose budget is exhausted */
    public String tenant() {
        return tenant;
    }

    /** @return the number of tokens the rejected call would have consumed */
    public long requestedTokens() {
        return requestedTokens;
    }
}

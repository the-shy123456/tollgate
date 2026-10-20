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
 * Raised when no registered model can serve a request.
 *
 * <p>The usual causes are a prompt larger than every context window, a pinned model that was
 * never registered, or a capability requirement no model declares.
 */
public class NoEligibleModelException extends TollgateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message describing why the candidate pool was empty
     */
    public NoEligibleModelException(String message) {
        super(message);
    }
}

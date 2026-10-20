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
package io.tollgate.core.model;

import java.util.Objects;

/**
 * A single message in a conversation.
 *
 * @param role    the speaker of this message
 * @param content the textual payload, never {@code null}
 */
public record Message(Role role, String content) {

    /** Conversation roles understood by Tollgate. */
    public enum Role {
        /** Instructions that frame the behaviour of the model. */
        SYSTEM,
        /** Input produced by the end user. */
        USER,
        /** Prior output produced by a model. */
        ASSISTANT
    }

    public Message {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    /**
     * Creates a {@link Role#SYSTEM} message.
     *
     * @param content the instruction text
     * @return an immutable message
     */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    /**
     * Creates a {@link Role#USER} message.
     *
     * @param content the user input
     * @return an immutable message
     */
    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    /**
     * Creates a {@link Role#ASSISTANT} message.
     *
     * @param content the prior model output
     * @return an immutable message
     */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content);
    }
}

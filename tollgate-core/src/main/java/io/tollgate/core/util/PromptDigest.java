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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces stable digests of prompt text.
 *
 * <p>A digest lets an auditor prove that two calls carried the same question without the log
 * holding the question itself. This is the difference between an audit trail that is safe to
 * retain and one that becomes a liability.
 */
public final class PromptDigest {

    private PromptDigest() {
    }

    /**
     * Returns the lowercase SHA-256 hex digest of the given text.
     *
     * @param text the text to digest, may be {@code null}
     * @return a 64-character hex string, or {@code null} when the input was {@code null}
     */
    public static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    /**
     * Returns a short prefix of the SHA-256 digest, for compact log lines.
     *
     * @param text the text to digest
     * @param length the number of hex characters to keep
     * @return a truncated digest, or {@code null} when the input was {@code null}
     */
    public static String shortSha256(String text, int length) {
        String full = sha256(text);
        if (full == null) {
            return null;
        }
        int safeLength = Math.max(0, Math.min(length, full.length()));
        return full.substring(0, safeLength);
    }
}

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

import java.lang.Character.UnicodeScript;

/**
 * Cheap, deterministic token estimation used for quota reservation <em>before</em> a model is
 * called.
 *
 * <p>Token counting is model specific, and asking a tokenizer for an exact number would mean
 * dragging a tokenizer dependency into a module that intentionally has none. Instead this
 * estimator uses a script-aware heuristic: CJK code points cost roughly one token each, other
 * code points cost a quarter of a token.
 *
 * <p>Estimates are intentionally biased <em>upwards</em> for the input side. Over-reserving and
 * then refunding the difference is safe; under-reserving lets traffic slip past a quota.
 * Responses are always metered with the provider-reported usage, so the estimate never
 * contaminates the final bill.
 *
 * <p>This class is stateless and thread safe.
 */
public final class TokenEstimator {

    /** Approximate per-message framing overhead applied by chat-style APIs. */
    public static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    /**
     * Estimates the number of tokens in the given text.
     *
     * @param text the text to estimate, may be {@code null}
     * @return a non-negative token estimate
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double cjk = 0;
        double other = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk += 1.0;
            } else if (!Character.isWhitespace(codePoint)) {
                other += 1.0;
            }
        }
        if (cjk == 0 && other == 0) {
            return 0;
        }
        return (int) Math.ceil(cjk + other / 4.0);
    }

    private static boolean isCjk(int codePoint) {
        UnicodeScript script = UnicodeScript.of(codePoint);
        return script == UnicodeScript.HAN
                || script == UnicodeScript.HIRAGANA
                || script == UnicodeScript.KATAKANA
                || script == UnicodeScript.HANGUL;
    }
}

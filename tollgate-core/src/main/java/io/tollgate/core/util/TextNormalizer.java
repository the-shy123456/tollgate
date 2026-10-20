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

import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonicalises prompt text so that trivially different spellings of the same question collapse
 * onto a single cache key.
 *
 * <p>Normalisation is deliberately conservative. It removes noise that carries no meaning
 * (letter case, full-width punctuation, redundant whitespace) but never rewrites words, because
 * rewriting words is the job of the embedding model, not of string processing.
 *
 * <p>This class is stateless and thread safe.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * Normalises the given text.
     *
     * <p>The pipeline is: Unicode NFKC (folds full-width characters), lower-casing, punctuation
     * removal, whitespace collapsing and trimming.
     *
     * @param text the raw text, may be {@code null}
     * @return the normalised text, never {@code null}; an empty string if the input was blank
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);

        StringBuilder out = new StringBuilder(folded.length());
        boolean pendingSpace = false;
        for (int i = 0; i < folded.length(); i++) {
            char ch = folded.charAt(i);
            if (Character.isWhitespace(ch)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (isPunctuation(ch)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(ch);
        }
        return out.toString().trim();
    }

    private static boolean isPunctuation(char ch) {
        if (Character.isLetterOrDigit(ch)) {
            return false;
        }
        int type = Character.getType(ch);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }
}

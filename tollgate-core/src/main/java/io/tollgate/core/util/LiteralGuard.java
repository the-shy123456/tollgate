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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protects literal values — order numbers, account ids, amounts, dates — from being blurred away
 * by semantic matching.
 *
 * <h2>The failure this prevents</h2>
 * <p>Consider two traffic lines from the same support inbox:
 *
 * <pre>
 *   订单 12345 的物流到哪了
 *   订单 67890 的物流到哪了
 * </pre>
 *
 * <p>Character-wise these strings are almost identical, so their cosine similarity is above
 * {@code 0.99} and a naive semantic cache will serve the second user the first user's tracking
 * status. The questions are semantically the same and the answers are completely different.
 * This is the single most dangerous failure mode of semantic caching, and it appears the moment
 * a workload contains identifiers, which is to say almost always.
 *
 * <h2>The mechanism</h2>
 * <p>Every digit run is extracted from the canonical text and joined into a fingerprint. Two
 * questions may only be matched by similarity when their fingerprints are equal. Questions with
 * no digits share the empty fingerprint and remain freely matchable, so the guard costs nothing
 * for the FAQ-style traffic that benefits most from caching.
 *
 * <p>The guard is deliberately strict: it does not try to understand that {@code 12345} and
 * {@code 12346} are "probably different orders". It treats every distinct digit sequence as
 * distinct, because being wrong here means leaking data, not just wasting money.
 */
public final class LiteralGuard {

    private static final Pattern DIGIT_RUNS = Pattern.compile("\\d+");

    private LiteralGuard() {
    }

    /**
     * Extracts the literal fingerprint of a canonical text.
     *
     * @param canonicalText the canonical text, may be {@code null}
     * @return a comma-joined list of digit runs, or an empty string when the text has none
     */
    public static String fingerprint(String canonicalText) {
        if (canonicalText == null || canonicalText.isEmpty()) {
            return "";
        }
        Matcher matcher = DIGIT_RUNS.matcher(canonicalText);
        List<String> runs = new ArrayList<>();
        while (matcher.find()) {
            runs.add(matcher.group());
        }
        return String.join(",", runs);
    }

    /**
     * Reports whether a text carries literal values.
     *
     * @param canonicalText the canonical text
     * @return {@code true} if at least one digit run is present
     */
    public static boolean hasLiterals(String canonicalText) {
        return !fingerprint(canonicalText).isEmpty();
    }
}

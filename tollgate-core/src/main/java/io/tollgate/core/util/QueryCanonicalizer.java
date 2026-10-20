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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reduces a prompt to the part that actually carries its meaning.
 *
 * <p>This is the first layer of cache matching, and in practice the most effective one. Two
 * questions such as {@code "退款怎么弄"} and {@code "如何申请退款"} share their subject but very
 * little surface form; no amount of vector similarity on characters will reliably connect them.
 * Stripping question framing collapses them onto the same canonical core and they match exactly.
 *
 * <h2>Why stripping is position-aware</h2>
 * <p>Chinese has no spaces, so naive substring removal is actively dangerous: removing the
 * single character {@code 请} turns {@code 申请} (apply) into {@code 申}, and removing {@code 的}
 * damages any noun phrase that contains it. Deleting a character is not the same thing as
 * deleting a word.
 *
 * <p>Instead, framing is removed only where framing actually occurs: at the start of the
 * sentence (where 请问, 我想, 帮我 live), at the end (where 吗, 呢, 吧 live), and for a small set
 * of multi-character question words that are unambiguous wherever they appear (如何, 为什么,
 * 是什么). Single characters are never removed from the middle of a string.
 *
 * <h2>What is preserved</h2>
 * <p>Content words are never touched, so {@code 退款} and {@code 退货} stay distinct. A cache
 * that confuses refunds with returns is worse than no cache at all.
 *
 * <p>This class is stateless and thread safe.
 */
public final class QueryCanonicalizer {

    /** Phrases that open a question rather than carry its subject. */
    private static final Set<String> LEADING_FILLERS = Set.of(
            "请问", "麻烦", "帮我", "我要", "我想问", "我想", "想问", "想知道",
            "能否", "是否", "可以", "应该", "需要", "你好", "谢谢", "一下");

    /** Particles that close a question. */
    private static final Set<String> TRAILING_PARTICLES = Set.of(
            "吗", "呢", "啊", "吧", "呀", "哦", "嘛");

    /** Multi-character question words that are safe to remove anywhere. */
    private static final Set<String> QUESTION_WORDS = Set.of(
            "为什么", "是什么", "有哪些", "怎么办", "怎么样", "什么时候",
            "如何", "怎么", "怎样", "什么", "哪些", "哪个", "哪里", "多少");

    private static final Set<String> ENGLISH_STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "do", "does", "did", "how", "what", "why", "when", "where", "which",
            "who", "to", "of", "for", "in", "on", "at", "by", "with", "and", "or",
            "i", "we", "you", "it", "this", "that", "can", "could", "should", "would",
            "please", "me", "my", "your", "tell", "want", "need", "there");

    private static final List<String> SORTED_LEADING = sortByLengthDescending(LEADING_FILLERS);
    private static final List<String> SORTED_QUESTION_WORDS = sortByLengthDescending(QUESTION_WORDS);

    private QueryCanonicalizer() {
    }

    /**
     * Canonicalises a prompt into its meaning-bearing core.
     *
     * @param text the raw prompt, may be {@code null}
     * @return the canonical form, never {@code null}; empty when the prompt carried no content
     */
    public static String canonicalize(String text) {
        String normalized = TextNormalizer.normalize(text);
        if (normalized.isEmpty()) {
            return "";
        }
        String stripped = stripLeadingFiller(normalized);
        stripped = stripTrailingParticles(stripped);
        stripped = stripQuestionWords(stripped);
        stripped = stripEnglishStopwords(stripped);
        stripped = stripped.replaceAll("\\s{2,}", " ").trim();
        return stripped.isBlank() ? normalized : stripped;
    }

    private static String stripLeadingFiller(String text) {
        String current = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : SORTED_LEADING) {
                if (current.startsWith(filler) && current.length() > filler.length()) {
                    current = current.substring(filler.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return current;
    }

    private static String stripTrailingParticles(String text) {
        String current = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String particle : TRAILING_PARTICLES) {
                if (current.endsWith(particle) && current.length() > particle.length()) {
                    current = current.substring(0, current.length() - particle.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return current;
    }

    private static String stripQuestionWords(String text) {
        String current = text;
        for (String word : SORTED_QUESTION_WORDS) {
            if (current.contains(word) && current.length() > word.length()) {
                current = current.replace(word, "");
            }
        }
        return current.trim();
    }

    private static String stripEnglishStopwords(String text) {
        String[] tokens = text.split(" ");
        if (tokens.length <= 1) {
            return text;
        }
        List<String> kept = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (!token.isEmpty() && !ENGLISH_STOPWORDS.contains(token.toLowerCase(Locale.ROOT))) {
                kept.add(token);
            }
        }
        return kept.isEmpty() ? text : String.join(" ", kept);
    }

    private static List<String> sortByLengthDescending(Set<String> words) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(words));
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    /**
     * Splits canonical text into the features used for similarity.
     *
     * <p>CJK text yields character unigrams and bigrams, because Chinese has no whitespace
     * delimiters and bigrams approximate words reasonably well. Latin text yields whole words
     * plus character trigrams, which makes the representation tolerant of small spelling
     * variations.
     *
     * @param canonical the output of {@link #canonicalize(String)}
     * @return the feature list, possibly empty
     */
    public static List<String> features(String canonical) {
        List<String> features = new ArrayList<>();
        if (canonical == null || canonical.isEmpty()) {
            return features;
        }
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < canonical.length(); i++) {
            char ch = canonical.charAt(i);
            if (isCjk(ch)) {
                flushLatin(latin, features);
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushCjk(cjk, features);
                latin.append(ch);
            } else {
                flushLatin(latin, features);
                flushCjk(cjk, features);
            }
        }
        flushLatin(latin, features);
        flushCjk(cjk, features);
        return features;
    }

    private static void flushCjk(StringBuilder cjk, List<String> features) {
        if (cjk.length() == 0) {
            return;
        }
        String text = cjk.toString();
        for (int i = 0; i < text.length(); i++) {
            features.add(String.valueOf(text.charAt(i)));
            if (i + 1 < text.length()) {
                features.add(text.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }

    private static void flushLatin(StringBuilder latin, List<String> features) {
        if (latin.length() == 0) {
            return;
        }
        String word = latin.toString();
        features.add(word);
        if (word.length() >= 3) {
            for (int i = 0; i + 3 <= word.length(); i++) {
                features.add(word.substring(i, i + 3));
            }
        }
        latin.setLength(0);
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(ch);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * Returns every built-in leading filler phrase, for documentation and testing.
     *
     * @return an immutable view of the filler set
     */
    public static Set<String> leadingFillers() {
        return Set.copyOf(LEADING_FILLERS);
    }

    /**
     * Returns every built-in trailing particle, for documentation and testing.
     *
     * @return an immutable view of the particle set
     */
    public static Set<String> trailingParticles() {
        return Set.copyOf(TRAILING_PARTICLES);
    }
}

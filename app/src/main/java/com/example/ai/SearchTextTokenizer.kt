package com.example.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Locale-neutral search preprocessing that preserves Devanagari letters and marks.
 * Punctuation and format characters become token boundaries; Unicode compatibility
 * forms are normalized so Hindi and mixed-script queries behave consistently.
 */
object SearchTextTokenizer {
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('\u200c', ' ')
            .replace('\u200d', ' ')
        val builder = StringBuilder(normalized.length)
        for (codePoint in normalized.codePoints().toArray()) {
            val type = Character.getType(codePoint)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (Character.isLetterOrDigit(codePoint) || codePoint == '_'.code || isMark) {
                builder.appendCodePoint(codePoint)
            } else {
                builder.append(' ')
            }
        }
        return builder.toString().trim().replace(Regex("\\s+"), " ")
    }

    fun tokenize(text: String): List<String> = normalize(text)
        .split(' ')
        .filter(String::isNotBlank)

    fun containsQuery(text: String, query: String): Boolean {
        val normalizedQuery = normalize(query)
        return normalizedQuery.isNotBlank() && normalize(text).contains(normalizedQuery)
    }
}

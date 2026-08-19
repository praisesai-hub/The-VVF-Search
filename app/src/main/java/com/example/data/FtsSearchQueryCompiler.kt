package com.example.data

/**
 * Converts free-form user input into a bounded FTS5 prefix query. Every term is a quoted FTS
 * phrase, so FTS operators in user input cannot alter the query grammar.
 */
object FtsSearchQueryCompiler {
    private const val MAX_QUERY_CHARACTERS = 256
    private const val MAX_TERMS = 8

    fun toPrefixQuery(userInput: String): String {
        val terms = userInput
            .trim()
            .take(MAX_QUERY_CHARACTERS)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(MAX_TERMS)

        return terms.joinToString(" AND ") { term ->
            "\"${term.replace("\"", "\"\"")}\"*"
        }
    }
}

package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FtsSearchQueryCompilerTest {
    @Test
    fun toPrefixQuery_preservesHindiTermsForFtsTokenizer() {
        assertEquals(
            "\"बिजली\"* AND \"का\"* AND \"बिल\"*",
            FtsSearchQueryCompiler.toPrefixQuery("  बिजली का बिल  ")
        )
    }

    @Test
    fun toPrefixQuery_quotesOperatorsAndEmbeddedQuotes() {
        assertEquals(
            "\"OR\"* AND \"budget\"* AND \"\"\"quoted\"\"\"*",
            FtsSearchQueryCompiler.toPrefixQuery("OR budget \"quoted\"")
        )
    }

    @Test
    fun toPrefixQuery_limitsTermsAndReturnsBlankForWhitespace() {
        val query = FtsSearchQueryCompiler.toPrefixQuery(
            "one two three four five six seven eight nine"
        )

        assertEquals(8, query.split(" AND ").size)
        assertFalse(FtsSearchQueryCompiler.toPrefixQuery("   ").isNotBlank())
    }
}

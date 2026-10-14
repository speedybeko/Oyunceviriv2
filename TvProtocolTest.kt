package com.berkay.oyunceviri.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TvProtocolTest {

    @Test
    fun subtitleMessageEscapesSpecialCharacters() {
        val json = TvProtocol.subtitle("Ona \"merhaba\" de\nYeni satır \\ ve ş ğ İ")
        assertEquals("""{"type":"sub","text":"Ona \"merhaba\" de\nYeni satır \\ ve ş ğ İ"}""", json)
    }

    @Test
    fun quoteEscapesControlCharacters() {
        assertEquals("\"a\\u0001b\"", TvProtocol.quote("a\u0001b"))
        assertEquals("\"tab\\there\"", TvProtocol.quote("tab\there"))
    }

    @Test
    fun clearAndStyleMessages() {
        assertEquals("""{"type":"clear"}""", TvProtocol.clear())
        assertEquals("""{"type":"style","size":28,"bg":70,"shadow":true}""", TvProtocol.style(28, 70, true))
    }

    @Test
    fun helloIsRecognized() {
        assertTrue(TvProtocol.isHello(TvProtocol.hello()))
        assertFalse(TvProtocol.isHello(TvProtocol.clear()))
        assertFalse(TvProtocol.isHello(TvProtocol.subtitle("hello")))
    }

    @Test
    fun tokenIsReadFromResourceDescriptor() {
        assertEquals("ABCD2345", TvProtocol.tokenFromResource("/?token=ABCD2345"))
        assertEquals("ABCD2345", TvProtocol.tokenFromResource("/?x=1&token=ABCD2345&y=2"))
        assertEquals("AB CD", TvProtocol.tokenFromResource("/?token=AB%20CD"))
    }

    @Test
    fun missingTokenIsNull() {
        assertNull(TvProtocol.tokenFromResource(null))
        assertNull(TvProtocol.tokenFromResource("/"))
        assertNull(TvProtocol.tokenFromResource("/?other=1"))
        assertNull(TvProtocol.tokenFromResource("/?token=%ZZ"))
    }
}

class TokenAuthTest {

    @Test
    fun generatedTokenHasExpectedShape() {
        val t = TokenAuth.generate()
        assertEquals(TokenAuth.LENGTH, t.length)
        assertEquals(t, TokenAuth.normalize(t)) // yalnızca izinli karakterler
    }

    @Test
    fun generationIsDeterministicForAGivenRandom() {
        assertEquals(TokenAuth.generate(Random(42)), TokenAuth.generate(Random(42)))
    }

    @Test
    fun differentRandomsGiveDifferentTokens() {
        assertFalse(TokenAuth.generate(Random(1)) == TokenAuth.generate(Random(2)))
    }

    @Test
    fun normalizeIgnoresCaseSpacesAndDashes() {
        assertEquals("ABCD2345", TokenAuth.normalize(" abcd-2345 "))
    }

    @Test
    fun matchesAcceptsCorrectCodeInAnyFormatting() {
        assertTrue(TokenAuth.matches("ABCD2345", "abcd-2345"))
    }

    @Test
    fun matchesRejectsWrongMissingOrEmpty() {
        assertFalse(TokenAuth.matches("ABCD2345", "ABCD2346"))
        assertFalse(TokenAuth.matches("ABCD2345", null))
        assertFalse(TokenAuth.matches("ABCD2345", ""))
        assertFalse(TokenAuth.matches("", ""))
    }
}

class CompositeSinkTest {

    private class Recording(private val boom: Boolean = false) : SubtitleSink {
        val shown = mutableListOf<String>()
        var cleared = 0

        override fun show(text: String) {
            if (boom) throw IllegalStateException("hata")
            shown += text
        }

        override fun clear() {
            if (boom) throw IllegalStateException("hata")
            cleared++
        }
    }

    @Test
    fun forwardsToEverySink() {
        val a = Recording()
        val b = Recording()
        val c = CompositeSink(listOf(a, b))
        c.show("merhaba")
        c.clear()
        assertEquals(listOf("merhaba"), a.shown)
        assertEquals(listOf("merhaba"), b.shown)
        assertEquals(1, a.cleared)
        assertEquals(1, b.cleared)
    }

    @Test
    fun oneFailingSinkDoesNotStopTheOthers() {
        val bad = Recording(boom = true)
        val good = Recording()
        var errors = 0
        val c = CompositeSink(listOf(bad, good), onError = { _, _ -> errors++ })
        c.show("x")
        assertEquals(listOf("x"), good.shown)
        assertEquals(1, errors)
    }
}

package com.berkay.oyunceviri.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranslatorManagerTest {

    private class FakeTranslator(
        private val name: String,
        private val failure: () -> TranslationException? = { null }
    ) : SubtitleTranslator {
        var calls = 0
        var closed = false

        override suspend fun translate(text: String): String {
            calls++
            failure()?.let { throw it }
            return "$name:$text"
        }

        override fun close() {
            closed = true
        }
    }

    private var now = 0L
    private val notices = mutableListOf<String>()

    private fun manager(primary: SubtitleTranslator?, fallback: SubtitleTranslator) =
        TranslatorManager(primary, fallback, clock = { now }, rateLimitPauseMs = 60_000L, onNotice = { notices += it })

    @Test
    fun usesPrimaryWhenItWorks() {
        val primary = FakeTranslator("gemini")
        val fallback = FakeTranslator("offline")
        val m = manager(primary, fallback)

        assertEquals("gemini:hi", runSuspend { m.translate("hi") })
        assertEquals(0, fallback.calls)
    }

    @Test
    fun usesFallbackWhenThereIsNoPrimary() {
        val fallback = FakeTranslator("offline")
        val m = manager(null, fallback)

        assertEquals("offline:hi", runSuspend { m.translate("hi") })
    }

    @Test
    fun networkErrorFallsBackForThatLineOnly() {
        var failNext = true
        val primary = FakeTranslator("gemini") { if (failNext) TranslationException(0, "ağ") else null }
        val fallback = FakeTranslator("offline")
        val m = manager(primary, fallback)

        assertEquals("offline:a", runSuspend { m.translate("a") })
        failNext = false
        assertEquals("gemini:b", runSuspend { m.translate("b") })
        assertTrue(notices.isEmpty())
    }

    @Test
    fun serverErrorFallsBackWithoutDisablingPrimary() {
        var fail = true
        val primary = FakeTranslator("gemini") { if (fail) TranslationException(503, "meşgul") else null }
        val m = manager(primary, FakeTranslator("offline"))

        assertEquals("offline:a", runSuspend { m.translate("a") })
        fail = false
        assertEquals("gemini:b", runSuspend { m.translate("b") })
    }

    @Test
    fun rateLimitPausesPrimaryForAMinute() {
        var limited = true
        val primary = FakeTranslator("gemini") { if (limited) TranslationException(429, "limit") else null }
        val fallback = FakeTranslator("offline")
        val m = manager(primary, fallback)

        assertEquals("offline:a", runSuspend { m.translate("a") })
        assertEquals(1, notices.size)

        limited = false
        now = 30_000
        assertEquals("offline:b", runSuspend { m.translate("b") }) // hâlâ duraklatıldı
        assertEquals(1, primary.calls)                              // birincil bu sürede çağrılmadı

        now = 60_001
        assertEquals("gemini:c", runSuspend { m.translate("c") })   // süre doldu
    }

    @Test
    fun clientErrorDisablesPrimaryAndClosesIt() {
        val primary = FakeTranslator("gemini") { TranslationException(403, "anahtar geçersiz") }
        val fallback = FakeTranslator("offline")
        val m = manager(primary, fallback)

        assertEquals("offline:a", runSuspend { m.translate("a") })
        assertTrue(primary.closed)
        assertEquals(1, notices.size)

        assertEquals("offline:b", runSuspend { m.translate("b") })
        assertEquals(1, primary.calls) // bir daha denenmedi
    }

    @Test
    fun fallbackFailurePropagates() {
        val fallback = FakeTranslator("offline") { TranslationException(0, "model indirilemedi") }
        val m = manager(null, fallback)

        try {
            runSuspend { m.translate("a") }
            fail("TranslationException bekleniyordu")
        } catch (e: TranslationException) {
            assertEquals("model indirilemedi", e.message)
        }
    }

    @Test
    fun closeClosesBothEngines() {
        val primary = FakeTranslator("gemini")
        val fallback = FakeTranslator("offline")
        manager(primary, fallback).close()

        assertTrue(primary.closed)
        assertTrue(fallback.closed)
    }
}

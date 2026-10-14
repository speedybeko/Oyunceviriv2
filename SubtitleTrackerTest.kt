package com.berkay.oyunceviri.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTrackerTest {

    private val line1 = "You're talking nonsense, man. You raised a fine daughter."
    private val line2 = "Thank you, both of you."

    private fun tracker() = SubtitleTracker(stableFramesRequired = 2, clearDelayMs = 500)

    @Test
    fun showsOnlyAfterTwoStableFrames() {
        val t = tracker()
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 0))
        assertEquals(SubtitleEvent.Show(line1), t.onOcr(line1, 250))
    }

    @Test
    fun singleFrameNoiseIsIgnored() {
        val t = tracker()
        assertEquals(SubtitleEvent.None, t.onOcr("Garbage text here", 0))
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 250))
        // Gürültü satırı kararlı olmadığı için hâlâ gösterilmedi; satır 2 kareyle kabul edilir
        assertEquals(SubtitleEvent.Show(line1), t.onOcr(line1, 500))
    }

    @Test
    fun sameLineIsNotShownTwice() {
        val t = tracker()
        t.onOcr(line1, 0)
        assertEquals(SubtitleEvent.Show(line1), t.onOcr(line1, 250))
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 500))
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 750))
    }

    @Test
    fun ocrJitterOnShownLineIsNotANewLine() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        val jittered = line1.replace("daughter", "daughler")
        assertEquals(SubtitleEvent.None, t.onOcr(jittered, 500))
        assertEquals(SubtitleEvent.None, t.onOcr(jittered, 750))
    }

    @Test
    fun differentLineReplacesTheOldOneAfterTwoFrames() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        assertEquals(SubtitleEvent.None, t.onOcr(line2, 500))
        assertEquals(SubtitleEvent.Show(line2), t.onOcr(line2, 750))
    }

    @Test
    fun clearsOnlyAfterTheDelay() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        assertEquals(SubtitleEvent.None, t.onOcr("", 500))   // boşluk başladı
        assertEquals(SubtitleEvent.None, t.onOcr("", 749))   // 249 ms: henüz erken
        assertEquals(SubtitleEvent.Clear, t.onOcr("", 1000)) // 500 ms doldu
    }

    @Test
    fun brieflyMissingSubtitleDoesNotFlicker() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        assertEquals(SubtitleEvent.None, t.onOcr("", 500))
        // 250 ms sonra aynı satır geri geldi: temizleme yok, yeniden gösterme de yok
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 750))
        assertEquals(SubtitleEvent.None, t.onOcr("", 1000))
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 1250))
    }

    @Test
    fun noClearEventWhenNothingWasShown() {
        val t = tracker()
        assertEquals(SubtitleEvent.None, t.onOcr("", 0))
        assertEquals(SubtitleEvent.None, t.onOcr("", 600))
        assertEquals(SubtitleEvent.None, t.onOcr("", 1200))
    }

    @Test
    fun clearIsReportedOnlyOnce() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        t.onOcr("", 500)
        assertEquals(SubtitleEvent.Clear, t.onOcr("", 1000))
        assertEquals(SubtitleEvent.None, t.onOcr("", 1250))
        assertEquals(SubtitleEvent.None, t.onOcr("", 2000))
    }

    @Test
    fun sameLineCanBeShownAgainAfterAClear() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        t.onOcr("", 500)
        t.onOcr("", 1000) // Clear
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 1250))
        assertEquals(SubtitleEvent.Show(line1), t.onOcr(line1, 1500))
    }

    @Test
    fun symbolNoiseCountsAsEmpty() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        assertEquals(SubtitleEvent.None, t.onOcr("|| -- //", 500))
        assertEquals(SubtitleEvent.Clear, t.onOcr("|| -- //", 1000))
    }

    @Test
    fun resetForgetsEverything() {
        val t = tracker()
        t.onOcr(line1, 0)
        t.onOcr(line1, 250)
        t.reset()
        assertEquals(SubtitleEvent.None, t.onOcr(line1, 500))
        assertEquals(SubtitleEvent.Show(line1), t.onOcr(line1, 750))
    }
}

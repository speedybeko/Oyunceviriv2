package com.berkay.oyunceviri.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextTest {

    @Test
    fun normalize_collapsesWhitespaceAndNewlines() {
        assertEquals("You're talking nonsense, man.", SubtitleText.normalize("  You're   talking\nnonsense,\t man.  "))
    }

    @Test
    fun looksLikeSubtitle_acceptsNormalSentence() {
        assertTrue(SubtitleText.looksLikeSubtitle("Thank you, both of you."))
    }

    @Test
    fun looksLikeSubtitle_rejectsEmptyAndTooShort() {
        assertFalse(SubtitleText.looksLikeSubtitle(""))
        assertFalse(SubtitleText.looksLikeSubtitle("ab"))
    }

    @Test
    fun looksLikeSubtitle_rejectsSymbolNoise() {
        assertFalse(SubtitleText.looksLikeSubtitle("|| -- // 12 34 ..."))
        assertFalse(SubtitleText.looksLikeSubtitle("a ... --- ### 1 2 3"))
    }

    @Test
    fun key_ignoresCaseSpacesAndPunctuation() {
        assertEquals("worrynot", SubtitleText.key("Worry, NOT!"))
    }

    @Test
    fun similar_treatsSingleCharacterOcrJitterAsSameLine() {
        val a = SubtitleText.key("They're en route. Kitana and I will see that they arrive as planned.")
        val b = SubtitleText.key("They're en route. Kitana and I will see that they arrlve as planned.")
        assertTrue(SubtitleText.similar(a, b))
    }

    @Test
    fun similar_distinguishesDifferentLines() {
        val a = SubtitleText.key("Thank you, both of you.")
        val b = SubtitleText.key("Understood, Jackson Briggs.")
        assertFalse(SubtitleText.similar(a, b))
    }

    @Test
    fun similar_shortLinesNeedExactMatchWithinOneEdit() {
        assertTrue(SubtitleText.similar("worrynot", "worrynot"))
        assertFalse(SubtitleText.similar("worrynot", "wearenot"))
    }

    @Test
    fun editDistance_classicExample() {
        assertEquals(3, SubtitleText.editDistance("kitten", "sitting"))
        assertEquals(0, SubtitleText.editDistance("abc", "abc"))
        assertEquals(3, SubtitleText.editDistance("", "abc"))
    }
}

package com.jadegenesis.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextSearchTest {
    @Test
    fun normalizesAccentsAndIgnoresCommonQuestionWords() {
        val terms = MemoryContextSearch.terms("Rappelle-moi comment importer le modèle Roblox dans Blender")
        assertTrue("roblox" in terms)
        assertTrue("blender" in terms)
        assertTrue("rappelle" !in terms)
    }

    @Test
    fun matchesWholeWordsNotSubstrings() {
        assertEquals(1, MemoryContextSearch.matchingTerms(
            "Une scene Roblox reelle", listOf("roblox", "roblo")
        ))
    }

    @Test
    fun emptyQueryDoesNotCreateMatches() {
        assertTrue(MemoryContextSearch.terms("oui").isEmpty())
        assertEquals(0, MemoryContextSearch.matchingTerms("Blender", emptyList()))
    }

    @Test
    fun boundsNumberOfSearchQueries() {
        val terms = MemoryContextSearch.terms(
            "Roblox Blender Godot Python Kotlin Luau Java textures geometrie import export"
        )
        assertTrue(terms.size <= 5)
    }
}

package com.aistudyscanner.agent

import com.aistudyscanner.agent.tts.stripForSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Listen button actually sends to the speech engine.
 *
 * The answers are Markdown with embedded LaTeX, and a TTS engine reads every
 * backslash and brace out loud, so this is the difference between a lesson and
 * a minute of noise.
 */
class SpeechTextTest {

    @Test
    fun `drops markdown emphasis and headings`() {
        val spoken = stripForSpeech("**Concept** — this is a *quadratic*")
        assertFalse(spoken.contains("*"))
        assertTrue(spoken.contains("Concept"))
        assertTrue(spoken.contains("quadratic"))
    }

    @Test
    fun `replaces inline latex rather than reading it`() {
        val spoken = stripForSpeech("""Substitute \(x = 2\) into the equation.""")
        assertFalse(spoken.contains("\\"))
        assertFalse(spoken.contains("("))
        assertTrue(spoken.startsWith("Substitute"))
        assertTrue(spoken.endsWith("into the equation."))
    }

    @Test
    fun `replaces display latex blocks`() {
        val spoken = stripForSpeech(
            """
            First rearrange:
            \[
            x^{2} - 5x + 6 = 0
            \]
            Then factorise.
            """.trimIndent()
        )
        assertFalse(spoken.contains("x^{2}"))
        assertTrue(spoken.contains("First rearrange:"))
        assertTrue(spoken.contains("Then factorise."))
    }

    @Test
    fun `handles dollar delimited maths`() {
        val d = '$'
        val spoken = stripForSpeech("The root is ${d}x = 3$d exactly.")
        assertFalse(spoken.contains(d))
        assertTrue(spoken.contains("The root is"))
        assertTrue(spoken.contains("exactly."))
    }

    @Test
    fun `strips latex commands that escape the delimiters`() {
        // Models sometimes emit a bare \times outside any delimiter.
        val spoken = stripForSpeech("""Multiply 2 \times 3 to get 6""")
        assertFalse(spoken.contains("\\times"))
        assertTrue(spoken.contains("Multiply 2"))
        assertTrue(spoken.contains("to get 6"))
    }

    @Test
    fun `keeps indian language text untouched`() {
        // The whole point of the feature: the explanatory words must survive.
        val telugu = "**దశలు** — సమీకరణాన్ని factor చేయాలి."
        val spoken = stripForSpeech(telugu)
        assertTrue(spoken.contains("దశలు"))
        assertTrue(spoken.contains("సమీకరణాన్ని"))
        assertTrue(spoken.contains("factor"))
    }

    @Test
    fun `collapses the blank lines markdown leaves behind`() {
        val spoken = stripForSpeech("One\n\n\n\nTwo")
        assertEquals("One\nTwo", spoken)
    }

    @Test
    fun `empty in empty out`() {
        assertEquals("", stripForSpeech(""))
        assertEquals("", stripForSpeech("   \n\n  "))
    }
}

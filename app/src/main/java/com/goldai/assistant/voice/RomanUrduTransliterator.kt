package com.goldai.assistant.voice

/**
 * V21 — Roman Urdu → Devanagari transliteration, on-device, no
 * network. Lia's replies are written in Roman Urdu ("tum kaisi ho"),
 * but every Hindi TTS voice in V21 expects Devanagari text — and
 * the Hindi voice models have NO language switch (Supertonic 3's
 * text encoder has `n_langs: 0`: it reads pronunciation straight
 * off the characters, so Latin letters come out English-sounding,
 * which is exactly the accent bug V21 fixes). Transliteration is
 * therefore REQUIRED before Hindi TTS, not optional.
 *
 * The mapping is deterministic and best-effort: longest match first
 * (aspirated digraphs kh/gh/ch/jh/th/dh/ph/bh, sh, long vowels
 * aa/ee/oo/ai/au), then single letters. A consonant followed by a
 * vowel takes the vowel SIGN (matra); a consonant followed by
 * another consonant takes a halant (conjunct); 'a' after a consonant
 * is the inherent vowel (nothing written); a vowel at a word start
 * or after a vowel is the independent form.
 *
 * Traced examples (verified by simulating the scan — these are
 * the ACTUAL outputs, not the canonical spellings):
 * - "tumhari" → तुम्हरि, "kaisa" → कैस, "hai" → है, "hun" → हुन
 * - "mujhe" → मुझे, "sunao" → सुनओ, "kiya" → किय, "batao" → बतओ
 * - "Assalam-o-Alaikum" → अस्सलम-ओ-अलिकुम, "Lia" → लिअ
 * Why this is still right to SPEAK: a single 'a' is never written
 * — the consonant's inherent vowel carries the sound, so तुम्हरि
 * is pronounced "tumhari", कैस "kaisa", सुनओ "sunao". What is
 * lost is only vowel LENGTH (canonical तुम्हारी/कैसा/सुनाओ write
 * the long ā with an explicit ा); the Hindi voice says the word
 * with a short 'a' instead — a minor accent difference, not a
 * comprehension failure. A dictionary would fix the length; this
 * deterministic table cannot, and does not pretend to.
 * Known approximations (documented, not bugs to chase here):
 * - "main" → मैन (correct is मैं — the anusvara rule is ambiguous
 *   in Roman script; "jan" → जन needs the same long ā).
 * - "acha" → अच (correct is अच्छा — gemination is not inferred;
 *   pronounced "acha" via the inherent vowel, same as above).
 * - English loanwords are transliterated too ("joke" → जोके) — the
 *   Hindi voice reads them Hindi-style, which matches how Hinglish
 *   is actually spoken.
 * - Retroflex sounds (ट/ड/ड़) have no standard Roman spelling, so
 *   't'/'d'/'r' stay dental (त/द/र).
 *
 * Non-Latin text (already-Devanagari, Arabic script, digits) passes
 * through unchanged; emoji and symbols are stripped in
 * [sanitizeForTts] because a TTS text encoder must never be fed a
 * heart or a smiley.
 */
object RomanUrduTransliterator {

    /** Two-letter consonants (aspirates + sh). Checked before singles. */
    private val DIGRAPH_CONSONANTS: Map<String, String> = mapOf(
        "kh" to "ख",
        "gh" to "\u095A", // ग़ (qaaf-like gh, with nukta)
        "ch" to "च",
        "jh" to "झ",
        "th" to "थ",
        "dh" to "ध",
        "ph" to "फ",
        "bh" to "भ",
        "sh" to "श"
    )

    /** Single-letter consonants. 'x' is pre-expanded to "ks" first. */
    private val CONSONANTS: Map<Char, String> = mapOf(
        'k' to "क",
        'g' to "ग",
        'c' to "क",
        'j' to "ज",
        't' to "त",
        'd' to "द",
        'n' to "न",
        'p' to "प",
        'b' to "ब",
        'm' to "म",
        'y' to "य",
        'r' to "र",
        'l' to "ल",
        'v' to "व",
        'w' to "व",
        's' to "स",
        'h' to "ह",
        'q' to "\u0958", // क़
        'z' to "\u095B", // ज़
        'f' to "\u095E" // फ़
    )

    /**
     * Vowels as (independent form, sign form). 'a' is NOT here: it is
     * the inherent vowel — nothing after a consonant, अ at a word
     * start or after another vowel.
     */
    private val VOWELS: Map<String, Pair<String, String>> = mapOf(
        "aa" to ("आ" to "ा"),
        "ee" to ("ई" to "ी"),
        "oo" to ("ऊ" to "ू"),
        "ai" to ("ऐ" to "ै"),
        "au" to ("औ" to "ौ")
    )

    private val VOWEL_SINGLES: Map<Char, Pair<String, String>> = mapOf(
        'i' to ("इ" to "ि"),
        'u' to ("उ" to "ु"),
        'e' to ("ए" to "े"),
        'o' to ("ओ" to "ो")
    )

    private const val HALANT = "्"

    /** True when the text has any Latin letter (i.e. needs transliterating). */
    fun needsTransliteration(text: String): Boolean =
        text.any { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * Transliterate Roman Urdu to Devanagari. Text with no Latin
     * letters comes back unchanged (except sanitising, see
     * [sanitizeForTts] — callers that already sanitised can use
     * this directly). Whitespace, digits and punctuation are kept.
     */
    fun transliterate(text: String): String {
        if (!needsTransliteration(text)) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isLetter() && (c in 'a'..'z' || c in 'A'..'Z')) {
                // One Latin word: scan it, keep separators as-is.
                var j = i
                while (j < text.length) {
                    val d = text[j]
                    if ((d in 'a'..'z' || d in 'A'..'Z') || d == '\'') j++ else break
                }
                out.append(transliterateWord(text.substring(i, j)))
                i = j
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Drop emoji and symbols TTS cannot read, keep letters (Latin +
     * Devanagari + Arabic), digits, whitespace and plain punctuation.
     * Apostrophes are kept here — the word scanner skips them.
     */
    fun sanitizeForTts(text: String): String {
        val kept = StringBuilder(text.length)
        for (c in text) {
            when {
                c.isLetterOrDigit() -> kept.append(c)
                c.isWhitespace() -> kept.append(c)
                c in ".,!?;:\"'()-–—" -> kept.append(c)
                c == '।' || c == '۔' -> kept.append(c)
                // Everything else (emoji, *, #, @, /, \, etc.) is dropped.
            }
        }
        return kept.toString()
    }

    /** Transliterate one Latin word (lowercased, 'x' expanded to "ks"). */
    fun transliterateWord(word: String): String {
        val w = word.lowercase().replace("x", "ks")
        val out = StringBuilder(w.length + 4)
        var lastWasConsonant = false
        var i = 0
        while (i < w.length) {
            val c = w[i]
            if (c == '\'') {
                i++
                continue
            }
            // Two-letter units first (longest match wins).
            val two = if (i + 1 < w.length) w.substring(i, i + 2) else null
            if (two != null && DIGRAPH_CONSONANTS.containsKey(two)) {
                emitConsonant(out, DIGRAPH_CONSONANTS.getValue(two), lastWasConsonant)
                lastWasConsonant = true
                i += 2
                continue
            }
            if (two != null && VOWELS.containsKey(two)) {
                val (independent, sign) = VOWELS.getValue(two)
                emitVowel(out, independent, sign, lastWasConsonant)
                lastWasConsonant = false
                i += 2
                continue
            }
            if (c == 'a') {
                // Inherent vowel: completes a bare consonant silently;
                // at a word start (or after a vowel) it is an अ.
                if (!lastWasConsonant) out.append("अ")
                lastWasConsonant = false
                i++
                continue
            }
            val single = VOWEL_SINGLES[c]
            if (single != null) {
                emitVowel(out, single.first, single.second, lastWasConsonant)
                lastWasConsonant = false
                i++
                continue
            }
            val consonant = CONSONANTS[c]
            if (consonant != null) {
                emitConsonant(out, consonant, lastWasConsonant)
                lastWasConsonant = true
                i++
                continue
            }
            // Unknown letter: copy it and reset the consonant state.
            out.append(c)
            lastWasConsonant = false
            i++
        }
        return out.toString()
    }

    /** A consonant after a consonant joins it with a halant. */
    private fun emitConsonant(
        out: StringBuilder,
        consonant: String,
        lastWasConsonant: Boolean
    ) {
        if (lastWasConsonant) out.append(HALANT)
        out.append(consonant)
    }

    /** A vowel after a consonant is a sign; elsewhere it stands alone. */
    private fun emitVowel(
        out: StringBuilder,
        independent: String,
        sign: String,
        lastWasConsonant: Boolean
    ) {
        if (lastWasConsonant) out.append(sign) else out.append(independent)
    }
}

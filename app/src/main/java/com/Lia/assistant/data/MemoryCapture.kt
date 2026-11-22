package com.Lia.assistant.data

/**
 * V15 — rule-based long-term memory capture. No LLM needed, so it
 * works on every engine including offline.
 *
 * Three outcomes for a user turn:
 * - [Capture.Save]: an explicit "yaad rakhna ke …" / "remember that …"
 *   command — the fact is stored immediately and Lia confirms.
 * - [Capture.Confirm]: a light auto-rule matched (e.g. "mera naam X
 *   hai") — Lia asks "Yaad rakhun? Yes / No" and stores it only on yes.
 * - [Capture.None]: nothing worth remembering, or the text is sensitive.
 *
 * Sensitive facts are NEVER captured — not even proposed: anything
 * health / financial / credential-like is excluded outright (see
 * [SENSITIVE_MARKERS]). Nothing is ever stored silently.
 */
object MemoryCapture {

    sealed class Capture {
        /** Store immediately (explicit command). */
        data class Save(val fact: MemoryFact) : Capture()

        /** Ask "Yaad rakhun?" first (auto-rule match). */
        data class Confirm(val fact: MemoryFact) : Capture()

        object None : Capture()
    }

    /** Words that mark a fact as sensitive — never captured, never proposed. */
    private val SENSITIVE_MARKERS = listOf(
        "password", "pin", "otp", "passcode", "secret",
        "card number", "credit card", "debit card", "cvv",
        "bank account", "account number", "iban",
        "cnic", "aadhaar", "passport number",
        "salary", "tankhah", "income",
        "blood", "diabetes", "cancer", "hospital", "dawai", "medicine",
        "bimari", "bimaari", "allergy", "doctor"
    )

    private val EXPLICIT_PREFIXES = listOf(
        "yaad rakhna ke ",
        "yaad rakhna ki ",
        "yaad rakhna ",
        "remember that ",
        "remember "
    )

    /** "yes" answers to the "Yaad rakhun?" confirmation. */
    private val YES_WORDS = setOf(
        "haan", "han", "yes", "yaad rakho", "rakho", "rakh lo",
        "theek hai", "ok", "okay", "bilkul", "jee", "ji"
    )

    /** "no" answers to the "Yaad rakhun?" confirmation. */
    private val NO_WORDS = setOf(
        "nahi", "nahin", "no", "mat rakho", "rehne do", "nahi chahiye", "nope"
    )

    fun isSensitive(text: String): Boolean {
        val lower = text.lowercase()
        return SENSITIVE_MARKERS.any { lower.contains(it) }
    }

    /** True when [text] answers a pending "Yaad rakhun?" with yes. */
    fun isYes(text: String): Boolean {
        val lower = text.trim().lowercase()
        return YES_WORDS.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") }
    }

    /** True when [text] answers a pending "Yaad rakhun?" with no. */
    fun isNo(text: String): Boolean {
        val lower = text.trim().lowercase()
        return NO_WORDS.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") }
    }

    /** V16 (P1-4) — worthiness hints, Roman Urdu + English. A fact is
     * worth remembering only when it says something about who he is,
     * what he likes, who matters to him, or how he lives. */
    private val WORTHINESS_HINTS = listOf(
        "naam", "pasand", "favourite", "favorite", "dost", "ammi", "abu",
        "bhai", "behen", "behan", "biwi", "shohar", "ustad", "teacher",
        "college", "university", "parhta", "parhti", "padhta", "padhti",
        "semester", "roz", "daily", "subah", "shaam", "kaam", "job",
        "sheher", "city", "ghar", "birthday", "saal", "umar",
        "name", "like", "love", "work", "study", "live", "lives",
        "birthday", "age", "school", "friend", "mother", "father",
        "brother", "sister", "girlfriend", "boyfriend"
    )

    /** V16 (P1-4) — stage 2 of the gate: a fact must be at least 12
     * characters AND contain a worthiness hint word. Short throwaway
     * lines ("ok", "theek hai") are never worth remembering. */
    fun isWorthy(factText: String): Boolean {
        val trimmed = factText.trim()
        if (trimmed.length < 12) return false
        val lower = trimmed.lowercase()
        return WORTHINESS_HINTS.any { lower.contains(it) }
    }

    /** Analyse one user turn. Never throws. */
    fun analyze(text: String): Capture {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 400) return Capture.None
        // V16 (P1-4) — stage 1, deny-list FIRST: sensitive facts are
        // never captured, never even proposed.
        if (isSensitive(trimmed)) return Capture.None
        val lower = trimmed.lowercase()

        // 1. Explicit command — store immediately, but only when the
        // fact passes the stage-2 worthiness gate.
        for (prefix in EXPLICIT_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val factText = trimmed.substring(prefix.length).trim()
                    .trimEnd('.', '!', ' ')
                if (!isWorthy(factText)) return Capture.None
                return Capture.Save(
                    MemoryFact(
                        category = guessCategory(factText),
                        text = factText,
                        source = "user"
                    )
                )
            }
        }

        // 2. Light auto-rules — propose, never store silently. The
        // proposal must pass the same worthiness gate.
        val proposed = matchAutoRule(trimmed) ?: return Capture.None
        if (!isWorthy(proposed.text)) return Capture.None
        return Capture.Confirm(proposed)
    }

    private fun matchAutoRule(text: String): MemoryFact? {
        val lower = text.lowercase()
        // Keep auto-rules to short, simple sentences only.
        if (text.length > 120) return null

        val identityName = Regex("(?i)^mera naam (.+?) hai[.!]?$").find(text)
        if (identityName != null) {
            val name = identityName.groupValues[1].trim()
            if (name.isNotEmpty() && name.length <= 40) {
                return MemoryFact(category = "identity", text = "Mera naam $name hai", source = "confirmed")
            }
        }
        val englishName = Regex("(?i)^my name is (.+?)[.!]?$").find(text)
        if (englishName != null) {
            val name = englishName.groupValues[1].trim()
            if (name.isNotEmpty() && name.length <= 40) {
                return MemoryFact(category = "identity", text = "My name is $name", source = "confirmed")
            }
        }
        val likes = Regex("(?i)^mujhe (.+?) pasand hai[.!]?$").find(text)
        if (likes != null) {
            val thing = likes.groupValues[1].trim()
            if (thing.isNotEmpty() && thing.length <= 60) {
                return MemoryFact(category = "preference", text = "Mujhe $thing pasand hai", source = "confirmed")
            }
        }
        val likesEn = Regex("(?i)^i like (.+?)[.!]?$").find(text)
        if (likesEn != null) {
            val thing = likesEn.groupValues[1].trim()
            if (thing.isNotEmpty() && thing.length <= 60) {
                return MemoryFact(category = "preference", text = "I like $thing", source = "confirmed")
            }
        }
        // "Romisa meri dost hai" / "X meri ammi hai" — a person in my life.
        val person = Regex("(?i)^([A-Za-z]+) meri (dost|ammi|abu|bhai|behen|behan|biwi|shohar|ustad|teacher|girlfriend|boyfriend) hai[.!]?$").find(text)
        if (person != null) {
            return MemoryFact(category = "people", text = text.trimEnd('.', '!'), source = "confirmed")
        }
        val studies = Regex("(?i)^main (.+?) (parhta|parhti|padhta|padhti) hun[.!]?$").find(text)
        if (studies != null) {
            return MemoryFact(category = "life", text = text.trimEnd('.', '!'), source = "confirmed")
        }
        // "mera favourite X Y hai" — a preference, kept whole.
        if (lower.startsWith("mera favourite ") || lower.startsWith("meri favourite ")) {
            return MemoryFact(category = "preference", text = text.trimEnd('.', '!'), source = "confirmed")
        }
        return null
    }

    /** Best-guess category for an explicitly given fact. */
    private fun guessCategory(factText: String): String {
        val lower = factText.lowercase()
        return when {
            lower.contains("naam") || lower.startsWith("my name") -> "identity"
            lower.contains("pasand") || lower.contains("like ") ||
                lower.contains("favourite") || lower.contains("favorite") -> "preference"
            lower.contains("meri ") || lower.contains("mera ") &&
                (lower.contains("dost") || lower.contains("ammi") ||
                    lower.contains("abu") || lower.contains("bhai") ||
                    lower.contains("behen") || lower.contains("behan")) -> "people"
            lower.contains("college") || lower.contains("university") ||
                lower.contains("parhta") || lower.contains("parhti") ||
                lower.contains("bs ") || lower.contains("semester") -> "life"
            lower.contains("subah") || lower.contains("shaam") ||
                lower.contains("roz") || lower.contains("daily") -> "routine"
            else -> "misc"
        }
    }
}

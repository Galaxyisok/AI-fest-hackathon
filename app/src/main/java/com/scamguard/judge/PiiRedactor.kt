package com.scamguard.judge

/**
 * Strips numbers and IDs out of the transcript BEFORE anything leaves the phone.
 * The judge still sees that a number was said and how long it was (a 6-digit number after
 * "tell me the code" is the scam signal) but never the digits themselves.
 *
 * Mirrored by eval/lib/redactor.mjs; both run against shared/redactor-vectors.json.
 */
object PiiRedactor {

    data class Result(val text: String, val codeSpoken: Boolean)

    private val DIGIT_WORDS = setOf("zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val MULTIPLIERS = mapOf("double" to 2, "triple" to 3)
    private val ID_RE = Regex("[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+")
    private val DIGIT_RUN_RE = Regex("\\d(?:[ -]?\\d){3,}")
    private val TRAILING_PUNCT = Regex("[.,?!;:]+$")
    private val WHITESPACE = Regex("\\s+")

    private fun label(n: Int) = "[$n-DIGIT NUMBER]"
    private fun isCodeLength(n: Int) = n in 4..8
    private fun bare(token: String) = token.lowercase().replace(TRAILING_PUNCT, "")

    fun redact(text: String): Result {
        var codeSpoken = false
        var out = ID_RE.replace(text, "[ID]")

        out = DIGIT_RUN_RE.replace(out) { m ->
            val n = m.value.count { it.isDigit() }
            if (isCodeLength(n)) codeSpoken = true
            label(n)
        }

        // Spoken digits: the speech engine writes "four eight two nine", never "4829".
        val tokens = out.split(WHITESPACE).filter { it.isNotEmpty() }
        val result = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var j = i
            var digits = 0
            while (j < tokens.size) {
                val w = bare(tokens[j])
                if (w in DIGIT_WORDS) { digits += 1; j += 1; continue }
                val mult = MULTIPLIERS[w]
                if (mult != null && j + 1 < tokens.size && bare(tokens[j + 1]) in DIGIT_WORDS) {
                    digits += mult; j += 2; continue
                }
                break
            }
            if (digits >= 4) {
                if (isCodeLength(digits)) codeSpoken = true
                result.add(label(digits))
                i = j
            } else {
                result.add(tokens[i])
                i += 1
            }
        }
        return Result(result.joinToString(" "), codeSpoken)
    }
}

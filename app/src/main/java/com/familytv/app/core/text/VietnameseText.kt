package com.familytv.app.core.text

import java.text.Normalizer
import java.util.Locale

/** Accent-insensitive helpers for Vietnamese titles (works for NFC and NFD file names). */
object VietnameseText {
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** "Đồ chơi Ướt" -> "do choi uot". */
    fun fold(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return MARKS.replace(decomposed, "")
            .replace('đ', 'd').replace('Đ', 'd')
            // U+00D0/U+00F0 (Eth) are sometimes typed instead of Đ/đ.
            .replace('Ð', 'd').replace('ð', 'd')
            .lowercase(Locale.ROOT)
    }

    /** Folded text with punctuation (_ - . etc.) turned into single spaces. */
    fun searchKey(input: String): String = NON_ALNUM.replace(fold(input), " ").trim()

    fun tokens(query: String): List<String> = searchKey(query).split(' ').filter { it.isNotEmpty() }

    /** True when every token occurs in [key]. An empty token list never matches. */
    fun matchesAll(key: String, tokens: List<String>): Boolean =
        tokens.isNotEmpty() && tokens.all { key.contains(it) }

    /** Display form: composed (NFC) so macOS-written NFD names render correctly. */
    fun display(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFC).trim()
}

/** "Bai 2" < "Bai 10"; case- and accent-insensitive, original string as tie-breaker. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = VietnameseText.fold(a)
        val y = VietnameseText.fold(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isAsciiDigit() && cy.isAsciiDigit()) {
                val si = i
                while (i < x.length && x[i].isAsciiDigit()) i++
                val sj = j
                while (j < y.length && y[j].isAsciiDigit()) j++
                val nx = x.substring(si, i).trimStart('0')
                val ny = y.substring(sj, j).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
            } else {
                if (cx != cy) return cx - cy
                i++
                j++
            }
        }
        val rest = (x.length - i) - (y.length - j)
        if (rest != 0) return rest
        return a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

package com.familytv.app.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class VietnameseTextTest {
    private fun matches(title: String, query: String) =
        VietnameseText.matchesAll(VietnameseText.searchKey(title), VietnameseText.tokens(query))

    @Test
    fun `folding removes tones and maps đ to d`() {
        assertEquals("do choi uot", VietnameseText.fold("Đồ chơi Ướt"))
        assertEquals("tieng anh cho be", VietnameseText.fold("Tiếng Anh cho bé"))
        assertEquals("duong dua", VietnameseText.fold("ĐƯỜNG ĐUA"))
    }

    @Test
    fun `accent-free queries match accented titles`() {
        assertTrue(matches("Bài hát ABC", "bai hat"))
        assertTrue(matches("Thế giới Động vật", "dong vat"))
        assertTrue(matches("Thế giới Động vật", "DONG VAT"))
        assertTrue(matches("Đi học", "di hoc"))
        assertTrue(matches("Bài hát ABC", "Bài Hát"))
        assertTrue(matches("bai_hat-con_co.v2", "con co"))
    }

    @Test
    fun `nfd file names (macOS) match too`() {
        val nfd = Normalizer.normalize("Bài hát Con Cò", Normalizer.Form.NFD)
        assertTrue(matches(nfd, "con cò"))
        assertTrue(matches(nfd, "con co"))
        assertEquals(Normalizer.normalize("Bài hát Con Cò", Normalizer.Form.NFC), VietnameseText.display(nfd))
    }

    @Test
    fun `all tokens are required and empty query matches nothing`() {
        assertFalse(matches("Bài hát ABC", "bai hat xyz"))
        assertFalse(matches("Bài hát ABC", "   "))
        assertFalse(matches("Bài hát ABC", "!!!"))
    }

    @Test
    fun `natural order sorts numbers by value`() {
        val sorted = listOf("Bài 10", "bài 2", "Bài 1", "Bài 01b", "Ăn sáng").sortedWith(NaturalOrder)
        assertEquals(listOf("Ăn sáng", "Bài 1", "Bài 01b", "bài 2", "Bài 10"), sorted)
    }
}

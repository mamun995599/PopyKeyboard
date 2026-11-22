package com.popykeyboard

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class BanglaPhoneticTest {
    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    @Test
    fun guideExamples() {
        val stream = javaClass.classLoader!!.getResourceAsStream("typing_cases.tsv")!!
        val failures = ArrayList<String>()
        var n = 0
        stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val (typed0, expected) = line.split('\t')
            n++
            // ⇧ = শিফট (যুক্ত কী) ট্যাপ = বাফারে SH টোকেন
            val typed = typed0.replace('⇧', BanglaPhonetic.SH)
            val got = BanglaPhonetic.convert(typed)
            if (nfc(got) != nfc(expected)) failures.add("$typed -> $got (expected $expected)")
        }
        assertEquals("$n cases, failures:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun shiftKey() {
        val sh = BanglaPhonetic.SH
        // শিফট ট্যাপ: ব্যঞ্জনের আগে জোর-যুক্ত, স্বরের আগে স্বাধীন স্বর
        assertEquals(nfc("ক্ফ"), nfc(BanglaPhonetic.convert("k${sh}f")))
        assertEquals(nfc("ন্ক"), nfc(BanglaPhonetic.convert("n${sh}k")))
        assertEquals(nfc("কফ"), nfc(BanglaPhonetic.convert("kf")))
        assertEquals(nfc("বই"), nfc(BanglaPhonetic.convert("bo${sh}i")))
        // বারবার চাপলেও একটি
        assertEquals(nfc("ত্ত"), nfc(BanglaPhonetic.convert("t${sh}${sh}t")))
        // মার্কার s
        val p = BanglaPhonetic.preview("t$sh")
        assertEquals('s', p.marker)
        assertEquals(true, BanglaPhonetic.canForceJoin("k"))
        assertEquals(false, BanglaPhonetic.canForceJoin("ka"))
    }

    @Test
    fun nonGreedyCases() {
        val stream = javaClass.classLoader!!.getResourceAsStream("typing_cases_nongreedy.tsv")!!
        BanglaPhonetic.greedy = false
        try {
            val bad = ArrayList<String>()
            stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val (typed0, expected) = line.split('\t')
                val got = BanglaPhonetic.convert(typed0.replace('⇧', BanglaPhonetic.SH))
                if (nfc(got) != nfc(expected)) bad.add("$typed0 -> $got (expected $expected)")
            }
            assertEquals(bad.joinToString("\n"), 0, bad.size)
        } finally {
            BanglaPhonetic.greedy = true
        }
    }

    @Test
    fun dictionaryCorrection() {
        // ডিকশনারি ফাইল কী (নরমালাইজড শব্দ) অনুযায়ী সাজানো থাকে: আমি < তুমি/তুমী
        val data = "আমি\t50\nতুমি\t100\nতুমী\t1\n"
        PopyDictionary.load(data.byteInputStream(Charsets.UTF_8))
        assertEquals("তুমি", PopyDictionary.correct("তুমী"))
        assertEquals("আমি", PopyDictionary.correct("আমি"))
        assertEquals("অজানা", PopyDictionary.correct("অজানা")) // তালিকায় নেই: যেমন আছে
        assertEquals("ab", PopyDictionary.correct("ab")) // বাংলা শব্দ নয়: ছোঁয়া হয় না
    }
}

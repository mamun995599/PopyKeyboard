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
            val (typed, expected) = line.split('\t')
            n++
            val got = BanglaPhonetic.convert(typed)
            if (nfc(got) != nfc(expected)) failures.add("$typed -> $got (expected $expected)")
        }
        assertEquals("$n cases, failures:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun forceJoinKey() {
        // শিফট (`) শুধু ব্যঞ্জনের পর কাজ করে
        assertEquals(true, BanglaPhonetic.canForceJoin("k"))
        assertEquals(true, BanglaPhonetic.canForceJoin("nk"))
        assertEquals(false, BanglaPhonetic.canForceJoin("ka"))
        assertEquals(false, BanglaPhonetic.canForceJoin(""))
        assertEquals(nfc("ক্ফ"), nfc(BanglaPhonetic.convert("k`f")))
        assertEquals(nfc("ন্ক"), nfc(BanglaPhonetic.convert("n`k")))
        assertEquals(nfc("কভ"), nfc(BanglaPhonetic.convert("kbh")))
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

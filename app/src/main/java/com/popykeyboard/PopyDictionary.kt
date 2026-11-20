package com.popykeyboard

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * পপি ডিকশনারি: অটো-কারেকশন ও সাজেশন (পিসি ভার্সনের clsPopyDictionary.pas-এর মতো)।
 *
 * শব্দতালিকা (assets/popy_words.dat): প্রতি লাইনে "শব্দ<TAB>ব্যবহার-সংখ্যা", আগে থেকেই
 * "নরমালাইজেশন কী" অনুযায়ী সাজানো (কী সমান হলে বেশি ব্যবহৃত শব্দ আগে)। কী-তে বাংলা টাইপিস্টদের
 * গুলিয়ে ফেলা বর্ণগুলো এক করা হয়: ঈ/ই, ী/ি, ঊ/উ, ূ/ু, শ/ষ/স, ণ/ন, ৎ/ত, ZWJ/ZWNJ বাদ।
 *
 * মেমোরি বাঁচাতে সব শব্দ একটি বড় স্ট্রিং + অফসেট-অ্যারেতে রাখা হয়। এই অবজেক্ট Android-মুক্ত;
 * তালিকা ব্যাকগ্রাউন্ড থ্রেডে একবারই লোড হয়।
 */
object PopyDictionary {

    @Volatile private var text: String = ""
    @Volatile private var offsets: IntArray = IntArray(0)   // শব্দ i = text[offsets[i] until offsets[i + 1]]
    @Volatile private var freqs: IntArray = IntArray(0)
    @Volatile private var count = 0
    @Volatile private var loaded = false
    @Volatile private var loading = false
    /** লোড ব্যর্থ হলে কারণ (সাজেশন বারে দেখানোর জন্য); ফাঁকা = কোনো ত্রুটি নেই */
    @Volatile var lastError: String = ""
    fun isLoading(): Boolean = loading

    /** টাইপ করা শব্দের ব্যবহার-সংখ্যা সেরা বানানের ১/RARE_RATIO-এর কম হলে সেটিকে ভুল ধরা হয় */
    private const val RARE_RATIO = 50L

    /** তালিকা লোড শেষ? (শেষ না হওয়া পর্যন্ত অটো-কারেকশন/সাজেশন কিছু করে না) */
    fun isReady(): Boolean = loaded && count > 0

    /**
     * ব্যাকগ্রাউন্ড থ্রেডে লোড শুরু (একবারই)। [open] তালিকার স্ট্রিম দেয় (gzip হোক বা সাধারণ লেখা, দুটোই চলে)।
     * লোড শেষ হলে [onReady] ব্যাকগ্রাউন্ড থ্রেডে ডাকা হয়; ব্যর্থ হলে পরের বার আবার চেষ্টা হয়।
     */
    fun preload(onReady: (() -> Unit)? = null, open: () -> InputStream) {
        if (loaded || loading) return
        loading = true
        Thread {
            try {
                open().use { raw ->
                    val bis = java.io.BufferedInputStream(raw, 1 shl 16)
                    bis.mark(2)
                    val b0 = bis.read()
                    val b1 = bis.read()
                    bis.reset()
                    // gzip-এর ম্যাজিক বাইট (1F 8B) থাকলে আনজিপ; না থাকলে (বিল্ড টুল আগেই খুলে দিলে) সরাসরি পড়া
                    val stream: InputStream =
                        if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(bis, 1 shl 16) else bis
                    load(stream)
                }
                lastError = ""
                onReady?.invoke()
            } catch (t: Throwable) {
                // তালিকা না থাকলে/পড়া না গেলে অটো-কারেকশন ও সাজেশন বন্ধ থাকে; পরের ইনপুটে আবার চেষ্টা হবে
                loaded = false
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
            } finally {
                loading = false
            }
        }.apply { priority = Thread.MIN_PRIORITY; start() }
    }

    /** টেস্টের জন্য সরাসরি লোড (সাজানো "শব্দ<TAB>সংখ্যা" লাইন)। */
    fun load(stream: InputStream) {
        val sb = StringBuilder(4 shl 20)
        var offs = IntArray(1 shl 16)
        var fr = IntArray(1 shl 16)
        var n = 0
        stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val tab = line.indexOf('\t')
            val w = if (tab >= 0) line.substring(0, tab) else line
            if (w.isEmpty()) return@forEachLine
            val f = if (tab >= 0) line.substring(tab + 1).toIntOrNull() ?: 1 else 1
            if (n + 1 >= offs.size) { offs = offs.copyOf(offs.size * 2); fr = fr.copyOf(fr.size * 2) }
            offs[n] = sb.length
            fr[n] = f
            sb.append(w)
            n++
        }
        offs[n] = sb.length
        text = sb.toString()
        offsets = offs
        freqs = fr
        count = n
        loaded = true
    }

    private fun wordAt(i: Int): String = text.substring(offsets[i], offsets[i + 1])

    // ---------- নরমালাইজেশন ----------

    /** য় ড় ঢ় — একক কোড-পয়েন্ট রূপ (তালিকা ও টাইপ করা শব্দ দুটোই এক রূপে মেলানো হয়) */
    fun composeNukta(s: String): String {
        if (s.indexOf('\u09BC') < 0) return s
        return s.replace("\u09A1\u09BC", "\u09DC").replace("\u09A2\u09BC", "\u09DD").replace("\u09AF\u09BC", "\u09DF")
    }

    fun normKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\u200C', '\u200D' -> {}
                '\u0988' -> sb.append('\u0987') // ঈ -> ই
                '\u09C0' -> sb.append('\u09BF') // ী -> ি
                '\u098A' -> sb.append('\u0989') // ঊ -> উ
                '\u09C2' -> sb.append('\u09C1') // ূ -> ু
                '\u09B6', '\u09B7' -> sb.append('\u09B8') // শ ষ -> স
                '\u09A3' -> sb.append('\u09A8') // ণ -> ন
                '\u09CE' -> sb.append('\u09A4') // ৎ -> ত
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** শুধু সাধারণ বাংলা অক্ষর/চিহ্ন; অঙ্ক, ল্যাটিন, যতিচিহ্ন নয় */
    fun isBanglaWord(s: String): Boolean {
        if (s.length < 2) return false
        for (c in s) {
            if (c == '\u200C' || c == '\u200D') continue
            if (c < '\u0980' || c > '\u09FF' || (c in '\u09E6'..'\u09EF') ||
                c == '\u09F3' || c == '\u09F7' || c == '\u09E4' || c == '\u09E5'
            ) return false
        }
        return true
    }

    /** প্রথম index যার কী >= k */
    private fun lowerBound(k: String): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (normKey(wordAt(mid)).compareTo(k) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ---------- অটো-কারেকশন ও সাজেশন ----------

    /** টাইপ করা শব্দ যেমন আছে তেমনই ফেরে, যদি না তালিকায় ভালো বানান থাকে। সাধারণ বাংলা শব্দ নয় এমন লেখা ছোঁয়া হয় না। */
    fun correct(word: String): String {
        if (!isReady() || !isBanglaWord(word)) return word
        val w = composeNukta(word)
        val k = normKey(w)
        val first = lowerBound(k)
        if (first >= count || normKey(wordAt(first)) != k) return word // তালিকায় এই কী-র কোনো শব্দ নেই
        val best = wordAt(first) // একই কী-র মধ্যে সবচেয়ে বেশি ব্যবহৃত শব্দ
        if (best == w) return word
        // টাইপ করা শব্দ তালিকায় থাকলেও যদি অত্যন্ত বিরল হয় (ভুল বানান), তবে জনপ্রিয় বানানে ঠিক করা হয়।
        // কাছাকাছি ব্যবহারের বৈধ শব্দ (যেমন শাল/সাল) অপরিবর্তিত থাকে।
        var i = first
        while (i < count && normKey(wordAt(i)) == k) {
            if (wordAt(i) == w) {
                return if (freqs[i].toLong() * RARE_RATIO < freqs[first]) best else word
            }
            i++
        }
        return best
    }

    /** আগে একই কী-র শব্দ (বেশি ব্যবহৃত আগে), তারপর যেসব শব্দ টাইপ করা অক্ষর দিয়ে শুরু। */
    fun suggestions(word: String, limit: Int): List<String> {
        if (!isReady() || !isBanglaWord(word)) return emptyList()
        val k = normKey(composeNukta(word))
        val res = ArrayList<String>(limit)
        var i = lowerBound(k)
        while (i < count && res.size < limit && normKey(wordAt(i)) == k) {
            res.add(wordAt(i)); i++
        }
        if (res.size < limit) {
            val extra = ArrayList<Int>()
            var scan = 0
            while (i < count && scan < 20000 && normKey(wordAt(i)).startsWith(k)) {
                extra.add(i); i++; scan++
            }
            extra.sortByDescending { freqs[it] }
            for (idx in extra) {
                if (res.size >= limit) break
                res.add(wordAt(idx))
            }
        }
        return res
    }
}

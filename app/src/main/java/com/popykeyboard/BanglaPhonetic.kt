package com.popykeyboard

/**
 * "নতুন লেআউট" ফোনেটিক টাইপিং ইঞ্জিন: ইংরেজি (ল্যাটিন) বর্ণ থেকে বাংলা।
 *
 * ইঞ্জিনটি পুরোপুরি Android-মুক্ত (pure Kotlin), তাই সহজে ইউনিট টেস্ট করা যায়।
 * কিবোর্ড প্রতিটি কী-চাপে বর্তমান শব্দের পুরো ল্যাটিন বাফার [convert] এ পাঠায়,
 * ফলাফলটি composing text হিসেবে দেখানো হয়।
 */
object BanglaPhonetic {

    private const val HASANTA = "্"
    private const val ZWNJ = "\u200C"
    private const val ZWJ = "\u200D"
    private const val NUKTA = "়"

    /**
     * শিফট-ট্যাপ টোকেন (বাফারে \u0001)। "স্বয়ংক্রিয় মিলন নয়" কী: ব্যঞ্জনের আগে জোর করে যুক্ত
     * (t⇧t = ত্ত, k⇧f = ক্ফ), স্বরের আগে স্বাধীন স্বর (bo⇧i = বই)।
     */
    const val SH = '\u0001'

    /**
     * গ্রিডি মোড (ডিফল্ট চালু): তালিকার ১৬৩ জোড়া নিজে যুক্ত হয়, শিফট প্রিফিক্স (ব্যঞ্জনের আগে)।
     * বন্ধ: কিছুই নিজে যুক্ত হয় না; র, য স্বাধীন; শিফট পোস্টফিক্স: লেখা শেষ ব্যঞ্জনকে আগেরটির সাথে
     * যুক্ত করে (km⇧ = ক্ম), কারের পরে হলে কারকে স্বাধীন স্বর করে (ka⇧ = কআ, ko⇧ = কঅ)।
     */
    @Volatile var greedy: Boolean = true

    private fun isConsLetter(ch: Char): Boolean =
        ch in 'ক'..'হ' || ch == '\u09DC' || ch == '\u09DD' || ch == '\u09DF' || ch == '\u09CE' || ch == '\u09BC'

    // ----- ব্যঞ্জন -----
    private val consonants: Map<String, String> = mapOf(
        "k" to "ক", "kh" to "খ", "g" to "গ", "gh" to "ঘ", "nng" to "ঙ",
        "c" to "চ", "ch" to "ছ", "j" to "জ", "jh" to "ঝ", "nnn" to "ঞ",
        "tt" to "ট", "tth" to "ঠ", "dd" to "ড", "ddh" to "ঢ", "nn" to "ণ",
        "t" to "ত", "th" to "থ", "d" to "দ", "dh" to "ধ", "n" to "ন",
        "p" to "প", "f" to "ফ", "ph" to "ফ", "b" to "ব", "bh" to "ভ", "v" to "ভ",
        "m" to "ম", "y" to "য$NUKTA", "z" to "য", "r" to "র", "l" to "ল",
        "s" to "স", "ss" to "শ", "sss" to "ষ", "h" to "হ",
        "x" to "ক${HASANTA}ষ",
        "rr" to "ড$NUKTA", "rrh" to "ঢ$NUKTA",
    )

    // ----- স্বর: টোকেন -> (স্বতন্ত্র রূপ, কার) -----
    private val vowels: Map<String, Pair<String, String>> = mapOf(
        "o" to Pair("অ", ""),
        "a" to Pair("আ", "া"),
        "i" to Pair("ই", "ি"),
        "ii" to Pair("ঈ", "ী"),
        "u" to Pair("উ", "ু"),
        "uu" to Pair("ঊ", "ূ"),
        "rrr" to Pair("ঋ", "ৃ"),
        "e" to Pair("এ", "ে"),
        "oi" to Pair("ঐ", "ৈ"),
        "oo" to Pair("ও", "ো"),
        "ou" to Pair("ঔ", "ৌ"),
    )

    private val digits = "০১২৩৪৫৬৭৮৯"

    /** '0'..'9' -> বাংলা অঙ্ক, অন্যথায় null */
    fun digit(c: Char): String? = if (c in '0'..'9') digits[c - '0'].toString() else null

    private fun isLetter(c: Char) = c in 'a'..'z'

    /** টাইপ চলাকালীন দেখানোর ফল। [markerIndex] >= 0 হলে ওই অবস্থানের অক্ষরটি রঙিন মার্কার ('o' লাল, 'q' সবুজ, 's' = শিফট নীল)। */
    class Preview(val text: String, val markerIndex: Int, val marker: Char, val endsWithConsonant: Boolean = false)

    /** ল্যাটিন লেখা → বাংলা (চূড়ান্ত, মার্কার ছাড়া)। */
    fun convert(input: String): String = run(input, false).text

    /**
     * টাইপ চলাকালীন composing টেক্সট। শেষ কী যদি যুক্তাক্ষর-বাধা 'o' (ব্যঞ্জনের পর) বা
     * ভাঙার চিহ্ন 'q' হয়, তবে সেটি দৃশ্যমান মার্কার হিসেবে শেষে দেখায়; পরের অক্ষর এলে সরে যায়।
     */
    fun preview(input: String): Preview = run(input, true)


    /**
     * যে ১৬৩টি ব্যঞ্জন-জোড়া (প্রথম + দ্বিতীয়) নিজে থেকে যুক্ত হয়। রেফ (র) সবার সাথে যুক্ত হয়।
     * বাকি জোড়া শিফট-ট্যাপ দিয়ে জোর করে যুক্ত করতে হয়।
     */
    private val PAIRS: Set<String> = """
        কক কট কত কদ কন কম কয কর কল কষ
        কস খয খর গগ গধ গন গম গয গর গল
        ঘন ঘয ঘর ঙক ঙখ ঙগ ঙঘ ঙম ঙয ঙর
        চচ চছ চঞ চয ছয ছর জজ জঝ জঞ জয
        জর ঝয ঞচ ঞছ ঞজ ঞঝ টট টম টয টর
        ঠয ডড ডম ডয ডর ঢয ঢর ণট ণঠ ণড
        ণঢ ণম ণয তন তম তয তর থয থর দগ
        দঘ দভ দম দয দর ধন ধম ধয ধর নট
        নঠ নড নত নথ নদ নধ নম নয নস পট
        পত পথ পন পপ পয পর পল পস ফট ফয
        ফর ফল বজ বদ বধ বয বর বল ভয ভর
        ভল মন মপ মফ মভ মম ময মর মল যয
        লক লগ লট লড লপ লফ লম লয লল শচ
        শছ শত শন শম শয শর শল ষক ষট ষঠ
        ষণ ষপ ষফ ষম ষয সক সখ সট সত সথ
        সন সপ সফ সম সয সর সল হণ হন হম
        হয হর হল
    """.trim().split(Regex("\\s+")).toHashSet()

    private fun joinable(a: Char, b: Char): Boolean = a == 'র' || PAIRS.contains("$a$b")

    private fun endsWithPlainRa(out: StringBuilder): Boolean =
        out.isNotEmpty() && out[out.length - 1] == 'র' &&
            (out.length < 2 || out[out.length - 2] != HASANTA[0])

    private fun run(input: String, preview: Boolean): Preview {
        // শুধু A–Z ছোট হাতের করা হয় (দৈর্ঘ্য অপরিবর্তিত থাকে)
        val src = buildString(input.length) {
            for (ch in input) append(if (ch in 'A'..'Z') (ch + 32) else ch)
        }
        val n = src.length
        val out = StringBuilder()
        // consAfter = সর্বশেষ আউটপুট একটি ব্যঞ্জন (কার/যুক্তাক্ষর নেওয়ার জন্য প্রস্তুত)
        var consAfter = false
        var wordStart = true
        // শব্দের শুরুর 'র' — এর পর z এলে রেফ নয়, র‍্য (য-ফলা) হয়
        var raInitial = false
        // q দিয়ে ভাঙা হয়েছে — পরের z এ য-ফলা হবে না (অযথা = oqzotha)
        var qBreak = false
        // সর্বশেষ ব্যঞ্জন-টোকেন ("t", "nn") ও তার শেষ বর্ণ (যুক্তাক্ষরের তালিকা মেলাতে)
        var last = ""
        var lastLetter = ' '
        // শেষ ব্যঞ্জন র-ফলা/য-ফলা হলে তার পরে নিজে থেকে যুক্ত হয় না
        var phala = false
        // শিফট ট্যাপ হয়েছে: পরের ব্যঞ্জন যুক্ত হবে / পরের স্বর স্বাধীন হবে
        var shift = false
        // শেষ টোকেনটি মার্কার-যোগ্য হলে: 'o' / 'q' / 's' (আউটপুটে অদৃশ্য), 'Q' (আউটপুটে আক্ষরিক q আছে)
        var pending = ' '
        // নন-গ্রিডি: শিফট পোস্টফিক্স। lStart = শেষ ব্যঞ্জন আউটপুটের যে অবস্থানে শুরু (-1 = নেই)
        var lStart = -1
        var lTok = ""
        var lRa = false
        // কারের পরে শিফট: (কারের অবস্থান, স্বাধীন রূপ)
        var lVowSt = -1
        var lVowInd = ""
        var i = 0

        while (i < n) {
            val c = src[i]
            if (c == SH && !greedy) {
                if (lVowSt >= 0) {
                    out.setLength(lVowSt); out.append(lVowInd)
                    lVowSt = -1
                }
                if (lStart > 0) {
                    val prev = out[lStart - 1]
                    if (isConsLetter(prev) || (lTok == "z" && prev in '\u0985'..'\u0994')) {
                        out.insert(lStart, if (lRa) "$ZWJ$HASANTA" else HASANTA)
                    }
                }
                lStart = -1
                i++
                pending = ' '
                continue
            }
            if (c == SH) { // শিফট ট্যাপ: পরের কী কাজে লাগালে 's' সরে যায়
                shift = true
                i++
                pending = if (i == n) 's' else ' '
                continue
            }
            lStart = -1
            lVowSt = -1
            val wasRa = raInitial
            raInitial = false
            val wasQ = qBreak
            qBreak = false
            val wasShift = shift
            shift = false
            pending = ' '

            // হসন্ত + ZWNJ  ( ,, )
            if (c == ',' && src.startsWith(",,", i)) {
                out.append(HASANTA).append(ZWNJ); consAfter = false; wordStart = false; i += 2; continue
            }

            if (c == 'q') {
                val nx = if (i + 1 < n) src[i + 1] else ' '
                if (nx == 'w') { out.append("ঃ"); consAfter = false; wordStart = false; i += 2; continue }
                if (nx == '.' || nx == ',') {
                    out.append(if (nx == '.') '?' else '!')
                    consAfter = false; wordStart = true; i += 2; continue
                }
                if (nx == 'q') { out.append("ঁ"); consAfter = false; wordStart = false; i += 2; continue }
                if (nx == 'h') {
                    lStart = out.length; lTok = "h"; lRa = false
                    out.append("হ"); consAfter = true; wordStart = false; last = "h"; lastLetter = 'হ'; i += 2; continue
                }
                if (nx == 'z' && !(consAfter && endsWithPlainRa(out))) {
                    lStart = out.length; lTok = "z"; lRa = false
                    out.append("য"); consAfter = true; wordStart = false; last = "z"; lastLetter = 'য'; i += 2; continue
                }
                if (nx == 's' && !(consAfter && last == "s")) {
                    if (consAfter && wasShift) out.append(HASANTA)
                    lStart = out.length; lTok = "sss"; lRa = false
                    out.append("ষ"); consAfter = true; wordStart = false; last = "sss"; lastLetter = 'ষ'; i += 2; continue
                }
                if (nx == 'x') {
                    out.append("ক").append(HASANTA).append("স")
                    consAfter = true; wordStart = false; last = "x"; lastLetter = 'স'; i += 2; continue
                }
                if (nx == 'n' && !(consAfter && (last == "n" || last == "nn"))) {
                    // ঞ-ও তালিকা মানে; শিফট জোর করে যুক্ত করে
                    if (consAfter && (wasShift || (greedy && joinable(lastLetter, 'ঞ')))) out.append(HASANTA)
                    lStart = out.length; lTok = "nnn"; lRa = false
                    out.append("ঞ"); consAfter = true; wordStart = false; last = "nnn"; lastLetter = 'ঞ'; i += 2; continue
                }
                if (nx == 'r' && !(consAfter && last == "r")) {
                    // qr = ঋ (শুরুতে/স্বরের পরে) বা ৃ (ব্যঞ্জনের পরে); র-এর পরে q শুধু বিভাজক
                    out.append(if (consAfter) "ৃ" else "ঋ")
                    consAfter = false; wordStart = false; i += 2; continue
                }
                if (consAfter) {
                    if (nx == 'z' && endsWithPlainRa(out)) {
                        out.append(ZWJ).append(HASANTA).append("য")
                        consAfter = true; wordStart = false; last = "z"; lastLetter = 'য'; i += 2; continue
                    }
                    val after = if (i + 2 < n) src[i + 2] else ' '
                    if (src.startsWith("qt", i) && after != 'h' && after != 't' && last != "t" && last != "tt") {
                        out.append("ৎ"); consAfter = false; wordStart = false; i += 2; continue
                    }
                    // অন্য q: কিছু আসে না, শুধু টোকেন ভাগ করে (tqt = তত)
                    i += 1
                    qBreak = true
                    if (i == n) pending = 'q'
                    continue
                }
                if (src.startsWith("qt", i)) { out.append("ৎ"); wordStart = false; i += 2; continue }
                if (src.startsWith("qk", i)) { out.append("৳"); wordStart = false; i += 2; continue }
                if (i + 1 < n && isLetter(src[i + 1])) { qBreak = true; i += 1; continue } // q + অন্য অক্ষর: q চলে যায়
                out.append('q'); consAfter = false; wordStart = false; i += 1
                if (i == n) pending = 'Q'
                continue
            }

            // সবচেয়ে বড় টোকেন আগে (৩ → ২ → ১)
            var matched = false
            var len = minOf(3, n - i)
            while (len >= 1) {
                val tok = src.substring(i, i + len)

                // w: ব্যঞ্জনের পরে ব-ফলা, অন্যথায় (শুরুতে/স্বরের পরে) ও
                if (tok == "w") {
                    if (consAfter) {
                        out.append(HASANTA).append("ব"); consAfter = true; last = "w"; lastLetter = 'ব'
                    } else {
                        out.append("ও"); consAfter = false
                    }
                    wordStart = false
                    i += 1; matched = true; break
                }

                // ng = ং (অনুস্বার)
                if (tok == "ng") {
                    out.append("ং"); consAfter = false; wordStart = false
                    i += 2; matched = true; break
                }

                val cons = consonants[tok]
                if (cons != null) {
                    val startsWord = wordStart && !consAfter
                    val wasPhala = phala
                    phala = false
                    if (tok == "z" && !consAfter && !wasQ && out.isNotEmpty() &&
                        out[out.length - 1] in '\u0985'..'\u0994' &&
                        (greedy || wasShift)
                    ) {
                        // স্বরবর্ণের সাথে য-ফলা: অ্যা = oza  (ভাঙতে q: অযথা = oqzotha)
                        out.append(HASANTA).append(cons)
                        phala = true
                    } else if (tok == "z" && consAfter && wasRa && (greedy || wasShift)) {
                        // র‍্য : র + ZWJ + হসন্ত + য
                        out.append(ZWJ).append(HASANTA).append(cons)
                        phala = true
                    } else {
                        // গ্রিডি অংশ: শুধু তালিকার ১৬৩ জোড়া নিজে যুক্ত হয়; b ও y নিজে যুক্ত হয় না;
                        // র-ফলা/য-ফলার পরে কিছু যুক্ত হয় না (র-ফলার পরে য-ফলা চলে: ব্র্যান; য়্য চলে)।
                        // শিফট ট্যাপ জোর করে যুক্ত করে। য় কখনো ফলা হয় না।
                        var joined = false
                        if (consAfter && tok != "y" && (wasShift || (greedy && tok != "b" && (
                                (tok == "z" && last == "y") ||
                                    ((!wasPhala || (tok == "z" && lastLetter == 'র')) && joinable(lastLetter, cons[0]))))))
                        {
                            out.append(HASANTA)
                            joined = true
                        }
                        if (!joined) { lStart = out.length; lTok = tok; lRa = (tok == "z" && wasRa) }
                        out.append(cons)
                        phala = joined && (cons[0] == 'র' || cons[0] == 'য')
                    }
                    lastLetter = cons[cons.length - 1]
                    raInitial = tok == "r" && startsWord
                    last = tok
                    consAfter = true; wordStart = false
                    i += len; matched = true; break
                }
                val v = vowels[tok]
                if (v != null) {
                    if (consAfter && !wasShift) { lVowSt = out.length; lVowInd = v.first }
                    out.append(if (consAfter && !wasShift) v.second else v.first)
                    // ব্যঞ্জনের পরে একা 'o' অদৃশ্য — শেষ কী হলে মার্কার দেখাই
                    if (tok == "o" && consAfter && i + len == n) pending = 'o'
                    consAfter = false; wordStart = false
                    i += len; matched = true; break
                }
                len--
            }
            if (matched) continue

            // অঙ্ক, দাঁড়ি, অন্য সব অক্ষর
            val d = digit(c)
            when {
                d != null -> out.append(d)
                c == '.' -> out.append('।')
                else -> out.append(input[i])
            }
            consAfter = false; wordStart = true; i += 1
        }

        var markerIndex = -1
        var marker = ' '
        if (preview) {
            when (pending) {
                'o', 'q', 's' -> { markerIndex = out.length; marker = pending; out.append(pending) }
                'Q' -> { markerIndex = out.length - 1; marker = 'q' }
            }
        }
        return Preview(out.toString(), markerIndex, marker, consAfter)
    }

    /** শব্দের শেষে ব্যঞ্জন আছে? (পুরনো API: শিফট দিয়ে জোর-যুক্ত করা যায় কি) */
    fun canForceJoin(buffer: String): Boolean = run(buffer, false).endsWithConsonant

    // ---------- পরের কী-এর ইঙ্গিত ----------

    private fun isCons(c: Char) = c in '\u0995'..'\u09B9' || c == '\u09DC' || c == '\u09DD' || c == '\u09DF'
    private fun isSign(c: Char) = c in '\u09BE'..'\u09CC' || c == '\u0981' || c == '\u0982' || c == '\u0983'

    /** লেখার শেষ "অক্ষর-একক": যুক্তাক্ষর + কার/চিহ্ন (যেমন ক্ষ্মা, কা, র‍্য)। */
    fun tailUnit(s: String): String {
        if (s.isEmpty()) return ""
        var i = s.length
        while (i > 0 && isSign(s[i - 1])) i--
        var j = i
        while (true) {
            var k = j
            if (k > 0 && s[k - 1] == NUKTA[0]) k--
            if (k > 0 && isCons(s[k - 1])) k-- else break
            j = k
            if (j > 0 && s[j - 1] == HASANTA[0]) {
                var m = j - 1
                if (m > 0 && s[m - 1] == '\u200D') m--
                if (m > 0 && (isCons(s[m - 1]) || s[m - 1] == NUKTA[0] || s[m - 1] in '\u0985'..'\u0994')) { j = m; continue }
            }
            break
        }
        val unit = when {
            j < i -> s.substring(j)
            i < s.length -> s.substring(i)
            else -> s.substring(s.length - 1)
        }
        return if (unit.length > 7) unit.substring(unit.length - 7) else unit
    }

    /** কী-র গায়ের ইঙ্গিত; [green] = চলমান 'q'-এর কারণে যে ফল বদলাচ্ছে */
    class Hint(val text: String, val green: Boolean)

    private fun hintFor(buffer: String, c: Char): String? {
        val base = convert(buffer)
        val next = convert(buffer + c)
        if (next == base) return null
        val h = tailUnit(next)
        return if (h.isEmpty() || h == c.toString()) null else h
    }

    /**
     * বাফারের পর প্রতিটি অক্ষর-কী চাপলে কী আসবে — কী-র গায়ে দেখানোর জন্য।
     * যে কী কিছু বদলায় না (যেমন ব্যঞ্জনের পরে o/q) তার জন্য এন্ট্রি থাকে না।
     * 'q' সক্রিয় থাকলে (শেষ কী q) যেসব কী-র ফল q-এর কারণে বদলায় সেগুলো green।
     */
    fun nextHints(buffer: String): Map<Char, Hint> {
        val b = buffer.lowercase()
        val p = preview(b)
        val qActive = p.markerIndex >= 0 && p.marker == 'q' && b.endsWith("q")
        val without = if (qActive) b.dropLast(1) else null
        val res = HashMap<Char, Hint>()
        for (c in 'a'..'z') {
            val h = hintFor(b, c)
            if (h == null) continue
            val green = without != null && hintFor(without, c) != h
            res[c] = Hint(h, green)
        }
        // q সক্রিয় থাকলে ',' = !  এবং  '।' (.) = ?
        if (qActive) {
            res[','] = Hint("!", true)
            res['.'] = Hint("?", true)
        }
        return res
    }
}

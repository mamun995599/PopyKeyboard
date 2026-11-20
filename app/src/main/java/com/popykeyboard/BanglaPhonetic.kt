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

    /** টাইপ চলাকালীন দেখানোর ফল। [markerIndex] >= 0 হলে ওই অবস্থানের অক্ষরটি রঙিন মার্কার ('o' লাল, 'q' সবুজ)। */
    class Preview(val text: String, val markerIndex: Int, val marker: Char, val endsWithConsonant: Boolean = false)

    /** ল্যাটিন লেখা → বাংলা (চূড়ান্ত, মার্কার ছাড়া)। */
    fun convert(input: String): String = run(input, false).text

    /**
     * টাইপ চলাকালীন composing টেক্সট। শেষ কী যদি যুক্তাক্ষর-বাধা 'o' (ব্যঞ্জনের পর) বা
     * ভাঙার চিহ্ন 'q' হয়, তবে সেটি দৃশ্যমান মার্কার হিসেবে শেষে দেখায়; পরের অক্ষর এলে সরে যায়।
     */
    fun preview(input: String): Preview = run(input, true)

    private fun endsWithPlainRa(out: StringBuilder): Boolean =
        out.isNotEmpty() && out[out.length - 1] == 'র' &&
            (out.length < 2 || out[out.length - 2] != HASANTA[0])

    /**
     * ১৬৩টি ব্যঞ্জন-জোড়া যা নিজে থেকে যুক্ত হয় (প্রথম+দ্বিতীয়, হেক্স কোড-পয়েন্ট)।
     * বাকি সব জোড়া আলাদা থাকে; q বা ` (শিফট) দিলে জোর করে যুক্ত হয়। রেফ (র) সব ব্যঞ্জনের সাথে যুক্ত হয়।
     * উৎস: পিসি ভার্সনের clsPopyPhonetic.pas (conjunct_list_v2, ধারা ১+২)।
     */
    private const val PAIRS_HEX =
        "09950995,0995099F,099509A4,099509A6,099509A8,099509AE,099509AF,099509B0,099509B2," +
            "099509B7,099509B8,099609AF,099609B0,09970997,099709A7,099709A8,099709AE,099709AF," +
            "099709B0,099709B2,099809A8,099809AF,099809B0,09990995,09990996,09990997,09990998," +
            "099909AE,099909AF,099909B0,099A099A,099A099B,099A099E,099A09AF,099B09AF,099B09B0," +
            "099C099C,099C099D,099C099E,099C09AF,099C09B0,099D09AF,099E099A,099E099B,099E099C," +
            "099E099D,099F099F,099F09AE,099F09AF,099F09B0,09A009AF,09A109A1,09A109AE,09A109AF," +
            "09A109B0,09A209AF,09A209B0,09A3099F,09A309A0,09A309A1,09A309A2,09A309AE,09A309AF," +
            "09A409A8,09A409AE,09A409AF,09A409B0,09A509AF,09A509B0,09A60997,09A60998,09A609AD," +
            "09A609AE,09A609AF,09A609B0,09A709A8,09A709AE,09A709AF,09A709B0,09A8099F,09A809A0," +
            "09A809A1,09A809A4,09A809A5,09A809A6,09A809A7,09A809AE,09A809AF,09A809B8,09AA099F," +
            "09AA09A4,09AA09A5,09AA09A8,09AA09AA,09AA09AF,09AA09B0,09AA09B2,09AA09B8,09AB099F," +
            "09AB09AF,09AB09B0,09AB09B2,09AC099C,09AC09A6,09AC09A7,09AC09AF,09AC09B0,09AC09B2," +
            "09AD09AF,09AD09B0,09AD09B2,09AE09A8,09AE09AA,09AE09AB,09AE09AD,09AE09AE,09AE09AF," +
            "09AE09B0,09AE09B2,09AF09AF,09B20995,09B20997,09B2099F,09B209A1,09B209AA,09B209AB," +
            "09B209AE,09B209AF,09B209B2,09B6099A,09B6099B,09B609A4,09B609A8,09B609AE,09B609AF," +
            "09B609B0,09B609B2,09B70995,09B7099F,09B709A0,09B709A3,09B709AA,09B709AB,09B709AE," +
            "09B709AF,09B80995,09B80996,09B8099F,09B809A4,09B809A5,09B809A8,09B809AA,09B809AB," +
            "09B809AE,09B809AF,09B809B0,09B809B2,09B909A3,09B909A8,09B909AE,09B909AF,09B909B0," +
            "09B909B2"

    private val joinPairs: HashSet<Int> = HashSet<Int>().also { set ->
        for (p in PAIRS_HEX.split(',')) {
            if (p.length == 8) set.add((p.substring(0, 4).toInt(16) shl 16) or p.substring(4, 8).toInt(16))
        }
    }

    private const val RA = 'র'
    private const val YA = 'য'

    /** ব্যঞ্জন a + হসন্ত + b নিজে থেকে যুক্ত হয় কি? র (রেফ) সব ব্যঞ্জনের সাথে যুক্ত হয়। */
    private fun joinablePair(a: Char, b: Char): Boolean =
        a == RA || joinPairs.contains((a.code shl 16) or b.code)

    private fun run(input: String, preview: Boolean): Preview {
        // শুধু A–Z ছোট হাতের করা হয় (দৈর্ঘ্য অপরিবর্তিত থাকে)
        val src = buildString(input.length) {
            for (ch in input) append(if (ch in 'A'..'Z') (ch + 32) else ch)
        }
        val out = StringBuilder()
        // consAfter = সর্বশেষ আউটপুট একটি ব্যঞ্জন (কার/যুক্তাক্ষর নেওয়ার জন্য প্রস্তুত)
        var consAfter = false
        var wordStart = true
        // শব্দের শুরুর 'র' — এর পর z এলে রেফ নয়, র‍্য (য-ফলা) হয়
        var raInitial = false
        // স্বরের পরে q দিয়ে ভাঙা হয়েছে — পরের z এ য-ফলা হবে না (অযথা = oqzotha);
        // ব্যঞ্জনের পরে q দিলে পরের ব্যঞ্জন জোর করে যুক্ত হয়
        var qBreak = false
        // ` (শিফট) চাপা হয়েছে: পরের ব্যঞ্জন তালিকায় না থাকলেও যুক্ত হবে
        var force = false
        // সর্বশেষ ব্যঞ্জন-টোকেন (যেমন "t", "nn") — q-এর অর্থ ঠিক করতে লাগে
        var lastConsTok = ""
        var lastLetter = '\u0000'
        // সর্বশেষ ব্যঞ্জন র-ফলা/য-ফলা হলে তার পরে নিজে থেকে আর যুক্ত হয় না
        var phala = false
        // শেষ টোকেনটি মার্কার-যোগ্য হলে: 'o' / 'q' (আউটপুটে অদৃশ্য), 'Q' (আউটপুটে আক্ষরিক q আছে)
        var pending = ' '
        var i = 0

        while (i < src.length) {
            val c = src[i]
            val wasRaInitial = raInitial
            raInitial = false
            val wasQBreak = qBreak
            qBreak = false
            val wasForce = force
            force = false
            pending = ' '

            // হসন্ত + ZWNJ  ( ,, )
            if (c == ',' && src.startsWith(",,", i)) {
                out.append(HASANTA).append(ZWNJ); consAfter = false; wordStart = false; i += 2; continue
            }

            // ` = জোর-যুক্ত: ব্যঞ্জনের পর হসন্ত বসে, পরের ব্যঞ্জন তালিকায় না থাকলেও যুক্ত হয়
            if (c == '`' && consAfter) {
                if (!wasForce) out.append(HASANTA) // পরপর দ্বিতীয় ` কিছু বদলায় না
                force = true
                i += 1; continue
            }

            if (c == 'q') {
                val nx = if (i + 1 < src.length) src[i + 1] else '\u0000'

                // qw = ঃ (বিসর্গ)
                if (nx == 'w') {
                    out.append("ঃ"); consAfter = false; wordStart = false; i += 2; continue
                }
                // q. = ?   q, = !
                if (nx == '.' || nx == ',') {
                    out.append(if (nx == '.') '?' else '!')
                    consAfter = false; wordStart = true; i += 2; continue
                }
                // qq = ঁ (চন্দ্রবিন্দু) — ব্যঞ্জনের পরেও (চঁআ = cqqa)
                if (nx == 'q') {
                    out.append("ঁ"); consAfter = false; wordStart = false; i += 2; continue
                }
                // স্বাধীন বর্ণ, জোড়া লাগে না: qh = হ, qz = য (রেফ-য ছাড়া), qs = ষ, qx = ক্স
                if (nx == 'h') {
                    out.append("হ"); lastLetter = 'হ'; consAfter = true; wordStart = false
                    lastConsTok = "h"; i += 2; continue
                }
                if (nx == 'z' && !(consAfter && endsWithPlainRa(out))) {
                    out.append("য"); lastLetter = YA; consAfter = true; wordStart = false
                    lastConsTok = "z"; i += 2; continue
                }
                // qs = ষ (স্বাধীন); স-এর ঠিক পরে q ভাঙার চিহ্ন (sqs = স্স)
                if (nx == 's' && !(consAfter && lastConsTok == "s")) {
                    out.append("ষ"); lastLetter = 'ষ'; consAfter = true; wordStart = false
                    lastConsTok = "sss"; i += 2; continue
                }
                if (nx == 'x') {
                    out.append("ক").append(HASANTA).append("স")
                    lastLetter = 'স'; consAfter = true; wordStart = false
                    lastConsTok = "x"; i += 2; continue
                }
                // q + স্বর = স্বাধীন স্বর, ব্যঞ্জনের পরেও (qa = আ, qi = ই, qoo = ও)
                var qVowel = false
                var vl = minOf(2, src.length - i - 1)
                while (vl >= 1) {
                    val vt = src.substring(i + 1, i + 1 + vl)
                    val vv = if (vt == "rrr") null else vowels[vt]
                    if (vv != null) {
                        out.append(vv.first)
                        consAfter = false; wordStart = false; i += 1 + vl
                        qVowel = true
                        break
                    }
                    vl--
                }
                if (qVowel) continue
                // qn = ঞ (nnn-এর মতোই, যুক্ত হয়)। ব্যতিক্রম: ন/ণ-এর ঠিক পরে q ভাঙার চিহ্ন (ন্ন = nqn)
                if (nx == 'n' && !(consAfter && (lastConsTok == "n" || lastConsTok == "nn"))) {
                    if (consAfter) out.append(HASANTA)
                    out.append("ঞ"); lastLetter = 'ঞ'; consAfter = true; wordStart = false
                    lastConsTok = "nnn"; i += 2; continue
                }
                // qr = ঋ (শুরুতে/স্বরের পরে) বা ৃ (ব্যঞ্জনের পরে); র-এর ঠিক পরে q ভাঙার চিহ্ন (rqr = র্র)
                if (nx == 'r' && !(consAfter && lastConsTok == "r")) {
                    out.append(if (consAfter) "ৃ" else "ঋ")
                    consAfter = false; wordStart = false; i += 2; continue
                }
                if (consAfter) {
                    // rqz = র‍্য (র-এর সাথে য-ফলা, রেফ নয়)
                    if (nx == 'z' && endsWithPlainRa(out)) {
                        out.append(ZWJ).append(HASANTA).append("য")
                        lastLetter = YA; consAfter = true; wordStart = false
                        lastConsTok = "z"; i += 2; continue
                    }
                    val after = if (i + 2 < src.length) src[i + 2] else ' '
                    // qt = ৎ ব্যঞ্জনের পরেও (জগৎ = jogqt)। ব্যতিক্রম: ত/ট-এর ঠিক পরে q ভাঙার চিহ্ন
                    // (ত্ত = tqt, ট্ট = ttqtt) এবং th/tt (ত্থ = tqth)
                    if (src.startsWith("qt", i) && after != 'h' && after != 't' &&
                        lastConsTok != "t" && lastConsTok != "tt"
                    ) {
                        out.append("ৎ"); consAfter = false; wordStart = false; i += 2; continue
                    }
                    // ব্যঞ্জনের পরে q = ভাঙার চিহ্ন (টোকেন ভাগ করে), পরের ব্যঞ্জন জোর করে যুক্ত হয়
                    qBreak = true
                    i += 1
                    if (i == src.length) pending = 'q'
                    continue
                }
                // স্বরের পরে / শুরুতে: বিশেষ চিহ্ন
                when {
                    src.startsWith("qt", i) -> { out.append("ৎ"); wordStart = false; i += 2; continue }
                    src.startsWith("qk", i) -> { out.append("৳"); wordStart = false; i += 2; continue }
                }
                // স্বর ভাঙতে q (boqi = বই)
                if (!wordStart && i + 1 < src.length && isLetter(src[i + 1])) {
                    qBreak = true
                    i += 1; continue
                }
                // শুধু q: q-ই থাকে
                out.append('q'); consAfter = false; wordStart = false; i += 1
                if (i == src.length) pending = 'Q'
                continue
            }

            // সবচেয়ে বড় টোকেন আগে (৩ → ২ → ১)
            var matched = false
            var len = minOf(3, src.length - i)
            while (len >= 1) {
                val tok = src.substring(i, i + len)

                // w: ব্যঞ্জনের পরে ব-ফলা, অন্যথায় (শুরুতে/স্বরের পরে) ও
                if (tok == "w") {
                    if (consAfter) {
                        out.append(HASANTA).append("ব"); lastLetter = 'ব'; consAfter = true; lastConsTok = "w"
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
                    val l = out.length
                    val wasPhala = phala
                    phala = false
                    if (tok == "z" && !consAfter && !wasQBreak && l >= 1 &&
                        out[l - 1] in '\u0985'..'\u0994'
                    ) {
                        // স্বরবর্ণের সাথে য-ফলা: অ্যা = oza  (ভাঙতে q: অযথা = oqzotha)
                        out.append(HASANTA).append(cons)
                        phala = true
                    } else if (tok == "z" && consAfter && wasRaInitial) {
                        // র‍্য : র + ZWJ + হসন্ত + য
                        out.append(ZWJ).append(HASANTA).append(cons)
                        phala = true
                    } else {
                        // হসন্ত বসে: শুধু ১৬৩ জোড়ায়, অথবা q (qBreak) দিলে; ` দিলে হসন্ত আগেই বসেছে।
                        // র-ফলা/য-ফলার পরে শুধু q বা ` যুক্ত করে (র-ফলার পর য-ফলা চলে: brzan)।
                        // b ও y নিজে থেকে কখনো যুক্ত হয় না (ব-ফলা = w)।
                        var joined = wasForce
                        if (consAfter && !wasForce && tok != "b" && tok != "y" &&
                            (wasQBreak ||
                                (tok == "z" && lastConsTok == "y") || // য়-এর পর য-ফলা চলে (yz = য়্য)
                                ((!wasPhala || (tok == "z" && lastLetter == RA)) &&
                                    joinablePair(lastLetter, cons[0])))
                        ) {
                            out.append(HASANTA)
                            joined = true
                        }
                        if (tok == "y" && wasForce && out.isNotEmpty() && out[out.length - 1] == HASANTA[0]) {
                            out.setLength(out.length - 1) // য় কখনো ফলা হয় না: জোড়ক বাদ
                            joined = false
                        }
                        out.append(cons)
                        phala = joined && (cons[0] == RA || cons[0] == YA)
                    }
                    lastLetter = cons[cons.length - 1]
                    raInitial = tok == "r" && startsWord
                    lastConsTok = tok
                    consAfter = true; wordStart = false
                    i += len; matched = true; break
                }
                val v = vowels[tok]
                if (v != null) {
                    out.append(if (consAfter) v.second else v.first)
                    // ব্যঞ্জনের পরে একা 'o' অদৃশ্য — শেষ কী হলে মার্কার দেখাই
                    if (tok == "o" && consAfter && i + len == src.length) pending = 'o'
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
                'o', 'q' -> { markerIndex = out.length; marker = pending; out.append(pending) }
                'Q' -> { markerIndex = out.length - 1; marker = 'q' }
            }
        }
        return Preview(out.toString(), markerIndex, marker, consAfter)
    }

    /** শব্দের শেষে ব্যঞ্জন আছে (শিফট/` দিয়ে জোর-যুক্ত করা যায়)? */
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

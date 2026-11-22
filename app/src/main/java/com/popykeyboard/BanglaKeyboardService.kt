package com.popykeyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * পপি কিবোর্ড (IME) সার্ভিস।
 *
 * বাংলা মোডে প্রতিটি অক্ষর একটি বাফারে জমে; প্রতিবার পুরো বাফার [BanglaPhonetic.convert] দিয়ে
 * বাংলায় বদলে composing text হিসেবে দেখানো হয়। স্পেস/এন্টার/চিহ্নে শব্দটি পাকা হয়।
 */
class BanglaKeyboardService : InputMethodService(), KeyboardRoot.Listener {

    private var root: KeyboardRoot? = null
    private val buffer = StringBuilder()

    private var bangla = true
    private var passwordField = false
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L

    private val prefs by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }

    private var appliedHeight = 100
    private var appliedPreview = false
    private var appliedAuto = true

    // অটো-কারেকশন ও সাজেশন (সেটিংসে বন্ধ করা যায়; ডিফল্টে চালু)
    private var autoCorrect = true

    override fun onCreateInputView(): View {
        appliedHeight = prefs.getInt("height_pct", 100)
        appliedPreview = prefs.getBoolean(KeyboardRoot.PREF_PREVIEW, false)
        appliedAuto = prefs.getBoolean(KeyboardRoot.PREF_AUTO, true)
        bangla = prefs.getBoolean("bangla", true)
        Feedback.prepare(this) // কী-চাপার শব্দ আগে থেকে লোড
        val view = KeyboardRoot(this, this)
        view.setLanguage(bangla)
        root = view
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        Feedback.release()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        buffer.clear()
        autoCorrect = prefs.getBoolean(KeyboardRoot.PREF_AUTO, true)
        BanglaPhonetic.greedy = prefs.getBoolean(KeyboardRoot.PREF_GREEDY, true)
        if (autoCorrect) {
            PopyDictionary.preload(onReady = {
                // তালিকা লোড শেষ হলে চলমান শব্দের সাজেশন সঙ্গে সঙ্গে দেখাই
                android.os.Handler(android.os.Looper.getMainLooper()).post { updateSuggestions() }
            }) { assets.open("popy_words.dat") }
        }
        // সেটিংসে উচ্চতা/প্রিভিউ/সাজেশন বদলালে কিবোর্ড নতুন করে বানাই
        if (root != null && (prefs.getInt("height_pct", 100) != appliedHeight ||
                prefs.getBoolean(KeyboardRoot.PREF_PREVIEW, false) != appliedPreview ||
                autoCorrect != appliedAuto)
        ) {
            setInputView(onCreateInputView())
        }

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        passwordField = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        val wantsLatin = cls != InputType.TYPE_CLASS_TEXT || passwordField ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI

        // ইউজারের পছন্দ মনে রাখা হয়; তবে নম্বর/ইমেইল/পাসওয়ার্ড ফিল্ডে ইংরেজি
        bangla = prefs.getBoolean("bangla", true) && !wantsLatin
        root?.apply {
            resetToLetters()
            setLanguage(bangla)
            setEnterLabel(enterLabelFor(info))
        }
        refreshShift()
        updateHints()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        commitComposing()
    }

    /** ইউজার কার্সর সরালে চলমান composing বাদ দিই */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (buffer.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            buffer.clear()
            currentInputConnection?.finishComposingText()
            updateHints()
        }
    }

    // ---------- সাহায্যকারী ----------

    private fun enterLabelFor(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return KeyView.ENTER_ICON
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "যান"
            EditorInfo.IME_ACTION_SEARCH -> "খুঁজুন"
            EditorInfo.IME_ACTION_SEND -> "পাঠান"
            EditorInfo.IME_ACTION_NEXT -> "পরের"
            EditorInfo.IME_ACTION_DONE -> "শেষ"
            else -> KeyView.ENTER_ICON
        }
    }

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        val p = BanglaPhonetic.preview(buffer.toString())
        val shown: CharSequence = if (p.markerIndex >= 0) {
            // o = লাল, q = সবুজ, s (শিফট) = নীল মার্কার; পরের অক্ষর এলে নিজে সরে যায়
            SpannableString(p.text).apply {
                val color = when (p.marker) { 'o' -> MARK_RED; 's' -> MARK_BLUE; else -> MARK_GREEN }
                setSpan(
                    ForegroundColorSpan(color), p.markerIndex, p.markerIndex + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else p.text
        ic.setComposingText(shown, 1)
        updateHints()
    }

    /** কী-গুলোর গায়ে "এরপর এটা চাপলে কী আসবে" ইঙ্গিত */
    private fun updateHints() {
        val r = root ?: return
        r.setTyped(if (bangla) buffer.toString() else "")
        if (!bangla || buffer.isEmpty()) r.setHints(null)
        else r.setHints(BanglaPhonetic.nextHints(buffer.toString()))
        updateSuggestions()
    }

    /**
     * সাজেশন বার: প্রথমে যা টাইপ হয়েছে, তারপর ডিকশনারির সর্বোচ্চ ৮টি শব্দ (পিসির মতো)।
     * তালিকায় কিছু না মিললে বার ফাঁকা।
     */
    private fun updateSuggestions() {
        val r = root ?: return
        if (!autoCorrect || !bangla || buffer.isEmpty()) {
            r.setSuggestions(emptyList()); return
        }
        if (!PopyDictionary.isReady()) {
            val err = PopyDictionary.lastError
            r.setSuggestionNote(
                if (PopyDictionary.isLoading()) "ডিকশনারি লোড হচ্ছে…"
                else if (err.isNotEmpty()) "ডিকশনারি লোড হয়নি: $err"
                else "ডিকশনারি তৈরি নয়"
            )
            return
        }
        val raw = BanglaPhonetic.convert(buffer.toString())
        val sug = PopyDictionary.suggestions(raw, 8)
        if (sug.isEmpty()) r.setSuggestionNote("কোনো মিল নেই")
        else r.setSuggestions(listOf(raw) + sug.filter { it != raw })
    }

    /** [fix] = true হলে (স্পেস/এন্টার) অটো-কারেকশন চালু থাকলে ডিকশনারির বানানে বদলায় */
    private fun commitComposing(fix: Boolean = false) {
        if (buffer.isEmpty()) return
        // মার্কার (o/q) বাদ দিয়ে চূড়ান্ত লেখা পাকা করি
        val raw = BanglaPhonetic.convert(buffer.toString())
        val finalText = if (fix && autoCorrect) PopyDictionary.correct(raw) else raw
        currentInputConnection?.apply {
            setComposingText(finalText, 1)
            finishComposingText()
        }
        buffer.clear()
        updateHints()
    }

    /** ইংরেজি মোডে বাক্যের শুরুতে স্বয়ংক্রিয় বড় হাতের অক্ষর */
    private fun refreshShift() {
        if (bangla) {
            // শিফট বাংলায় শুধু চোখে দেখার (বড়/ছোট হরফ); নিজে থেকে বদলায় না
        } else if (shift != ShiftState.CAPS) {
            val info = currentInputEditorInfo
            val caps = if (info != null && info.inputType != InputType.TYPE_NULL) {
                currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
            } else 0
            shift = if (caps != 0) ShiftState.ON else ShiftState.OFF
        }
        root?.setShift(shift)
    }

    // ---------- KeyboardRoot.Listener ----------

    override fun onLetter(ch: Char) {
        if (bangla) {
            if (ch == 'q' && buffer.endsWith("qq")) return // q সর্বোচ্চ পরপর ২টি
            buffer.append(ch)
            updateComposing()
        } else {
            val out = if (shift != ShiftState.OFF) ch.uppercaseChar() else ch
            currentInputConnection?.commitText(out.toString(), 1)
            if (shift == ShiftState.ON) refreshShiftAfterLetter()
        }
    }

    private fun refreshShiftAfterLetter() {
        shift = ShiftState.OFF
        root?.setShift(shift)
    }

    /** ':' ও ',' — বাংলায় বাফারে যায় (:: = ঃ, ,, = হসন্ত) */
    override fun onCompose(text: String) {
        if (bangla) {
            val afterQ = buffer.endsWith("q") && text == ","
            buffer.append(text)
            if (afterQ) commitComposing() else updateComposing() // q, = !
        } else {
            onText(text)
        }
    }

    override fun onText(text: String) {
        if (bangla && text == "।" && buffer.endsWith("q")) { // q। = ?
            buffer.append('.')
            commitComposing()
            refreshShift()
            return
        }
        commitComposing()
        currentInputConnection?.commitText(text, 1)
        refreshShift()
    }

    /** সাজেশনে চাপ: শব্দটি বেছে নেওয়া বানানে বদলে পাকা হয় (পিসির মতো, স্পেস পড়ে না) */
    override fun onSuggestionPicked(text: String) {
        if (buffer.isEmpty()) return
        currentInputConnection?.apply {
            setComposingText(text, 1)
            finishComposingText()
        }
        buffer.clear()
        updateHints()
    }

    override fun onSpace() {
        commitComposing(fix = true)
        currentInputConnection?.commitText(" ", 1)
        refreshShift()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (buffer.isNotEmpty()) {
            // চোখে-না-পড়া অক্ষর (যেমন অদৃশ্য q) একসাথে মুছে যায়, যাতে প্রতি চাপে দৃশ্যমান পরিবর্তন হয়
            val before = BanglaPhonetic.preview(buffer.toString()).text
            do {
                buffer.deleteCharAt(buffer.length - 1)
            } while (buffer.isNotEmpty() && BanglaPhonetic.preview(buffer.toString()).text == before)
            if (buffer.isEmpty()) { ic.commitText("", 1); updateHints() } else updateComposing()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        refreshShift()
    }

    override fun onEnter() {
        commitComposing(fix = true)
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = info == null ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            currentInputConnection?.performEditorAction(action)
        }
        refreshShift()
    }

    /**
     * বাংলা মোডে শিফট (যুক্ত কী) ট্যাপ = "স্বয়ংক্রিয় মিলন নয়" টোকেন (SH)। একবারই কাজ করে (বারবার চাপলেও একটি),
     * শব্দের শুরুতে কিছু হয় না, Backspace দিলে মুছে যায়।
     *  • গ্রিডি চালু: ব্যঞ্জনের আগে জোর করে যুক্ত (t, শিফট, t = ত্ত; k, শিফট, f = ক্ফ), স্বরের আগে স্বাধীন স্বর
     *    (bo, শিফট, i = বই); নীল `s` মার্কার দেখা যায়।
     *  • গ্রিডি বন্ধ: পোস্টফিক্স — লেখা শেষ ব্যঞ্জনকে আগেরটির সাথে যুক্ত করে (km, শিফট = ক্ম),
     *    কারের পরে হলে কারকে স্বাধীন স্বর করে (ka, শিফট = কআ)।
     */
    private fun shiftTap() {
        if (buffer.isEmpty() || buffer.last() == BanglaPhonetic.SH) return
        buffer.append(BanglaPhonetic.SH)
        updateComposing()
    }

    override fun onShift() {
        if (bangla) { shiftTap(); return }
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.CAPS -> ShiftState.OFF
            shift == ShiftState.ON && now - lastShiftTap < 350 -> ShiftState.CAPS
            shift == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastShiftTap = now
        root?.setShift(shift)
    }

    override fun onToggleLanguage() {
        if (passwordField) return // পাসওয়ার্ড ফিল্ডে সবসময় ইংরেজি
        commitComposing()
        bangla = !bangla
        prefs.edit().putBoolean("bangla", bangla).apply()
        shift = ShiftState.OFF
        root?.setShift(shift)
        root?.setLanguage(bangla)
        refreshShift()
        updateHints()
    }

    override fun onLanguagePicker() {
        commitComposing()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onEmojiPicked(emoji: String) {
        commitComposing()
        currentInputConnection?.commitText(emoji, 1)
    }

    /**
     * ২ সেকেন্ড চেপে ধরার কমান্ড। চলমান বাংলা শব্দ আগে পাকা করা হয়, যাতে কপি/কাট ঠিক লেখার ওপর চলে।
     * কাট/কপি/পেস্ট/সব-নির্বাচন সরাসরি টেক্সট ফিল্ডে; আনডু/রিডু Ctrl+Z / Ctrl+Shift+Z দিয়ে।
     */
    override fun onEditCommand(cmd: Char) {
        commitComposing()
        val ic = currentInputConnection ?: return
        val name = KeyboardLayouts.holdCommands[cmd] ?: return
        when (cmd) {
            'x' -> ic.performContextMenuAction(android.R.id.cut)
            'c' -> ic.performContextMenuAction(android.R.id.copy)
            'v' -> ic.performContextMenuAction(android.R.id.paste)
            'a' -> ic.performContextMenuAction(android.R.id.selectAll)
            'z' -> sendCtrlKey(KeyEvent.KEYCODE_Z, withShift = false)
            'y' -> sendCtrlKey(KeyEvent.KEYCODE_Z, withShift = true) // Ctrl+Shift+Z = রিডু
            else -> return
        }
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    }

    private fun sendCtrlKey(keyCode: Int, withShift: Boolean) {
        val ic = currentInputConnection ?: return
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (withShift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private companion object {
        const val MARK_RED = 0xFFE53935.toInt()
        const val MARK_GREEN = 0xFF1E9E45.toInt()
        const val MARK_BLUE = 0xFF1E6FE5.toInt()
    }
}

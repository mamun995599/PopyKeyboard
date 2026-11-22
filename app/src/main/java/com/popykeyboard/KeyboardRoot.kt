package com.popykeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * কিবোর্ডের মূল ভিউ। অক্ষরের সারি + ইমোজি প্যানেল ধরে রাখে।
 * লজিক (কী চাপলে কী হবে) [Listener] এর মাধ্যমে সার্ভিসে যায়।
 */
@SuppressLint("ViewConstructor")
class KeyboardRoot(
    context: Context,
    private val listener: Listener,
) : FrameLayout(context), EmojiPanel.Listener {

    interface Listener {
        fun onLetter(ch: Char)
        fun onCompose(text: String)
        fun onText(text: String)
        fun onSpace()
        fun onBackspace()
        fun onEnter()
        fun onShift()
        fun onToggleLanguage()
        fun onLanguagePicker()
        fun onEmojiPicked(emoji: String)
        /** সাজেশন বারে একটি শব্দে চাপ */
        fun onSuggestionPicked(text: String)
        /** ২ সেকেন্ড চেপে ধরার এডিট কমান্ড: x কাট, c কপি, v পেস্ট, z আনডু, y রিডু, a সব নির্বাচন */
        fun onEditCommand(cmd: Char)
    }

    private val colors = KeyColors(context)
    private val density = resources.displayMetrics.density
    private val keyHeightPx: Int
    private val rowsContainer = LinearLayout(context)
    private val emojiPanel: EmojiPanel

    private var layer = Layer.LETTERS
    private var bangla = true
    private var shift = ShiftState.OFF
    private var enterLabel = KeyView.ENTER_ICON
    private var emojiShown = false
    private val letterViews = HashMap<Char, KeyView>()
    // নম্বর সারির ওপরে ইংরেজি অক্ষরের প্রিভিউ (সেটিংসে চালু করলে)
    private var previewText: TextView? = null
    // সাজেশন বার (অটো-কারেকশন চালু থাকলে): প্রথমে টাইপ করা শব্দ, তারপর ডিকশনারির শব্দ
    private var suggestRow: LinearLayout? = null
    // বাংলা অক্ষর-সারির ',' ও '।' কী (q চাপা থাকলে ! ও ? দেখায়)
    private val punctViews = HashMap<Char, KeyView>()
    private var hints: Map<Char, BanglaPhonetic.Hint>? = null

    init {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // সেটিংসে ঠিক করা উচ্চতা (৭০%–১৫০%)
        val pct = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
            .getInt("height_pct", 100).coerceIn(70, 150)
        keyHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, (if (landscape) 42f else 52f) * pct / 100f, resources.displayMetrics
        ).toInt()
        val baseHeight = keyHeightPx * 5 + (6 * density).toInt()
        val previewOn = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
            .getBoolean(PREF_PREVIEW, false)
        val autoOn = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTO, true)
        val stripPx = if (previewOn || autoOn) (34 * density).toInt() else 0
        val totalHeight = baseHeight + stripPx

        setBackgroundColor(colors.background)
        rowsContainer.orientation = LinearLayout.VERTICAL
        rowsContainer.setPadding(
            (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt()
        )
        addView(rowsContainer, LayoutParams(LayoutParams.MATCH_PARENT, baseHeight).apply { topMargin = stripPx })

        emojiPanel = EmojiPanel(context, colors, keyHeightPx, this)
        emojiPanel.visibility = View.GONE
        addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, baseHeight).apply { topMargin = stripPx })

        if (autoOn) {
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            scroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, stripPx))
            suggestRow = row
        }

        if (previewOn) {
            val pillColor = colors.key
            val pillText = colors.text
            val pillBg = GradientDrawable()
            pillBg.cornerRadius = 14 * density
            pillBg.setColor(pillColor)
            val tv = TextView(context)
            tv.setTextColor(pillText)
            tv.textSize = 16f
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.gravity = Gravity.CENTER
            tv.setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            tv.background = pillBg
            tv.visibility = View.GONE
            tv.minWidth = (48 * density).toInt()
            val row = suggestRow
            if (row != null) {
                // সাজেশন বার চালু থাকলে ইংরেজি অক্ষরের ঘর বারের প্রথমে বসে
                row.addView(
                    tv,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (28 * density).toInt())
                        .apply { marginEnd = (6 * density).toInt() },
                )
            } else {
                tv.visibility = View.INVISIBLE
                addView(
                    tv,
                    LayoutParams(LayoutParams.WRAP_CONTENT, (28 * density).toInt(), Gravity.CENTER_HORIZONTAL or Gravity.TOP)
                        .apply { topMargin = (3 * density).toInt() },
                )
            }
            previewText = tv
        }

        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalHeight)
        rebuild()
    }

    // ---------- সার্ভিস থেকে অবস্থা পরিবর্তন ----------

    fun setLanguage(bangla: Boolean) {
        if (this.bangla == bangla) return
        this.bangla = bangla
        emojiPanel.setLanguage(bangla)
        rebuild()
    }

    fun setShift(state: ShiftState) {
        if (shift == state) return
        shift = state
        rebuild()
    }

    /** বাংলা মোডে চলমান শব্দের পর প্রতিটি অক্ষর-কী চাপলে কী আসবে। null = স্থির ইঙ্গিত */
    fun setHints(map: Map<Char, BanglaPhonetic.Hint>?) {
        hints = map
        applyHints()
    }

    private fun applyHints() {
        val m = hints
        for ((c, v) in letterViews) v.setDynamic(m != null, m?.get(c)?.text, m?.get(c)?.green == true)
        // ',' ও '।' — ইঙ্গিত থাকলে (q সক্রিয়) সেটা, নইলে স্থির ইঙ্গিত
        for ((c, v) in punctViews) {
            val h = m?.get(c)
            v.setDynamic(h != null, h?.text, h?.green == true)
        }
    }

    /** চলমান শব্দের ইংরেজি অক্ষর (প্রিভিউ প্যানেল চালু থাকলে); ফাঁকা হলে প্যানেল লুকায় */
    fun setTyped(text: String) {
        val tv = previewText ?: return
        // ⇧ = শিফট (যুক্ত কী) ট্যাপের টোকেন
        tv.text = text.replace(BanglaPhonetic.SH, '\u21E7')
        tv.visibility = if (text.isEmpty()) {
            if (suggestRow != null) View.GONE else View.INVISIBLE
        } else View.VISIBLE
    }

    /** সাজেশন বারের শব্দ বদলায় (প্রথমটি = যা টাইপ হয়েছে); ফাঁকা তালিকা = বার খালি */
    fun setSuggestions(items: List<String>) {
        val row = suggestRow ?: return
        val keep = if (previewText != null) 1 else 0 // ইংরেজি অক্ষরের ঘর থাকলে সেটি রাখি
        while (row.childCount > keep) row.removeViewAt(row.childCount - 1)
        for (item in items) row.addView(suggestionChip(item), chipParams())
    }

    /** সাজেশন বারে সাধারণ (চাপ-অযোগ্য) বার্তা, যেমন ডিকশনারির অবস্থা */
    fun setSuggestionNote(text: String) {
        val row = suggestRow ?: return
        val keep = if (previewText != null) 1 else 0
        while (row.childCount > keep) row.removeViewAt(row.childCount - 1)
        row.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(colors.hint)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (28 * density).toInt()))
    }

    private fun chipParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (28 * density).toInt())
            .apply { marginEnd = (6 * density).toInt() }

    private fun suggestionChip(word: String): TextView {
        val bg = GradientDrawable()
        bg.cornerRadius = 14 * density
        bg.setColor(colors.key)
        return TextView(context).apply {
            text = word
            textSize = 17f
            setTextColor(colors.text)
            gravity = Gravity.CENTER
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            background = bg
            setOnClickListener { listener.onSuggestionPicked(word) }
        }
    }

    fun setEnterLabel(label: String) {
        if (enterLabel == label) return
        enterLabel = label
        rebuild()
    }

    /** নতুন ইনপুট ফিল্ডে ঢুকলে আবার অক্ষরের লেআউটে ফেরা */
    fun resetToLetters() {
        layer = Layer.LETTERS
        hideEmoji()
        rebuild()
    }

    fun showEmoji() {
        emojiShown = true
        emojiPanel.setLanguage(bangla)
        emojiPanel.onShown()
        rowsContainer.visibility = View.INVISIBLE
        emojiPanel.visibility = View.VISIBLE
    }

    fun hideEmoji() {
        emojiShown = false
        emojiPanel.visibility = View.GONE
        rowsContainer.visibility = View.VISIBLE
    }

    // ---------- সারি তৈরি ----------

    private fun rebuild() {
        rowsContainer.removeAllViews()
        letterViews.clear()
        punctViews.clear()
        val rows = KeyboardLayouts.rows(layer, bangla, shift, enterLabel)
        for (row in rows) {
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val longPress: ((Key) -> Unit)? = when {
                    key.act == Act.LANG -> { _ -> listener.onLanguagePicker() }
                    key.holdCmd != null -> { k -> listener.onEditCommand(k.holdCmd!!) }
                    else -> null
                }
                val view = KeyView(context, key, colors, ::handleKey, longPress)
                if (key.act == Act.LETTER) letterViews[key.text[0].lowercaseChar()] = view
                if (bangla && layer == Layer.LETTERS) {
                    if (key.act == Act.COMPOSE && key.label == ",") punctViews[','] = view
                    if (key.act == Act.TEXT && key.label == "।") punctViews['.'] = view
                }
                rowView.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight))
            }
            rowsContainer.addView(rowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyHeightPx))
        }
        applyHints()
    }

    private fun handleKey(key: Key) {
        when (key.act) {
            Act.LETTER -> listener.onLetter(key.text[0].lowercaseChar())
            Act.COMPOSE -> listener.onCompose(key.text)
            Act.TEXT -> listener.onText(key.text)
            Act.SPACE -> listener.onSpace()
            Act.BACKSPACE -> listener.onBackspace()
            Act.ENTER -> listener.onEnter()
            Act.SHIFT -> listener.onShift()
            Act.LANG -> listener.onToggleLanguage()
            Act.EMOJI -> showEmoji()
            Act.TO_SYMBOLS -> { layer = Layer.SYMBOLS_1; rebuild() }
            Act.TO_LETTERS -> { layer = Layer.LETTERS; rebuild() }
            Act.SYMBOL_PAGE -> {
                layer = if (layer == Layer.SYMBOLS_1) Layer.SYMBOLS_2 else Layer.SYMBOLS_1
                rebuild()
            }
            Act.SPACER -> Unit
        }
    }

    // ---------- EmojiPanel.Listener ----------

    override fun onEmojiPicked(emoji: String) = listener.onEmojiPicked(emoji)
    override fun onEmojiBackspace() = listener.onBackspace()
    override fun onEmojiSpace() = listener.onSpace()
    override fun onEmojiClose() = hideEmoji()

    companion object {
        const val PREF_PREVIEW = "preview_panel"
        const val PREF_AUTO = "auto_correct"
        /** পপি গ্রিডি মোড (ডিফল্ট চালু) */
        const val PREF_GREEDY = "popy_greedy"
    }
}

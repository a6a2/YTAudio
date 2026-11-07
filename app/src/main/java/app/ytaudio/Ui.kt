package app.ytaudio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Small code-built design system: dark cards, pill buttons, chips. Every tap gives a light haptic tick. */
object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun color(ctx: Context, id: Int) = ctx.getColor(id)

    /** IBM Plex Sans Arabic at [weight] (400 regular, 500 medium, 700 bold). */
    fun font(ctx: Context, weight: Int): Typeface {
        val family = ctx.resources.getFont(R.font.ibm_plex_sans_arabic)
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(family, weight, false)
        else Typeface.create(family, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun shape(fill: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = radiusPx
            if (strokeColor != null) setStroke(strokePx, strokeColor)
        }

    /** The dark rounded card background, for custom containers such as the update pop-up. */
    fun cardBackground(ctx: Context, radiusDp: Int) =
        shape(color(ctx, R.color.surface), dp(ctx, radiusDp).toFloat(), color(ctx, R.color.stroke), dp(ctx, 1))

    private fun ripple(ctx: Context, base: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

    // ---- page + containers ------------------------------------------------------------------

    fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 20)
        setPadding(p, dp(ctx, 28), p, dp(ctx, 32))
    }

    /** Scrolling page that keeps content clear of the (edge-to-edge) system bars. */
    fun page(ctx: Context, content: View) = ScrollView(ctx).apply {
        fitsSystemWindows = true
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** A rounded surface; add it with [LinearLayout.addGap]-style spacing handled here. */
    fun card(ctx: Context, parent: LinearLayout): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 18)
            setPadding(p, p, p, p)
            background = shape(color(ctx, R.color.surface), dp(ctx, 22).toFloat(), color(ctx, R.color.stroke), dp(ctx, 1))
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(ctx, 14) })
        return c
    }

    // ---- text -------------------------------------------------------------------------------

    fun text(ctx: Context, str: CharSequence, sp: Float, weight: Int = 400, muted: Boolean = false) =
        TextView(ctx).apply {
            text = str
            textSize = sp
            typeface = font(ctx, weight)
            setTextColor(color(ctx, if (muted) R.color.muted else R.color.text))
            setLineSpacing(0f, 1.15f)
        }

    fun title(ctx: Context, str: CharSequence) = text(ctx, str, 17f, 700).apply { setPadding(0, 0, 0, dp(ctx, 6)) }

    fun space(ctx: Context, parent: LinearLayout, heightDp: Int) =
        parent.addView(View(ctx), LinearLayout.LayoutParams(1, dp(ctx, heightDp)))

    // ---- buttons ----------------------------------------------------------------------------

    /** Full-width pill. [primary] = filled accent, otherwise a quiet tonal button. */
    fun button(ctx: Context, label: CharSequence, primary: Boolean = true, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = font(ctx, 500)
            stateListAnimator = null
            minHeight = dp(ctx, 50)
            setTextColor(color(ctx, if (primary) R.color.on_accent else R.color.text))
            background = ripple(ctx, shape(color(ctx, if (primary) R.color.accent else R.color.surface2), dp(ctx, 26).toFloat()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 10) }
            setOnClickListener { Haptics.click(ctx); onClick(it) }
        }

    /** Small selectable pill for speed / timer choices. */
    fun chip(ctx: Context, label: CharSequence, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        typeface = font(ctx, 500)
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 42)
        setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { Haptics.click(ctx); onClick() }
        style(this, false)
    }

    fun style(chip: TextView, selected: Boolean) {
        val ctx = chip.context
        chip.setTextColor(color(ctx, if (selected) R.color.on_accent else R.color.text))
        chip.background = ripple(ctx, shape(color(ctx, if (selected) R.color.accent else R.color.surface2), dp(ctx, 21).toFloat()))
    }

    /** A row of equally wide chips. */
    fun chipRow(ctx: Context, parent: LinearLayout, chips: List<TextView>) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { i, c ->
            row.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = if (i == 0) 0 else dp(ctx, 8)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
    }

    /** Version badge style pill (non-interactive). */
    fun badge(ctx: Context, label: CharSequence) = text(ctx, label, 12f, 500, muted = true).apply {
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        background = shape(color(ctx, R.color.surface2), dp(ctx, 14).toFloat())
    }

    // ---- switches and radio groups ----------------------------------------------------------

    fun switch(ctx: Context, parent: LinearLayout, label: CharSequence, hint: CharSequence?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(ctx).apply {
            text = label
            textSize = 15f
            typeface = font(ctx, 500)
            setTextColor(color(ctx, R.color.text))
            isChecked = checked
            minHeight = dp(ctx, 48)
            val on = color(ctx, R.color.accent)
            val off = color(ctx, R.color.muted)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(on, off))
            trackTintList = ColorStateList(states, intArrayOf((on and 0x00FFFFFF) or 0x66000000, color(ctx, R.color.stroke)))
            setOnCheckedChangeListener { _, v -> Haptics.click(ctx); onChange(v) }
        }
        parent.addView(sw, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (hint != null) parent.addView(text(ctx, hint, 13f, muted = true))
    }

    /** Single-choice list; [options] are (stored value, label). */
    fun radios(ctx: Context, parent: LinearLayout, options: List<Pair<String, CharSequence>>, current: String, onPick: (String) -> Unit) {
        val group = RadioGroup(ctx)
        options.forEach { (value, label) ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                typeface = font(ctx, 400)
                setTextColor(color(ctx, R.color.text))
                buttonTintList = ColorStateList.valueOf(color(ctx, R.color.accent))
                tag = value
                minHeight = dp(ctx, 46)
                isChecked = value == current
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        group.setOnCheckedChangeListener { g, id ->
            Haptics.click(ctx)
            onPick(g.findViewById<View>(id).tag as String)
        }
        parent.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}

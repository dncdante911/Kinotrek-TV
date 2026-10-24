package org.trackertv.player.player

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible

/** Side panel for tracks and player options. Back returns to the previous page, then closes it. */
internal class PlayerMenu(context: Context) : FrameLayout(context) {
    /** A row; without [action] it only shows information. */
    class Item(
        val label: String,
        val detail: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val action: (() -> Unit)? = null,
    )

    private class Page(val title: String, val items: List<Item>)

    private val pages = ArrayDeque<Page>()
    /** Where focus was before the menu opened, to return there on close. */
    private var restoreFocus: View? = null
    private val titleView = context.playerText(24f, bold = true)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var onClosed: (() -> Unit)? = null

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        setBackgroundColor(0x80000000.toInt())
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(32), dp(28), dp(24))
            setBackgroundColor(PlayerStyle.PANEL)
            addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, dp(6), 0, dp(6))
                addView(list)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addView(panel, LayoutParams(dp(420), -1, Gravity.END))
    }

    fun open(title: String, items: List<Item>) {
        if (!isOpen) restoreFocus = rootView.findFocus()
        pages.clear()
        push(title, items)
    }

    fun push(title: String, items: List<Item>) {
        pages.addLast(Page(title, items))
        visibility = VISIBLE
        render()
    }

    fun back() {
        pages.removeLastOrNull()
        if (pages.isEmpty()) close() else render()
    }

    fun close() {
        if (!isOpen) return
        pages.clear()
        visibility = GONE
        restoreFocus?.takeIf { it.isShown }?.requestFocus()
        restoreFocus = null
        onClosed?.invoke()
    }

    private fun render() {
        val page = pages.last()
        titleView.text = page.title
        list.removeAllViews()
        page.items.forEach { list.addView(row(it)) }
        val focus = page.items.indexOfFirst { it.checked }.takeIf { it >= 0 } ?: page.items.indexOfFirst { it.enabled }.coerceAtLeast(0)
        list.getChildAt(focus)?.let { row -> post { row.requestFocus() } }
    }

    private fun row(item: Item): View {
        val check = PathIcon(PlayerIcons.CHECK, PlayerStyle.ACCENT)
        val label = context.playerText(19f, if (item.enabled) PlayerStyle.TEXT else PlayerStyle.MUTED).apply { setShadowLayer(0f, 0f, 0f, 0) }
        label.text = item.label
        val detail = item.detail?.let { text ->
            context.playerText(15f, PlayerStyle.MUTED).apply {
                setShadowLayer(0f, 0f, 0f, 0)
                this.text = text
            }
        }
        val idle = rounded(0, dp(16).toFloat())
        val focused = rounded(PlayerStyle.FOCUS, dp(16).toFloat())
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(14), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            background = idle
            addView(ImageView(context).apply {
                setImageDrawable(check)
                visibility = if (item.checked) VISIBLE else INVISIBLE
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                detail?.let(::addView)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnFocusChangeListener { _, hasFocus ->
                background = if (hasFocus) focused else idle
                val dark = hasFocus
                label.setTextColor(if (dark) PlayerStyle.ON_FOCUS else if (item.enabled) PlayerStyle.TEXT else PlayerStyle.MUTED)
                detail?.setTextColor(if (dark) 0xFF3A4456.toInt() else PlayerStyle.MUTED)
                check.color = if (dark) PlayerStyle.ON_FOCUS else PlayerStyle.ACCENT
            }
            setOnClickListener { if (item.enabled) item.action?.invoke() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        }
    }
}

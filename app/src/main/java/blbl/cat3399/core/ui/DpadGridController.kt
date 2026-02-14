package blbl.cat3399.core.ui

import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R

/**
 * A reusable DPAD focus controller for grid-like RecyclerViews.
 *
 * It keeps focus inside the grid on RIGHT/DOWN edges (no outflow), optionally switches tabs on
 * LEFT/RIGHT edges, and supports "infinite scroll" UX: when DPAD_DOWN hits the bottom and triggers
 * loading more data, focus will move to the next row (same column) after new items are appended.
 */
internal class DpadGridController(
    private val recyclerView: RecyclerView,
    private val callbacks: Callbacks,
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * Called on every key event. Return false to disable all handling.
         * Typical usage in Fragments: `{ _binding != null && isResumed }`.
         */
        val isEnabled: () -> Boolean = { true },
        /**
         * When enabled, long-pressing DPAD_CENTER/ENTER triggers performLongClick() (repeatCount>0).
         * This is useful on TV where "OK" long press is expected to open a context action.
         */
        val enableCenterLongPressToLongClick: Boolean = false,
        /**
         * When DPAD_DOWN reaches the bottom but RecyclerView can still scroll, scroll by this
         * factor of the focused item height and then attempt to keep focus inside the grid.
         */
        val scrollOnDownEdgeFactor: Float = 0.8f,
        /**
         * If true, DPAD_RIGHT on the right edge will always be consumed (no outflow).
         *
         * Some layouts (e.g. a left-side vertical list that should move focus into a right-side
         * content panel) may want to allow RIGHT to bubble to the system focus-search.
         */
        val consumeRightEdge: Boolean = true,
        /**
         * If true, DPAD_UP at the top edge will always be consumed (even if callbacks fail to
         * move focus elsewhere). This helps prevent focus from "escaping" unexpectedly.
         */
        val consumeUpAtTopEdge: Boolean = true,
    )

    interface Callbacks {
        /**
         * Called when DPAD_UP is pressed on the first row and the list can't scroll further up.
         * Return true if you handled focus (e.g. focused TabLayout/back button).
         */
        fun onTopEdge(): Boolean

        /**
         * Called when DPAD_LEFT is pressed and focus-search can't find another item in the grid.
         *
         * Return true to consume the event (e.g. switched to previous tab), or false to allow the
         * event to bubble (e.g. let Activity handle entering the sidebar on the first tab).
         */
        fun onLeftEdge(): Boolean

        /**
         * Called when DPAD_RIGHT is pressed and focus-search can't find another item in the grid.
         * The key event will always be consumed (no outflow).
         */
        fun onRightEdge()

        /**
         * Whether more data could be loaded when reaching the bottom edge.
         * Note: return true even while a request is in-flight if you want DPAD_DOWN to "queue"
         * a focus jump to the next row when the request finishes.
         */
        fun canLoadMore(): Boolean

        /**
         * Trigger loading the next page (if applicable). Implementations should be idempotent.
         */
        fun loadMore()
    }

    private var installed: Boolean = false
    private var pendingFocusAfterLoadMoreAnchorPos: Int = RecyclerView.NO_POSITION

    private val childListener =
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (config.enableCenterLongPressToLongClick) {
                    view.setTag(R.id.tag_long_press_handled, false)
                }
                view.setOnKeyListener { v, keyCode, event ->
                    if (!config.isEnabled()) return@setOnKeyListener false

                    if (config.enableCenterLongPressToLongClick && handleCenterLongPress(v, keyCode, event)) {
                        return@setOnKeyListener true
                    }

                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

                    val holder = recyclerView.findContainingViewHolder(v) ?: return@setOnKeyListener false
                    val pos =
                        holder.bindingAdapterPosition
                            .takeIf { it != RecyclerView.NO_POSITION }
                            ?: return@setOnKeyListener false

                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> handleDpadUp(pos)
                        KeyEvent.KEYCODE_DPAD_LEFT -> handleDpadLeft(v)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> handleDpadRight(v)
                        KeyEvent.KEYCODE_DPAD_DOWN -> handleDpadDown(v, pos)
                        else -> false
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.setOnKeyListener(null)
                if (config.enableCenterLongPressToLongClick) {
                    view.setTag(R.id.tag_long_press_handled, false)
                }
            }
        }

    fun install() {
        if (installed) return
        installed = true
        recyclerView.addOnChildAttachStateChangeListener(childListener)
    }

    fun release() {
        if (!installed) return
        installed = false
        clearPendingFocusAfterLoadMore()
        recyclerView.removeOnChildAttachStateChangeListener(childListener)
    }

    fun clearPendingFocusAfterLoadMore() {
        pendingFocusAfterLoadMoreAnchorPos = RecyclerView.NO_POSITION
    }

    fun consumePendingFocusAfterLoadMore(): Boolean {
        val anchorPos = pendingFocusAfterLoadMoreAnchorPos
        if (anchorPos == RecyclerView.NO_POSITION) return false

        if (!config.isEnabled()) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val adapter = recyclerView.adapter
        if (adapter == null) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val spanCount = spanCountForLayoutManager()
        if (spanCount == null) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val candidatePos =
            when {
                anchorPos + spanCount in 0 until itemCount -> anchorPos + spanCount
                anchorPos + 1 in 0 until itemCount -> anchorPos + 1
                else -> null
            }
        clearPendingFocusAfterLoadMore()
        if (candidatePos == null) return false

        recyclerView.findViewHolderForAdapterPosition(candidatePos)?.itemView?.requestFocus()
            ?: run {
                recyclerView.scrollToPosition(candidatePos)
                recyclerView.postIfAlive(
                    isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
                ) {
                    recyclerView.findViewHolderForAdapterPosition(candidatePos)?.itemView?.requestFocus()
                }
            }
        return true
    }

    private fun handleCenterLongPress(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
            keyCode != KeyEvent.KEYCODE_ENTER &&
            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return false
        }

        val handled = (v.getTag(R.id.tag_long_press_handled) as? Boolean) == true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            if (!handled) {
                v.setTag(R.id.tag_long_press_handled, true)
                v.performLongClick()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && handled) {
            v.setTag(R.id.tag_long_press_handled, false)
            return true
        }
        return false
    }

    private fun handleDpadUp(position: Int): Boolean {
        if (!isInTopRowAtTop(position)) return false
        val handled = callbacks.onTopEdge()
        return if (config.consumeUpAtTopEdge) true else handled
    }

    private fun handleDpadLeft(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_LEFT)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) return false
        return callbacks.onLeftEdge()
    }

    private fun handleDpadRight(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_RIGHT)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) return false
        if (!config.consumeRightEdge) return false
        callbacks.onRightEdge()
        // No outflow on the right edge.
        return true
    }

    private fun handleDpadDown(itemView: View, position: Int): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            // Consume even when an in-grid next focus exists. Letting the system handle the event
            // can occasionally pick a "better" candidate outside the RecyclerView (e.g. sidebar),
            // especially during layout/adapter updates while the user is holding DPAD_DOWN.
            next.requestFocus()
            return true
        }

        if (recyclerView.canScrollVertically(1)) {
            val dy = (rootItem.height * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            recyclerView.scrollBy(0, dy)
            val adapter = recyclerView.adapter
            val itemCount = adapter?.itemCount ?: 0
            val spanCount = spanCountForLayoutManager()
            val candidatePos =
                if (itemCount <= 0 || spanCount == null) {
                    null
                } else {
                    when {
                        position + spanCount in 0 until itemCount -> position + spanCount
                        position + 1 in 0 until itemCount -> position + 1
                        else -> null
                    }
                }
            recyclerView.postIfAlive(
                isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
            ) {
                if (tryFocusNextDownFromCurrent()) return@postIfAlive
                if (candidatePos != null) focusAdapterPosition(candidatePos)
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            pendingFocusAfterLoadMoreAnchorPos = position
            callbacks.loadMore()
        }
        // Always consume at the bottom edge to avoid focus escaping to other containers.
        return true
    }

    private fun tryFocusNextDownFromCurrent(): Boolean {
        if (!config.isEnabled()) return false
        val focused = recyclerView.findFocus() ?: return false
        if (!FocusTreeUtils.isDescendantOf(focused, recyclerView)) return false
        val itemView = recyclerView.findContainingItemView(focused) ?: return false
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, itemView, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            next.requestFocus()
            return true
        }
        return false
    }

    private fun focusAdapterPosition(position: Int): Boolean {
        val adapter = recyclerView.adapter ?: return false
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) return false

        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            ?: run {
                recyclerView.scrollToPosition(position)
                recyclerView.postIfAlive(
                    isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
                ) {
                    recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                }
            }
        return true
    }

    private fun spanCountForLayoutManager(): Int? {
        val lm = recyclerView.layoutManager
        return when (lm) {
            is GridLayoutManager -> lm.spanCount.coerceAtLeast(1)
            is LinearLayoutManager -> 1
            is StaggeredGridLayoutManager -> lm.spanCount.coerceAtLeast(1)
            else -> null
        }
    }

    private fun isInTopRowAtTop(position: Int): Boolean {
        // IMPORTANT: Don't rely solely on `RecyclerView.canScrollVertically(-1)` here.
        // On TV, focused items often scale up slightly; RecyclerView may scroll by a few pixels
        // to keep the scaled item fully visible, making `canScrollVertically(-1)` return true
        // even when the user is effectively on the first row. That causes DPAD_UP to escape to
        // other containers (e.g. sidebar) instead of focusing the header/tab.
        val lm = recyclerView.layoutManager ?: return false
        return when (lm) {
            is GridLayoutManager -> {
                val spanCount = lm.spanCount.coerceAtLeast(1)
                val groupIndex = lm.spanSizeLookup.getSpanGroupIndex(position, spanCount)
                if (groupIndex != 0) return false

                val firstVisible = lm.findFirstVisibleItemPosition()
                firstVisible == 0 || !recyclerView.canScrollVertically(-1)
            }

            is LinearLayoutManager -> {
                if (position != 0) return false
                val firstVisible = lm.findFirstVisibleItemPosition()
                firstVisible == 0 || !recyclerView.canScrollVertically(-1)
            }

            is StaggeredGridLayoutManager -> {
                val spanCount = lm.spanCount.coerceAtLeast(1)
                val first = IntArray(spanCount)
                lm.findFirstVisibleItemPositions(first)
                val nearTop = first.any { it == 0 } || !recyclerView.canScrollVertically(-1)
                nearTop && first.any { it == position }
            }

            else -> position == 0 && !recyclerView.canScrollVertically(-1)
        }
    }
}

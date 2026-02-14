package blbl.cat3399.feature.live

interface LivePageFocusTarget {
    // Entering content from a focused tab item: focus first card.
    fun requestFocusFirstCardFromTab(): Boolean

    // Switching tabs from content edge: restore last focused card when possible, fallback to first card.
    fun requestFocusFirstCardFromContentSwitch(): Boolean
}

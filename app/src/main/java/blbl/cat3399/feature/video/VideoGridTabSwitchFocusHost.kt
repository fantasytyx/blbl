package blbl.cat3399.feature.video

/**
 * Implemented by fragments that host a TabLayout + ViewPager2 whose pages are [VideoGridFragment].
 * Used when switching tabs from inside the content area (e.g. DPAD_LEFT/RIGHT at list edges) to
 * move focus into the newly selected page content. Target page should restore its last focused
 * card when possible, and only fallback to first card when no history exists.
 */
interface VideoGridTabSwitchFocusHost {
    fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean
}

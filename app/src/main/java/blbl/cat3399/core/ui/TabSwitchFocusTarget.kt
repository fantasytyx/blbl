package blbl.cat3399.core.ui

interface TabSwitchFocusTarget {
    fun requestFocusFirstCardFromTab(): Boolean

    fun requestFocusFirstCardFromContentSwitch(): Boolean
}

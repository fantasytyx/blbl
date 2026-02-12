package blbl.cat3399.feature.my

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import blbl.cat3399.R
import com.google.android.material.tabs.TabLayout

fun Fragment.myTabLayout(): TabLayout? {
    return parentFragment?.view?.findViewById(R.id.tab_layout)
}

fun Fragment.focusSelectedMyTabIfAvailable(): Boolean {
    val tabLayout = myTabLayout() ?: return false
    val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
    val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
    tabLayout.post { tabStrip.getChildAt(pos)?.requestFocus() }
    return true
}

fun Fragment.switchToNextMyTabIfAvailable(): Boolean {
    val tabLayout = myTabLayout() ?: return false
    val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
    val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
    val next = cur + 1
    if (next >= tabLayout.tabCount) return false
    tabLayout.getTabAt(next)?.select() ?: return false
    tabLayout.post { tabStrip.getChildAt(next)?.requestFocus() }
    return true
}

fun Fragment.switchToPrevMyTabIfAvailable(): Boolean {
    val tabLayout = myTabLayout() ?: return false
    val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
    val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
    val prev = cur - 1
    if (prev < 0) return false
    tabLayout.getTabAt(prev)?.select() ?: return false
    tabLayout.post { tabStrip.getChildAt(prev)?.requestFocus() }
    return true
}

fun Fragment.switchToNextMyTabFromContentEdge(): Boolean {
    val tabLayout = myTabLayout() ?: return false
    val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
    val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
    val next = cur + 1
    if (next >= tabLayout.tabCount) return false
    tabLayout.getTabAt(next)?.select() ?: return false
    tabLayout.post {
        (parentFragment as? MyTabContentSwitchFocusHost)?.requestFocusCurrentPageFirstItemFromContentSwitch()
            ?: tabStrip.getChildAt(next)?.requestFocus()
    }
    return true
}

fun Fragment.switchToPrevMyTabFromContentEdge(): Boolean {
    val tabLayout = myTabLayout() ?: return false
    val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
    val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
    val prev = cur - 1
    if (prev < 0) return false
    tabLayout.getTabAt(prev)?.select() ?: return false
    tabLayout.post {
        (parentFragment as? MyTabContentSwitchFocusHost)?.requestFocusCurrentPageFirstItemFromContentSwitch()
            ?: tabStrip.getChildAt(prev)?.requestFocus()
    }
    return true
}

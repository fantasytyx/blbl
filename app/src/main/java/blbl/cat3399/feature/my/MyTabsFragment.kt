package blbl.cat3399.feature.my

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentMyTabsBinding
import blbl.cat3399.ui.BackPressHandler
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.ui.RefreshKeyHandler
import com.google.android.material.tabs.TabLayout

class MyTabsFragment : Fragment(), MyTabContentSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentMyTabsBinding? = null
    private val binding get() = _binding!!

    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var pendingFocusFirstItemFromContentSwitch: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.viewPager.adapter = MyPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text =
                when (position) {
                    0 -> getString(R.string.my_tab_history)
                    1 -> getString(R.string.my_tab_fav)
                    2 -> getString(R.string.my_tab_bangumi)
                    3 -> getString(R.string.my_tab_drama)
                    4 -> getString(R.string.my_tab_toview)
                    else -> getString(R.string.my_tab_like)
                }
        }.attach()
        binding.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = Unit

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) {
                    refreshCurrentPageFromTabReselect()
                }
            },
        )

        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { _binding != null }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus }) { position ->
                AppLog.d("My", "tab focus pos=$position t=${SystemClock.uptimeMillis()}")
            }
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return@setOnKeyListener focusCurrentPageFirstItem()
                    }
                    false
                }
            }
        }

        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    AppLog.d("My", "page selected pos=$position t=${SystemClock.uptimeMillis()}")
                    if (pendingFocusFirstItemFromContentSwitch) {
                        if (focusCurrentPageFirstItemFromContentSwitch()) {
                            pendingFocusFirstItemFromContentSwitch = false
                        }
                    }
                }
            }
        binding.viewPager.registerOnPageChangeCallback(pageCallback!!)
    }

    override fun onResume() {
        super.onResume()
    }

    private fun refreshCurrentPageFromTabReselect(): Boolean {
        val adapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = adapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val target = (byTag as? RefreshKeyHandler)
            ?: (childFragmentManager.fragments.firstOrNull { it.isVisible && it is RefreshKeyHandler } as? RefreshKeyHandler)
            ?: return false
        return target.handleRefreshKey()
    }

    private fun focusCurrentPageFirstItem(): Boolean {
        val adapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = adapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val target = (byTag as? MyTabSwitchFocusTarget)
            ?: (childFragmentManager.fragments.firstOrNull { it.isVisible && it is MyTabSwitchFocusTarget } as? MyTabSwitchFocusTarget)
        if (target != null) return target.requestFocusFirstItemFromTabSwitch()

        val pageFragment =
            if (byTag?.view?.findViewById<RecyclerView?>(R.id.recycler) != null) {
                byTag
            } else {
                childFragmentManager.fragments.firstOrNull { it.isVisible && it.view?.findViewById<RecyclerView?>(R.id.recycler) != null }
            } ?: return false
        val recycler = pageFragment.view?.findViewById<RecyclerView?>(R.id.recycler) ?: return false

        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            when {
                vh != null -> {
                    vh.itemView.requestFocus()
                }

                recycler.adapter?.itemCount == 0 -> {
                    recycler.requestFocus()
                }

                else -> {
                    recycler.scrollToPosition(0)
                    recycler.postIfAlive(isAlive = { _binding != null }) {
                        recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                            ?: recycler.requestFocus()
                    }
                }
            }
        }
        return true
    }

    private fun focusCurrentPageFirstItemFromContentSwitch(): Boolean {
        val adapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = adapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val target = (byTag as? MyTabSwitchFocusTarget)
            ?: (childFragmentManager.fragments.firstOrNull { it.isVisible && it is MyTabSwitchFocusTarget } as? MyTabSwitchFocusTarget)
            ?: return false
        return target.requestFocusFirstItemFromTabSwitch()
    }

    override fun requestFocusCurrentPageFirstItemFromContentSwitch(): Boolean {
        pendingFocusFirstItemFromContentSwitch = true
        if (focusCurrentPageFirstItemFromContentSwitch()) {
            pendingFocusFirstItemFromContentSwitch = false
        }
        return true
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        if (b.viewPager.currentItem == 0) return false
        pendingFocusFirstItemFromContentSwitch = true
        b.viewPager.setCurrentItem(0, true)
        return true
    }

    override fun onDestroyView() {
        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        _binding = null
        super.onDestroyView()
    }
}

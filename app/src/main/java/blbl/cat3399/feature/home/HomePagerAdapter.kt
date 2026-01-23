package blbl.cat3399.feature.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.feature.video.VideoGridFragment

class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> VideoGridFragment.newRecommend()
            1 -> VideoGridFragment.newPopular()
            2 -> PgcRecommendGridFragment.newBangumi()
            else -> PgcRecommendGridFragment.newCinema()
        }
    }
}

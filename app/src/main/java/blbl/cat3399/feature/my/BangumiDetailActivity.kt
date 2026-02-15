package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive

class BangumiDetailActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container =
            FrameLayout(this).apply {
                id = View.generateViewId()
            }
        setContentView(container)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        val seasonId = intent.getLongExtra(EXTRA_SEASON_ID, -1L).takeIf { it > 0L }
        val epId = intent.getLongExtra(EXTRA_EP_ID, -1L).takeIf { it > 0L }
        val isDrama = intent.getBooleanExtra(EXTRA_IS_DRAMA, false)
        val continueEpId = intent.getLongExtra(EXTRA_CONTINUE_EP_ID, -1L).takeIf { it > 0L } ?: epId
        val continueEpIndex = intent.getIntExtra(EXTRA_CONTINUE_EP_INDEX, -1).takeIf { it > 0 }

        if (seasonId == null && epId == null) {
            AppToast.show(this, "缺少 seasonId/epId")
            finish()
            return
        }

        if (savedInstanceState == null) {
            val fragment =
                when {
                    seasonId != null ->
                        MyBangumiDetailFragment.newInstance(
                            seasonId = seasonId,
                            isDrama = isDrama,
                            continueEpId = continueEpId,
                            continueEpIndex = continueEpIndex,
                        )
                    else ->
                        MyBangumiDetailFragment.newInstanceByEpId(
                            epId = epId ?: 0L,
                            isDrama = isDrama,
                            continueEpId = continueEpId,
                            continueEpIndex = continueEpIndex,
                        )
                }
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(container.id, fragment)
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    companion object {
        const val EXTRA_SEASON_ID: String = "season_id"
        const val EXTRA_EP_ID: String = "ep_id"
        const val EXTRA_IS_DRAMA: String = "is_drama"
        const val EXTRA_CONTINUE_EP_ID: String = "continue_ep_id"
        const val EXTRA_CONTINUE_EP_INDEX: String = "continue_ep_index"
    }
}

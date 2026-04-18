package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import blbl.cat3399.core.image.ImageLoader

internal fun PlayerActivity.initCommentImageViewer() {
    binding.commentImageViewer.visibility = View.GONE
    ImageLoader.loadInto(binding.ivCommentImage, null)
    binding.ivCommentImage.resetViewport()

    binding.commentImageViewer.setOnClickListener {
        if (!isCommentImageViewerVisible()) return@setOnClickListener
        closeCommentImageViewer()
    }

    binding.ivCommentImagePrev.setOnClickListener {
        if (!isCommentImageViewerVisible()) return@setOnClickListener
        if (binding.ivCommentImage.isZoomed()) return@setOnClickListener
        commentImageViewerPrev()
    }
    binding.ivCommentImageNext.setOnClickListener {
        if (!isCommentImageViewerVisible()) return@setOnClickListener
        if (binding.ivCommentImage.isZoomed()) return@setOnClickListener
        commentImageViewerNext()
    }
    binding.ivCommentImage.onNavigatePrevious = {
        if (isCommentImageViewerVisible() && !binding.ivCommentImage.isZoomed()) {
            commentImageViewerPrev()
        }
    }
    binding.ivCommentImage.onNavigateNext = {
        if (isCommentImageViewerVisible() && !binding.ivCommentImage.isZoomed()) {
            commentImageViewerNext()
        }
    }
    binding.ivCommentImage.onBlankAreaTap = {
        if (isCommentImageViewerVisible()) {
            closeCommentImageViewer()
        }
    }
    binding.ivCommentImage.onZoomStateChanged = {
        if (isCommentImageViewerVisible()) {
            updateCommentImageViewerNavigationUi()
        }
    }
}

internal fun PlayerActivity.isCommentImageViewerVisible(): Boolean = binding.commentImageViewer.visibility == View.VISIBLE

internal fun PlayerActivity.openCommentImageViewer(urls: List<String>, startIndex: Int = 0) {
    val safeUrls = urls.map { it.trim() }.filter { it.isNotBlank() }
    if (safeUrls.isEmpty()) return

    commentImageViewerUrls = safeUrls
    commentImageViewerIndex = startIndex.coerceIn(0, safeUrls.lastIndex)
    commentImageViewerFocusReturn.capture(currentFocus)

    binding.commentImageViewer.visibility = View.VISIBLE
    binding.commentImageViewer.bringToFront()
    binding.commentImageViewer.invalidate()
    binding.commentImageViewer.requestLayout()
    binding.commentImageViewer.requestFocus()
    renderCommentImageViewer()
}

internal fun PlayerActivity.closeCommentImageViewer(restoreFocus: Boolean = true) {
    if (!isCommentImageViewerVisible()) return

    binding.commentImageViewer.visibility = View.GONE
    ImageLoader.loadInto(binding.ivCommentImage, null)
    binding.ivCommentImage.resetViewport()
    commentImageViewerUrls = emptyList()
    commentImageViewerIndex = 0

    if (!restoreFocus) {
        commentImageViewerFocusReturn.clear()
        return
    }

    val fallback =
        when {
            isCommentThreadVisible() -> binding.recyclerCommentThread
            isCommentsPanelVisible() -> binding.recyclerComments
            else -> binding.btnComments
        }
    commentImageViewerFocusReturn.restoreAndClear(fallback = fallback, postOnFail = false)
}

internal fun PlayerActivity.dispatchCommentImageViewerKey(event: KeyEvent): Boolean {
    if (!isCommentImageViewerVisible()) return false

    val keyCode = event.keyCode
    if (event.action == KeyEvent.ACTION_DOWN) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                closeCommentImageViewer()
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            -> return true

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                binding.ivCommentImage.toggleDpadZoom()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (binding.ivCommentImage.isZoomed()) {
                    binding.ivCommentImage.panLeft()
                } else {
                    commentImageViewerPrev()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.ivCommentImage.isZoomed()) {
                    binding.ivCommentImage.panRight()
                } else {
                    commentImageViewerNext()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.ivCommentImage.isZoomed()) {
                    binding.ivCommentImage.panUp()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.ivCommentImage.isZoomed()) {
                    binding.ivCommentImage.panDown()
                }
                return true
            }
        }
    }

    if (event.action == KeyEvent.ACTION_UP) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> return true
        }
    }

    return false
}

private fun PlayerActivity.renderCommentImageViewer() {
    val urls = commentImageViewerUrls
    if (urls.isEmpty()) {
        closeCommentImageViewer()
        return
    }

    val idx = commentImageViewerIndex.coerceIn(0, urls.lastIndex)
    commentImageViewerIndex = idx
    binding.ivCommentImage.resetViewport()
    ImageLoader.loadInto(binding.ivCommentImage, urls[idx])
    updateCommentImageViewerNavigationUi()
}

private fun PlayerActivity.commentImageViewerPrev() {
    if (commentImageViewerUrls.size <= 1) return
    if (commentImageViewerIndex <= 0) return
    commentImageViewerIndex -= 1
    renderCommentImageViewer()
}

private fun PlayerActivity.commentImageViewerNext() {
    if (commentImageViewerUrls.size <= 1) return
    if (commentImageViewerIndex >= commentImageViewerUrls.lastIndex) return
    commentImageViewerIndex += 1
    renderCommentImageViewer()
}

private fun PlayerActivity.updateCommentImageViewerNavigationUi() {
    val urls = commentImageViewerUrls
    val showNavigation = urls.size > 1 && !binding.ivCommentImage.isZoomed()
    binding.ivCommentImagePrev.visibility =
        if (showNavigation && commentImageViewerIndex > 0) View.VISIBLE else View.GONE
    binding.ivCommentImageNext.visibility =
        if (showNavigation && commentImageViewerIndex < urls.lastIndex) View.VISIBLE else View.GONE
}

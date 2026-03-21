package blbl.cat3399.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class VideoShotPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val srcRect = Rect()
    private val dstRect = Rect()

    internal var spriteFrame: SpriteFrame? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = spriteFrame ?: return

        val bitmap: Bitmap = frame.spriteSheet

        srcRect.set(
            frame.srcRect.left,
            frame.srcRect.top,
            frame.srcRect.right,
            frame.srcRect.bottom
        )

        dstRect.set(0, 0, width, height)

        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }
}

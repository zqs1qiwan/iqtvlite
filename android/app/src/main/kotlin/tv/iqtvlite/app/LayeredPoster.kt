package tv.iqtvlite.app

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.request.ImageRequest
import coil.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 为 tvOS 海报卡生成真正的分层视差素材：
 * - 前景层：清晰的频道 Logo（原始图）
 * - 背景层：把同一 Logo 缩小再放大得到的模糊版本（廉价模糊，API 28 可用），
 *          作为"发光背景"，聚焦时与前景层做不同位移 → 真视差
 *
 * 不依赖 coil-transformations 额外库；用下采样实现模糊，开销极低。
 */
object LayeredPoster {
    /** 把 bitmap 下采样到极小再放大，得到柔和模糊背景 */
    private fun blurViaDownscale(src: Bitmap): Bitmap {
        val small = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(small)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, android.graphics.Rect(0, 0, 16, 16), paint)
        // 放大回目标尺寸（双线性插值 = 模糊）
        val out = Bitmap.createBitmap(160, 110, Bitmap.Config.ARGB_8888)
        val c2 = android.graphics.Canvas(out)
        c2.drawBitmap(small, null, android.graphics.Rect(0, 0, 160, 110), paint)
        // 压暗，作为背景不喧宾夺主
        c2.drawARGB(140, 0, 0, 0)
        return out
    }

    /**
     * 用 Coil 加载 Logo，同时把模糊版本设置到 background 层。
     * @param logoView 前景清晰 Logo
     * @param bgView 背景模糊层（ImageView）
     * @param url Logo URL，null 时清空
     */
    fun load(
        scope: CoroutineScope,
        context: android.content.Context,
        logoView: ImageView,
        bgView: ImageView,
        url: String?,
    ) {
        logoView.setImageResource(R.drawable.ic_tv)
        bgView.setImageDrawable(null)
        if (url == null) return

        val request = ImageRequest.Builder(context)
            .data(url)
            .target(object : Target {
                override fun onSuccess(drawable: Drawable) {
                    logoView.setImageDrawable(drawable)
                    // 后台生成模糊背景层
                    scope.launch(Dispatchers.Default) {
                        val bmp = drawableToBitmap(drawable) ?: return@launch
                        val blurred = try { blurViaDownscale(bmp) } catch (e: Exception) { return@launch }
                        withContext(Dispatchers.Main) {
                            bgView.setImageDrawable(BitmapDrawable(context.resources, blurred))
                        }
                    }
                }
                override fun onError(error: Drawable?) {
                    logoView.setImageResource(R.drawable.ic_tv)
                }
                override fun onStart(placeholder: Drawable?) {}
            })
            .build()
        coil.ImageLoader(context).enqueue(request)
    }

    private fun drawableToBitmap(d: Drawable): Bitmap? = try {
        if (d is BitmapDrawable && d.bitmap != null) d.bitmap
        else {
            val w = d.intrinsicWidth.coerceAtLeast(1).coerceAtMost(256)
            val h = d.intrinsicHeight.coerceAtLeast(1).coerceAtMost(256)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(android.graphics.Canvas(bmp))
            bmp
        }
    } catch (e: Exception) { null }
}

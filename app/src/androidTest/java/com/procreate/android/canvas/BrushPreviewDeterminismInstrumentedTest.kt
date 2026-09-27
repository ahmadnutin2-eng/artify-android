package com.procreate.android.canvas

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.procreate.android.brushes.BrushLibrary
import com.procreate.android.brushes.BrushPreviewRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Review finding M1: a swatch is the same picture every time it is drawn, so it can be
 * golden-tested; and the renderer remains safe when the lifecycle-owned adapter job runs it away
 * from the main thread.
 */
@RunWith(AndroidJUnit4::class)
class BrushPreviewDeterminismInstrumentedTest {

    private val brushes = BrushLibrary.getDefaultBrushSets().flatMap { it.brushes }

    private fun render(index: Int): Bitmap {
        BrushPreviewRenderer.clearCache()
        return BrushPreviewRenderer.getPreview(brushes[index], 320, 72).copy(Bitmap.Config.ARGB_8888, false)
    }

    @Test
    fun jitteredBrushesRenderIdenticallyTwice() {
        BrushTextures.appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        // Only brushes whose randomness actually shows are worth checking.
        val jittered = brushes.indices.filter { i ->
            val p = brushes[i].properties
            p.scatter > 0f || p.sizeJitter > 0f || p.angleJitter > 0f || p.opacityJitter > 0f
        }.take(12)
        assertTrue("the library has jittered brushes", jittered.isNotEmpty())
        jittered.forEach { i ->
            val first = render(i)
            val second = render(i)
            assertTrue("${brushes[i].id} changed between renders", first.sameAs(second))
        }
    }

    @Test
    fun previewCanRenderOffTheMainThreadAndPopulateTheCache() {
        BrushTextures.appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        BrushPreviewRenderer.clearCache()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val renderedOffMain = executor.submit<Boolean> {
                val offMain = android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
                BrushPreviewRenderer.getPreview(brushes[0], 200, 60)
                offMain
            }.get(10, TimeUnit.SECONDS)
            assertTrue(renderedOffMain)
        } finally {
            executor.shutdownNow()
        }
        assertTrue(BrushPreviewRenderer.cachedPreview(brushes[0], 200, 60) != null)
    }
}

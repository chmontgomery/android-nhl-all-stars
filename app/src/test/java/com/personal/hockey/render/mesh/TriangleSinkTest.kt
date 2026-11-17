package com.personal.hockey.render.mesh

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TriangleSinkTest {
    private val size = 400
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun render(sink: TriangleSink): PixelMap {
        val bmp = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(size.toFloat(), size.toFloat())) {
            drawRect(Color.White)
            sink.begin()
            sink.add(100f, 300f, red, 300f, 300f, green, 200f, 100f, blue)
            sink.flush(this)
        }
        return bmp.toPixelMap()
    }

    @Test fun vertices_sink_fills_a_triangle_with_blended_vertex_colours() {
        val px = render(VerticesSink())
        val c = px[200, 233]
        assertTrue("centroid $c", c != Color.White && c.red > 0.15f && c.green > 0.15f && c.blue > 0.15f)
        val near = px[105, 295]
        assertTrue("red corner $near", near.red > 0.7f)
        assertEquals(Color.White, px[20, 20])
    }

    @Test fun path_sink_fills_a_triangle_flat_with_the_average_colour() {
        val c = render(PathSink())[200, 233]
        val t = 1f / 3f
        assertTrue("centroid $c", abs(c.red - t) < 0.05f && abs(c.green - t) < 0.05f && abs(c.blue - t) < 0.05f)
    }

    @Test fun sinks_drop_triangles_past_capacity() {
        for (s in listOf<TriangleSink>(VerticesSink(2), PathSink(2))) {
            s.begin()
            repeat(3) { s.add(0f, 0f, red, 1f, 0f, red, 0f, 1f, red) }
            assertEquals(2, s.count)
        }
    }

    @Test fun begin_clears() {
        for (s in listOf<TriangleSink>(VerticesSink(2), PathSink(2))) {
            s.add(0f, 0f, red, 1f, 0f, red, 0f, 1f, red)
            s.begin()
            assertEquals(0, s.count)
        }
    }
}

package au.gordo.portalcam

import android.media.Image

/**
 * YUV_420_888 to NV21 conversion and a rotate/mirror pass on NV21.
 * The Portal's camera HAL gives chroma planes with pixel stride 2 that share one buffer, so the fast path copies the
 * V plane straight into the NV21 chroma block. The slow path handles any other layout.
 */
object Yuv {

    /** Converts [image] into [out] as NV21. [out] must be width*height*3/2 bytes. */
    fun toNv21(image: Image, out: ByteArray) {
        val w = image.width
        val h = image.height
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        // Luma.
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == w) {
            yBuf.position(0)
            yBuf.get(out, 0, w * h)
        } else {
            var pos = 0
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(out, pos, w)
                pos += w
            }
        }

        // Chroma, interleaved as V then U.
        val chromaW = w / 2
        val chromaH = h / 2
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvOffset = w * h
        val uvPixelStride = vPlane.pixelStride
        val uvRowStride = vPlane.rowStride

        if (uvPixelStride == 2 && uPlane.pixelStride == 2 && uvRowStride == uPlane.rowStride) {
            // Interleaved. The V buffer starts at V0 and continues V0 U0 V1 U1 ..., which is NV21 order.
            val rowBytes = chromaW * 2
            val vLimit = vBuf.limit()
            var pos = uvOffset
            for (row in 0 until chromaH) {
                val start = row * uvRowStride
                val n = minOf(rowBytes, vLimit - start)
                vBuf.position(start)
                vBuf.get(out, pos, n)
                if (n < rowBytes) {
                    // The HAL omits the trailing U byte of the last row. Fetch it from the U plane.
                    val lastU = uBuf.get((row * uPlane.rowStride) + (chromaW - 1) * uPlane.pixelStride)
                    out[pos + rowBytes - 1] = lastU
                }
                pos += rowBytes
            }
        } else {
            var pos = uvOffset
            for (row in 0 until chromaH) {
                val vRow = row * uvRowStride
                val uRow = row * uPlane.rowStride
                for (col in 0 until chromaW) {
                    out[pos++] = vBuf.get(vRow + col * uvPixelStride)
                    out[pos++] = uBuf.get(uRow + col * uPlane.pixelStride)
                }
            }
        }
    }

    /**
     * Rotates [src] (NV21, [w] x [h]) clockwise by [degrees] (0, 90, 180, 270) and optionally mirrors it
     * horizontally, writing into [dst]. Returns the output (width, height). Mirroring happens after rotation.
     */
    fun transform(src: ByteArray, w: Int, h: Int, degrees: Int, mirror: Boolean, dst: ByteArray): Pair<Int, Int> {
        val outW = if (degrees == 90 || degrees == 270) h else w
        val outH = if (degrees == 90 || degrees == 270) w else h

        // Luma.
        for (y in 0 until h) {
            val srcRow = y * w
            for (x in 0 until w) {
                var ox: Int
                var oy: Int
                when (degrees) {
                    90 -> { ox = h - 1 - y; oy = x }
                    180 -> { ox = w - 1 - x; oy = h - 1 - y }
                    270 -> { ox = y; oy = w - 1 - x }
                    else -> { ox = x; oy = y }
                }
                if (mirror) ox = outW - 1 - ox
                dst[oy * outW + ox] = src[srcRow + x]
            }
        }

        // Chroma (VU pairs at half resolution).
        val cw = w / 2
        val ch = h / 2
        val outCw = outW / 2
        val srcBase = w * h
        val dstBase = outW * outH
        for (y in 0 until ch) {
            val srcRow = srcBase + y * cw * 2
            for (x in 0 until cw) {
                var ox: Int
                var oy: Int
                when (degrees) {
                    90 -> { ox = ch - 1 - y; oy = x }
                    180 -> { ox = cw - 1 - x; oy = ch - 1 - y }
                    270 -> { ox = y; oy = cw - 1 - x }
                    else -> { ox = x; oy = y }
                }
                if (mirror) ox = outCw - 1 - ox
                val d = dstBase + (oy * outCw + ox) * 2
                val s = srcRow + x * 2
                dst[d] = src[s]
                dst[d + 1] = src[s + 1]
            }
        }
        return outW to outH
    }
}

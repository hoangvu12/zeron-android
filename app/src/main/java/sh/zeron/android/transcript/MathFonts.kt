package sh.zeron.android.transcript

import android.graphics.fonts.Font
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.nio.ByteBuffer
import uniffi.zeron_core.mathFontData

/**
 * The core's bundled math fonts by table index (iOS `Fonts.mathFont`,
 * `ios:Core/Fonts.swift:70-97`): `mathFontData(index)` → a
 * [Font] over a direct buffer, built on first use and kept for the
 * process. A face is ~1–2 MB, so it loads only when a glyph first names
 * it — [RowModel] resolves its fonts at construction, off main on the
 * prefetch path — and an index that yields no bytes or no font is
 * remembered as a miss, so it is tried once.
 *
 * Thread-safe: row models build on the prefetch worker and on main.
 */
internal object MathFonts {

    private val fonts = HashMap<Int, Font?>()

    /** The font bytes for an index; a seam so tests run without the core. */
    @VisibleForTesting
    internal var bytes: (UByte) -> ByteArray = { mathFontData(it) }

    /** Math font [index], or `null` for an unknown face (logged once). */
    fun get(index: UByte): Font? = synchronized(fonts) {
        val key = index.toInt()
        if (fonts.containsKey(key)) return fonts[key]
        val font = load(index)
        // runCatching: plain-JVM tests have no android.util.Log.
        if (font == null) runCatching { Log.w(TAG, "no math font at index $key") }
        fonts[key] = font
        font
    }

    @VisibleForTesting
    internal fun resetForTest() = synchronized(fonts) {
        fonts.clear()
        bytes = { mathFontData(it) }
    }

    private fun load(index: UByte): Font? {
        val data = runCatching { bytes(index) }.getOrNull() ?: return null
        if (data.isEmpty()) return null
        // Font.Builder only reads direct buffers.
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(data.size)
        buffer.put(data)
        buffer.rewind()
        return try {
            Font.Builder(buffer).build()
        } catch (e: Exception) {
            null
        }
    }

    private const val TAG = "ZeronTranscript"
}

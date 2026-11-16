package sh.zeron.android.transcript

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * One way to get an image's bytes. The loader tries a reference's sources
 * in order and fails only when every one fails (a source whose bytes the
 * phone can't decode — SVG — counts as failed, so the next one runs).
 */
enum class ImageSource {
    /** A `data:` URI, decoded on the phone (no network). */
    Inline,

    /**
     * The host's `readAttachment` (`ReadAttachmentChunk`), as iOS
     * `CoreSessionSource.image` reads every reference. The phone never
     * fetches an `http(s)` reference itself: like iOS and the desktop, a
     * reply image never makes the phone call an arbitrary URL.
     */
    Engine,
}

/**
 * The sources for [reference], most direct first. Nothing is refused up
 * front: every non-blank reference reaches the engine at least.
 */
fun imageSources(reference: String): List<ImageSource> = when {
    reference.isBlank() -> emptyList()
    isDataUri(reference) -> listOf(ImageSource.Inline, ImageSource.Engine)
    else -> listOf(ImageSource.Engine)
}

/** Whether any source can try [reference] (only blank ones have none). */
fun isFetchableImageReference(ref: String): Boolean = imageSources(ref).isNotEmpty()

internal fun isDataUri(reference: String): Boolean = reference.trim().startsWith("data:", ignoreCase = true)

/**
 * The bytes of a `data:[<mime>][;base64],<payload>` URI (base64, URL-safe
 * base64 or percent-encoded), or null when it is malformed.
 */
fun decodeDataUri(reference: String): ByteArray? {
    val ref = reference.trim()
    if (!ref.startsWith("data:", ignoreCase = true)) return null
    val comma = ref.indexOf(',')
    if (comma < 0) return null
    val meta = ref.substring(5, comma)
    val payload = ref.substring(comma + 1)
    val base64 = meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }
    return if (base64) {
        val compact = percentDecode(payload).filterNot { it.isWhitespace() }.trimEnd('=')
        runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull()
    } else {
        percentDecodeBytes(payload)
    }
}

private fun percentDecode(text: String): String = String(percentDecodeBytes(text), Charsets.UTF_8)

/** The value of an ASCII hex digit, or -1 (no sign, unlike `toIntOrNull(16)`). */
private fun hexDigit(b: Byte): Int = when (val c = b.toInt()) {
    in '0'.code..'9'.code -> c - '0'.code
    in 'a'.code..'f'.code -> c - 'a'.code + 10
    in 'A'.code..'F'.code -> c - 'A'.code + 10
    else -> -1
}

/** `%XX` escapes decoded to bytes; a `%` not followed by two hex digits stays literal. */
internal fun percentDecodeBytes(text: String): ByteArray {
    val raw = text.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(raw.size)
    var at = 0
    while (at < raw.size) {
        val hex = if (raw[at] == '%'.code.toByte() && at + 2 < raw.size) {
            val hi = hexDigit(raw[at + 1])
            val lo = hexDigit(raw[at + 2])
            if (hi >= 0 && lo >= 0) hi * 16 + lo else null
        } else {
            null
        }
        if (hex != null) {
            out.write(hex)
            at += 3
        } else {
            out.write(raw[at].toInt())
            at += 1
        }
    }
    return out.toByteArray()
}

/**
 * The delay before a failed tile's [attempt]-th automatic retry (1-based):
 * 2 s, 4 s, 8 s … capped at a minute, so a dead URL is never hammered.
 */
fun imageRetryDelayMs(attempt: Int): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(5)
    return min(2_000L shl exponent, 60_000L)
}

/**
 * Automatic retries a failed tile makes on its own backoff before it stops
 * (2 + 4 + 8 + 16 + 32 + 60 + 60 + 60 s ≈ 4 min). After that only a
 * reconnect or a tap tries again, and either restarts the count.
 */
const val MaxAutoImageRetries = 8

/**
 * One emission each time [connected] turns true after the first value:
 * the engine came back, so failed tiles retry at once.
 */
fun reconnects(connected: Flow<Boolean>): Flow<Unit> =
    connected.distinctUntilChanged().drop(1).filter { it }.map { }

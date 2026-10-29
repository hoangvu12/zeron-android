package sh.zeron.android.transcript

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * What a link tap does (ticket 126's routing table, the port of
 * `ios:Transcript/TranscriptListView.swift:574-594`). Pure — no Android
 * types — so it unit-tests on the plain JVM.
 */
sealed interface LinkRoute {
    /** http/https — a Custom Tab (iOS's in-app `SFSafariViewController`). */
    data class Web(val url: String) : LinkRoute

    /** An image chip (`zeron-preview://image?ref=…`) — the viewer, from the row. */
    data class Preview(val reference: String) : LinkRoute

    /**
     * A sender or recipient caption (`zeron-chat://open?id=…`, #30): open
     * that chat (TLV:710-717); the session screen ignores its own id.
     */
    data class Chat(val chatId: String) : LinkRoute

    /** Any other recognized scheme — `Intent(ACTION_VIEW)`, as iOS's `UIApplication.open`. */
    data class External(val url: String) : LinkRoute

    /** Do nothing: iOS's silent no-ops (decision TRANSCRIPT-D4). */
    data object Ignore : LinkRoute
}

/** A scheme-shaped prefix: `^[A-Za-z][A-Za-z0-9+.-]*:` (RFC 3986, ASCII). */
private val SchemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/** A Windows drive path `^[A-Za-z]:` followed by a separator (checked before the scheme row: `C:` looks like one). */
private val WindowsDriveRegex = Regex("^[A-Za-z]:[\\\\/]")

/** The percent byte, 0x25. */
private const val PercentByte: Byte = 0x25

/**
 * Routes a link URL (the Rust `LinkHit.url`, the markdown destination
 * verbatim). The rows are checked in the ticket's order: empty and
 * `#anchor` (row 1), `http`/`https` (2), `zeron-preview` (3),
 * `zeron-chat` (3b, #30), `zeron-invoke`/`zeron-file`/`file` (4–6, the decision
 * TRANSCRIPT-D4 no-ops), a Windows drive path (7, before the scheme
 * row), any other scheme (8, the system), and anything scheme-less (9).
 * Scheme matching is ASCII case-insensitive (RFC 3986; iOS's
 * case-sensitive `url.scheme == "http"` is adapted). File-shaped links
 * (rows 5–7 and 9) that name an image open the viewer with the link
 * verbatim as the reference — the engine's `ReadImageChunk` resolves it —
 * and stay the TRANSCRIPT-D4 no-ops otherwise.
 */
fun routeLink(url: String): LinkRoute {
    val raw = url.trim()
    if (raw.isEmpty() || raw.startsWith("#")) return LinkRoute.Ignore
    val scheme = SchemeRegex.find(raw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    val fileShaped = scheme == null ||
        scheme == "zeron-file" ||
        scheme == "file" ||
        WindowsDriveRegex.containsMatchIn(raw)
    return when {
        fileShaped -> if (namesImageFile(raw)) LinkRoute.Preview(raw) else LinkRoute.Ignore
        scheme == "http" || scheme == "https" -> LinkRoute.Web(raw)
        scheme == "zeron-preview" -> previewRef(raw)?.let { LinkRoute.Preview(it) }
            ?: LinkRoute.Ignore
        scheme == "zeron-chat" -> queryItem(raw, "id")?.let { LinkRoute.Chat(it) }
            ?: LinkRoute.Ignore
        // A skill or command mention: iOS hands it to the system, which
        // has no handler for it, so nothing happens.
        scheme == "zeron-invoke" -> LinkRoute.Ignore
        else -> LinkRoute.External(raw)
    }
}

/** Image file extensions a file-shaped link opens in the viewer. */
private val ImageExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "svg", "svgz", "bmp", "avif", "heic", "heif", "ico", "tif", "tiff",
)

/** Whether a file-shaped link's last path segment (before `?`/`#`) has an image extension. */
internal fun namesImageFile(link: String): Boolean {
    val path = link.substringBefore('#').substringBefore('?').trimEnd('/', '\\')
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    return dot > 0 && name.substring(dot + 1).lowercase(Locale.ROOT) in ImageExtensions
}

/** The `ref` of an image-chip link (`zeron-preview://image?ref=…`); see [queryItem]. */
internal fun previewRef(url: String): String? = queryItem(url, "ref")

/**
 * The [name] query item of [url], percent-decoded, or `null` when there
 * is none, it is empty, or it does not decode. Hand-rolled on purpose:
 * `Uri.getQueryParameter` and `URLDecoder` both turn `+` into a space,
 * and iOS's `URLComponents.queryItems` does not. The query is the text
 * after the first `?` up to the first `#`; the first item named [name]
 * (its name percent-decoded) that contains `=` wins, and its value is
 * decoded with [percentDecodeUtf8] and must be non-empty.
 */
internal fun queryItem(url: String, name: String): String? {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return null
    var query = url.substring(queryStart + 1)
    val hash = query.indexOf('#')
    if (hash >= 0) query = query.substring(0, hash)
    for (item in query.split('&')) {
        val eq = item.indexOf('=')
        if (eq < 0) continue
        val key = percentDecodeUtf8(item.substring(0, eq)) ?: continue
        if (key != name) continue
        return percentDecodeUtf8(item.substring(eq + 1))?.takeIf { it.isNotEmpty() }
    }
    return null
}

/**
 * Percent-decodes [s] as UTF-8, or returns `null` on a truncated or
 * non-hex `%XX` escape or on bytes that are not valid UTF-8
 * (`CodingErrorAction.REPORT`; iOS's `removingPercentEncoding` returns
 * `nil` for those). `+` is kept — this is a path-style decode, not the
 * `application/x-www-form-urlencoded` one. Hex digits are accepted in
 * both cases (the encoder writes upper-case).
 */
internal fun percentDecodeUtf8(s: String): String? {
    val src = s.encodeToByteArray()
    // Every escape shrinks the output; plain bytes keep their size.
    val out = ByteArray(src.size)
    var n = 0
    var i = 0
    while (i < src.size) {
        val b = src[i]
        if (b == PercentByte) {
            if (i + 2 >= src.size) return null
            val hi = hexDigit(src[i + 1]) ?: return null
            val lo = hexDigit(src[i + 2]) ?: return null
            out[n++] = ((hi shl 4) or lo).toByte()
            i += 3
        } else {
            out[n++] = b
            i += 1
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(out, 0, n)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/** The value of a hex digit byte, or `null` (both cases). */
private fun hexDigit(b: Byte): Int? {
    val c = b.toInt() and 0xFF
    return when (c) {
        in 0x30..0x39 -> c - 0x30 // '0'…'9'
        in 0x41..0x46 -> c - 0x41 + 10 // 'A'…'F'
        in 0x61..0x66 -> c - 0x61 + 10 // 'a'…'f'
        else -> null
    }
}

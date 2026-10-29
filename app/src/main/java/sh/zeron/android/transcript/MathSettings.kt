package sh.zeron.android.transcript

import java.util.concurrent.CopyOnWriteArraySet
import uniffi.zeron_core.MathFace
import uniffi.zeron_core.TranscriptView

/**
 * How transcripts show LaTeX math, local to this phone (iOS `MathSettings`,
 * `ios:Transcript/TranscriptListView.swift:4-80`): rendered in one of the
 * core's bundled math faces, or as its TeX source. Every live transcript
 * applies [current] when it is created and again on each change
 * ([TranscriptState] registers through [observe]).
 *
 * TODO(#36): persist `mathRender` / `mathFont` / `mathSize` and load them
 * at startup; the Settings rows write through [update]. Until then the
 * values are the iOS defaults: on, Fira Math, 100 %.
 */
object MathSettings {

    /** One settings snapshot. [sizePercent] is a percent of the text around a formula (the core clamps to 75–200). */
    data class Values(
        val render: Boolean = true,
        val face: MathFace = MathFace.FIRA,
        val sizePercent: Int = 100,
    )

    /** The desktop's size options. */
    val allSizes = listOf(90, 100, 110, 120, 135, 150)

    @Volatile
    var current: Values = Values()
        private set

    private val observers = CopyOnWriteArraySet<(Values) -> Unit>()

    /** Publishes [values] to every live transcript (no-op when unchanged). */
    fun update(values: Values) {
        if (values == current) return
        current = values
        for (o in observers) o(values)
    }

    /** Calls [onChange] on every later [update]; the returned function unregisters it. */
    fun observe(onChange: (Values) -> Unit): () -> Unit {
        observers.add(onChange)
        return { observers.remove(onChange) }
    }

    /** iOS `MathSettings.apply(to:)`. */
    fun apply(engine: TranscriptView, values: Values = current) {
        engine.setMath(values.render, values.face, values.sizePercent.coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort())
    }
}

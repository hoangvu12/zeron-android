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
 * Persisted by `core/Prefs` under iOS's keys (`mathRender`, `mathFont`,
 * `mathSize`) and published here at startup and on every Settings change
 * (#36); the defaults are iOS's: on, Fira Math, 100 %.
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

    /** The core's bundled math faces, in iOS's menu order. */
    val allFaces = listOf(
        MathFace.FIRA,
        MathFace.STIX_TWO,
        MathFace.COMPUTER_MODERN,
        MathFace.LIBERTINUS,
        MathFace.PAGELLA,
        MathFace.EULER,
    )

    /** The stored key of [face] (iOS `key(_:)`, `mathFont`). */
    fun key(face: MathFace): String = when (face) {
        MathFace.FIRA -> "fira"
        MathFace.STIX_TWO -> "stixTwo"
        MathFace.COMPUTER_MODERN -> "computerModern"
        MathFace.LIBERTINUS -> "libertinus"
        MathFace.PAGELLA -> "pagella"
        MathFace.EULER -> "euler"
    }

    /** The face stored under [key]; anything unknown reads as Fira Math (iOS). */
    fun faceOf(key: String?): MathFace = allFaces.firstOrNull { key(it) == key } ?: MathFace.FIRA

    /** iOS `label(_:)`. */
    fun label(face: MathFace): String = when (face) {
        MathFace.FIRA -> "Fira Math"
        MathFace.STIX_TWO -> "STIX Two"
        MathFace.COMPUTER_MODERN -> "Computer Modern"
        MathFace.LIBERTINUS -> "Libertinus"
        MathFace.PAGELLA -> "Palatino"
        MathFace.EULER -> "Euler"
    }

    /** iOS `detail(_:)`. */
    fun detail(face: MathFace): String = when (face) {
        MathFace.FIRA -> "A sans that sits beside the interface font."
        MathFace.STIX_TWO -> "A serif with the Times-like TeX look."
        MathFace.COMPUTER_MODERN -> "The classic LaTeX look."
        MathFace.LIBERTINUS -> "A warm book serif."
        MathFace.PAGELLA -> "TeX Gyre Pagella, a Palatino serif."
        MathFace.EULER -> "Zapf's upright, hand-drawn math."
    }

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

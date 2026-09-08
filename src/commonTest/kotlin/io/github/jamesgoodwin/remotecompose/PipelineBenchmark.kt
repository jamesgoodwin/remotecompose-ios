package io.github.jamesgoodwin.remotecompose

import io.github.jamesgoodwin.remotecompose.parser.OperationReader
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeParser
import kotlin.time.TimeSource

/**
 * How long the shared pipeline takes, per runtime.
 *
 * Almost all of this renderer is common code: there are four `expect` declarations in it, and two
 * of those are demo chrome. So "is iOS slower than Android" is mostly a question about the two
 * runtimes that common code is compiled for — Kotlin/Native ahead of time, and ART — and this
 * measures the same work on whichever one it is running on.
 *
 * It measures the two halves separately. [decode] is `OperationReader.readAll`, which a host does
 * once when the document is opened. [build] is `RemoteComposeParser.build`, which runs on every
 * frame of an animating document and is therefore the one with a 16.7ms budget over it.
 *
 * Text is measured by [io.github.jamesgoodwin.remotecompose.text.EstimatedTextMetrics] here — the arithmetic
 * one, not the platform's font engine — so what is timed is this code rather than Skia or
 * `android.text`. Those are measured separately; see docs/PERFORMANCE.md.
 */
object PipelineBenchmark {

    /** Fixtures worth timing: the animated ones, the text-heavy ones and the two largest. */
    private val SUBJECTS = listOf(
        "watch", "carousel", "article", "coffee", "material",
        "lazylist", "parallax", "wrap", "sample", "showcase",
    )

    private const val WARMUP = 200
    private const val RUNS = 500

    /** One fixture's timings, in microseconds. */
    data class Row(
        val name: String,
        val bytes: Int,
        val opcodes: Int,
        val decodeMedian: Double,
        val buildMedian: Double,
        val buildBest: Double,
        val buildP95: Double,
        /** Medians of the first and last quarter of the run, in the order they were measured. */
        val buildEarly: Double,
        val buildLate: Double,
    )

    fun run(): List<Row> = SUBJECTS.mapNotNull { name ->
        val bytes = FIXTURES[name] ?: return@mapNotNull null
        Row(
            name = name,
            bytes = bytes.size,
            opcodes = RemoteComposeParser.load(bytes).frame(0L).opcodes.size,
            decodeMedian = time(bytes, warmup = 20, runs = 50) { OperationReader.readAll(it).size },
            buildMedian = 0.0,
            buildBest = 0.0,
            buildP95 = 0.0,
            buildEarly = 0.0,
            buildLate = 0.0,
        ).withBuild(bytes)
    }

    /**
     * Frames the document [RUNS] times with the clock advancing by one 60Hz frame each time, so
     * animations, scrolls and time variables are actually moving rather than idling on one value.
     */
    private fun Row.withBuild(bytes: ByteArray): Row {
        val document = RemoteComposeParser.load(bytes)
        val samples = DoubleArray(RUNS)
        var sink = 0
        var t = 0L
        repeat(WARMUP) { sink += document.frame(t).opcodes.size; t += 16 }
        for (i in 0 until RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            sink += document.frame(t).opcodes.size
            samples[i] = mark.elapsedNow().inWholeNanoseconds / 1000.0
            t += 16
        }
        // Keeps the work live: Kotlin/Native's release build is free to delete a loop whose
        // result nothing reads, and a benchmark of nothing runs very fast indeed.
        checksum += sink
        // Before sorting: a runtime still compiling while it is being timed gets faster as the
        // run goes on, and that is a different thing from a runtime that is simply erratic.
        val quarter = RUNS / 4
        val early = samples.copyOfRange(0, quarter).also { it.sort() }[quarter / 2]
        val late = samples.copyOfRange(RUNS - quarter, RUNS).also { it.sort() }[quarter / 2]
        samples.sort()
        return copy(
            buildMedian = samples[RUNS / 2],
            buildBest = samples[0],
            buildP95 = samples[(RUNS * 95) / 100],
            buildEarly = early,
            buildLate = late,
        )
    }

    private fun time(bytes: ByteArray, warmup: Int, runs: Int, block: (ByteArray) -> Int): Double {
        var sink = 0
        repeat(warmup) { sink += block(bytes) }
        val samples = DoubleArray(runs)
        for (i in 0 until runs) {
            val mark = TimeSource.Monotonic.markNow()
            sink += block(bytes)
            samples[i] = mark.elapsedNow().inWholeNanoseconds / 1000.0
        }
        checksum += sink
        samples.sort()
        return samples[runs / 2]
    }

    var checksum: Long = 0
        private set

    /** The table, as one string, so every runner prints it the same way. */
    fun report(label: String): String {
        val rows = run()
        val out = StringBuilder()
        out.append("RCBENCH $label\n")
        out.append("RCBENCH fixture      bytes  opcodes  decode.med   build.med  build.best   build.p95 build.early  build.late\n")
        for (r in rows) {
            out.append(
                "RCBENCH " + r.name.padEnd(10) +
                    r.bytes.toString().padStart(8) +
                    r.opcodes.toString().padStart(9) +
                    us(r.decodeMedian).padStart(12) +
                    us(r.buildMedian).padStart(12) +
                    us(r.buildBest).padStart(12) +
                    us(r.buildP95).padStart(12) +
                    us(r.buildEarly).padStart(12) +
                    us(r.buildLate).padStart(12) + "\n",
            )
        }
        out.append(
            "RCBENCH total build.med ${us(rows.sumOf { it.buildMedian })}" +
                " early ${us(rows.sumOf { it.buildEarly })}" +
                " late ${us(rows.sumOf { it.buildLate })}" +
                " best ${us(rows.sumOf { it.buildBest })} (checksum $checksum)\n",
        )
        return out.toString()
    }

    private fun us(value: Double): String {
        val scaled = (value * 10).toLong()
        return "${scaled / 10}.${scaled % 10}us"
    }
}

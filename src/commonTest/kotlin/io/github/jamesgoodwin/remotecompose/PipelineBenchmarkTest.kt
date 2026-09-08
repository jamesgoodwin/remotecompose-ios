package io.github.jamesgoodwin.remotecompose

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs [PipelineBenchmark] and prints its table.
 *
 * A test rather than a `main`, because that is the one entry point every target already has a
 * runner for: the same code has to be startable on the JVM, on Kotlin/Native and under an Android
 * instrumentation runner, and writing three launchers would measure three different things.
 *
 * It asserts nothing about the numbers. A threshold here would either be loose enough to pass on
 * any machine, and so measure nothing, or tight enough to fail on a busy one.
 */
class PipelineBenchmarkTest {

    @Test
    fun benchmark() {
        val report = PipelineBenchmark.report("build")
        println(report)
        assertTrue(PipelineBenchmark.checksum > 0, "the work was not optimised away")
    }
}

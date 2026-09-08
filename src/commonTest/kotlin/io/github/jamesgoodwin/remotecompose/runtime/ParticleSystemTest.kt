package io.github.jamesgoodwin.remotecompose.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class ParticleSystemTest {

    private val var1 = FloatExpressionEvaluator.opFloat(70)
    private val add = FloatExpressionEvaluator.opFloat(1)
    private val mul = FloatExpressionEvaluator.opFloat(3)

    @Test
    fun everyParticleIsCreatedFromItsOwnIndex() {
        val system = ParticleSystem(listOf(10, 11), particleCount = 4)
        val equations = listOf(
            floatArrayOf(var1, 5f, mul), // x = 5 * index
            floatArrayOf(var1, 100f, add), // y = 100 + index
        )
        for (p in 0 until system.count) system.initialize(p, equations)
        assertEquals(listOf(0f, 5f, 10f, 15f), system.values.map { it[0] })
        assertEquals(listOf(100f, 101f, 102f, 103f), system.values.map { it[1] })
    }

    @Test
    fun loadingAParticlePutsItsValuesUnderTheSystemsVariableIds() {
        val context = RemoteContext()
        val system = ParticleSystem(listOf(100, 101), particleCount = 2)
        system.values[0][0] = 7f
        system.values[0][1] = 8f
        system.values[1][0] = 70f
        system.values[1][1] = 80f
        system.load(context, 1)
        assertEquals(70f, context.getFloat(100))
        assertEquals(80f, context.getFloat(101))
        system.load(context, 0)
        assertEquals(7f, context.getFloat(100))
    }

    @Test
    fun aVariableWithNoEquationKeepsItsValue() {
        val system = ParticleSystem(listOf(1, 2), particleCount = 1)
        system.values[0][1] = 42f
        system.initialize(0, listOf(floatArrayOf(3f)))
        assertEquals(3f, system.values[0][0])
        assertEquals(42f, system.values[0][1])
    }
}

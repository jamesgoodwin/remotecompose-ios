package com.example.remotecompose.runtime

/**
 * The per-particle variables of one `ParticlesCreate` (remote-core 1.0.0-alpha18): a value per
 * variable per particle. [varIds] are the float-pool ids the particle's values are written to
 * before its equations run and before its body draws, so the body reads whichever particle is
 * current.
 */
class ParticleSystem(val varIds: List<Int>, particleCount: Int) {

    val values: Array<FloatArray> = Array(particleCount) { FloatArray(varIds.size) }

    val count: Int get() = values.size

    /**
     * `initializeParticle`: each variable takes the value of its own creation equation, which
     * sees the particle's index in the first caller variable slot. [equations] must already have
     * had their pool variables resolved.
     */
    fun initialize(index: Int, equations: List<FloatArray>) {
        val particle = values[index]
        val slot = floatArrayOf(index.toFloat())
        for (v in particle.indices) {
            val equation = equations.getOrNull(v) ?: continue
            particle[v] = FloatExpressionEvaluator.eval(equation, slot)
        }
    }

    /** Writes this particle's values into the float pool, so the equations and body read them. */
    fun load(context: RemoteContext, index: Int) {
        val particle = values[index]
        for (v in particle.indices) context.loadFloat(varIds[v], particle[v])
    }
}

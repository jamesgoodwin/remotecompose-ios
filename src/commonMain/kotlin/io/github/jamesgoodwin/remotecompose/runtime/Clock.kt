package io.github.jamesgoodwin.remotecompose.runtime

/** Wall-clock milliseconds since the Unix epoch, for the context's time variables. */
internal expect fun currentTimeMillis(): Long

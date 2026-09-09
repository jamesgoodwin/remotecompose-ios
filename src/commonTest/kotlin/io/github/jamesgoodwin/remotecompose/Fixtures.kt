package io.github.jamesgoodwin.remotecompose

import io.github.jamesgoodwin.remotecompose.fixtures.ACTIONS_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.ADVANCED_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.ARTICLE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.ANIM_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.COFFEE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.FLIGHT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.FONT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.LAZYLIST_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.NOTCHES_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.REFERENCED_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.LAYOUT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.MARQUEE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.RUNACTION_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.WRAP_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.LIST_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.MATERIAL_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.CAROUSEL_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.PAINT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.PARALLAX_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.PATTERN_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SAMPLE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SHOWCASE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.WATCH_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SHADER_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SHADOW_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SWIPE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.TEXTPATH_RC_BYTES

/**
 * The writer's fixtures by name, as the bytes the demo pages already carry.
 *
 * These tests used to read `tools/rc-writer/<name>.rc` through `java.io.File`, which is the only
 * reason they were a JVM-only suite: everything they exercise is common code, so the platform
 * that ships it — Kotlin/Native, with its own float formatting and its own byte handling — never
 * ran any of it. Reading the payloads instead lets the same tests run on every target.
 *
 * The payloads are the same bytes, and `PayloadDriftTest` on the desktop target keeps them so:
 * it is the one place that still opens the files, and it fails if a regenerated fixture has not
 * been carried across.
 */
val FIXTURES: Map<String, ByteArray> = mapOf(
    "actions" to ACTIONS_RC_BYTES,
    "advanced" to ADVANCED_RC_BYTES,
    "article" to ARTICLE_RC_BYTES,
    "attributes" to ATTRIBUTES_RC_BYTES,
    "anim" to ANIM_RC_BYTES,
    "carousel" to CAROUSEL_RC_BYTES,
    "coffee" to COFFEE_RC_BYTES,
    "coretext" to CORETEXT_RC_BYTES,
    "easing" to EASING_RC_BYTES,
    "flight" to FLIGHT_RC_BYTES,
    "font" to FONT_RC_BYTES,
    "hostactions" to HOSTACTIONS_RC_BYTES,
    "lazylist" to LAZYLIST_RC_BYTES,
    "notches" to NOTCHES_RC_BYTES,
    "referenced" to REFERENCED_RC_BYTES,
    "wrap" to WRAP_RC_BYTES,
    "layout" to LAYOUT_RC_BYTES,
    "marquee" to MARQUEE_RC_BYTES,
    "runaction" to RUNACTION_RC_BYTES,
    "list" to LIST_RC_BYTES,
    "material" to MATERIAL_RC_BYTES,
    "named" to NAMED_RC_BYTES,
    "paint" to PAINT_RC_BYTES,
    "parallax" to PARALLAX_RC_BYTES,
    "pattern" to PATTERN_RC_BYTES,
    "sample" to SAMPLE_RC_BYTES,
    "shader" to SHADER_RC_BYTES,
    "showcase" to SHOWCASE_RC_BYTES,
    "shadow" to SHADOW_RC_BYTES,
    "swipe" to SWIPE_RC_BYTES,
    "textpath" to TEXTPATH_RC_BYTES,
    "watch" to WATCH_RC_BYTES,
    "timeattr" to TIMEATTR_RC_BYTES,
    "visibility" to VISIBILITY_RC_BYTES,
)

/** The bytes of `tools/rc-writer/[name].rc`. */
fun fixture(name: String): ByteArray =
    FIXTURES[name] ?: error("no fixture named '$name'; known: ${FIXTURES.keys.sorted()}")

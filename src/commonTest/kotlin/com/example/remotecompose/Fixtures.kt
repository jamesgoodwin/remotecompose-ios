package com.example.remotecompose

import com.example.remotecompose.demo.ACTIONS_RC_BYTES
import com.example.remotecompose.demo.ADVANCED_RC_BYTES
import com.example.remotecompose.demo.ARTICLE_RC_BYTES
import com.example.remotecompose.demo.ANIM_RC_BYTES
import com.example.remotecompose.demo.COFFEE_RC_BYTES
import com.example.remotecompose.demo.LIST_RC_BYTES
import com.example.remotecompose.demo.MATERIAL_RC_BYTES
import com.example.remotecompose.demo.PAINT_RC_BYTES
import com.example.remotecompose.demo.PATTERN_RC_BYTES
import com.example.remotecompose.demo.SAMPLE_RC_BYTES
import com.example.remotecompose.demo.SHOWCASE_RC_BYTES
import com.example.remotecompose.demo.TEXTPATH_RC_BYTES

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
    "coffee" to COFFEE_RC_BYTES,
    "coretext" to CORETEXT_RC_BYTES,
    "easing" to EASING_RC_BYTES,
    "hostactions" to HOSTACTIONS_RC_BYTES,
    "list" to LIST_RC_BYTES,
    "material" to MATERIAL_RC_BYTES,
    "named" to NAMED_RC_BYTES,
    "paint" to PAINT_RC_BYTES,
    "pattern" to PATTERN_RC_BYTES,
    "sample" to SAMPLE_RC_BYTES,
    "shader" to SHADER_RC_BYTES,
    "showcase" to SHOWCASE_RC_BYTES,
    "textpath" to TEXTPATH_RC_BYTES,
    "timeattr" to TIMEATTR_RC_BYTES,
    "visibility" to VISIBILITY_RC_BYTES,
)

/** The bytes of `tools/rc-writer/[name].rc`. */
fun fixture(name: String): ByteArray =
    FIXTURES[name] ?: error("no fixture named '$name'; known: ${FIXTURES.keys.sorted()}")

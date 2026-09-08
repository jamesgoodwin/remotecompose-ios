# Performance: iOS against Android

This renderer is one body of common code. There are four `expect` declarations in it — a clock, an
image decoder, and two pieces of demo chrome — so "is iOS slower than Android" is very nearly a
question about the two runtimes that the same Kotlin is compiled for: Kotlin/Native ahead of time,
and ART.

`PipelineBenchmark` in `src/commonTest` measures that. It runs on whichever runtime it is started
on, and every runtime returns the same checksum, so the four tables below are the same work.

## What was measured

Two phases, separately.

- **decode** — `OperationReader.readAll`, which a host does once when it opens a document.
- **build** — `RemoteComposeParser.build`, which runs on every frame of an animating document and
  is the one with 16.7ms over it.

Ten fixtures, 200 warm-up frames then 500 timed frames each, with the clock advancing one 60Hz
frame per iteration so animations, scrolls and time variables are moving rather than idling. Text
is measured by `EstimatedTextMetrics` — the arithmetic one — so what is timed is this code and not
a font engine. The font engines are dealt with separately below, and they are the larger problem.

The numbers are the sum over the ten fixtures of each fixture's median frame, which is a way of
comparing runtimes, not a frame time any one document has.

| Runtime | Sum of medians | Against the fastest |
| --- | --- | --- |
| Kotlin/Native, release | 1.17ms | 1.0x |
| JVM (HotSpot, desktop) | 2.20ms | 1.9x |
| ART, release | 2.72ms | 2.3x |
| ART, debug | 8.79ms | 7.5x |
| Kotlin/Native, debug | 11.46ms | 9.8x |

Per fixture, in microseconds, median frame:

| fixture | opcodes | Native rel | ART rel | ART dbg | Native dbg |
| --- | --- | --- | --- | --- | --- |
| watch | 81 | 16.0 | 78.9 | 267.7 | 229.1 |
| showcase | 122 | 30.2 | 92.8 | 328.0 | 392.6 |
| material | 165 | 44.7 | 156.0 | 522.5 | 581.0 |
| article | 171 | 47.5 | 172.0 | 590.1 | 610.6 |
| coffee | 235 | 64.1 | 247.5 | 742.6 | 822.5 |
| wrap | 171 | 67.5 | 164.1 | 601.3 | 753.9 |
| carousel | 104 | 141.7 | 181.2 | 460.0 | 777.2 |
| sample | 703 | 174.8 | 666.4 | 2063.4 | 2162.6 |
| parallax | 139 | 227.5 | 234.8 | 549.4 | 1291.9 |
| lazylist | 51 | 360.1 | 729.7 | 2661.8 | 3839.8 |

## The finding that matters

**A debug build costs iOS three times what it costs Android.** Kotlin/Native debug is 9.8x its own
release; ART debug is 3.2x its own release. So which pair you compare decides the answer:

- both apps as they are normally built and run — **iOS is 1.3x slower** than Android
- both apps as they would ship — **iOS is 2.3x faster** than Android

`iosApp/project.yml` runs `:embedAndSignAppleFrameworkForXcode`, which follows Xcode's
`$CONFIGURATION`. Opening the project and pressing Run gives the Debug framework, which is the
9.8x one. Nothing is wrong with that — it is what Debug is for — but it means the demo app is not
evidence about this renderer's speed on iOS, and neither is any comparison drawn from it.

Building `-configuration Release` produces a working app; it was built and run on the simulator to
check that, and it is what any measurement should use.

## What is not the bottleneck

Even on the slowest runtime the pipeline fits the frame. The worst fixture, `lazylist`, is 3.8ms
on Kotlin/Native debug — 23% of a 60Hz frame — and 0.36ms on release. Decoding is tens of
microseconds and happens once. Nothing here needs optimising to hit 60Hz; it needs building in
release.

ART is the faster of the two at decode (byte-walking, few allocations) and the slower at build
(allocating an object graph per frame). The two runtimes are not uniformly ordered.

## What is the bottleneck, and is not in those numbers

Text. `RemoteComposeCanvas` calls `rememberTextMeasurer()`, whose default cache is 8 layouts
(`DefaultCacheSize` in `TextMeasurerHelper.kt`). Two things then work against it:

- Every drawn string is laid out **twice per frame** — once in the build phase through
  `ComposeTextMetrics`, which measures at density 1 so `sp` equals document pixels, and once in
  the draw phase through `OpcodeExecutor`, at the `DrawScope`'s own density. Density is part of the
  cache key, so these are two entries, not one.
- Documents want far more than eight. Distinct `DrawText` layouts in one frame: `wrap` 50,
  `sample` 17, `article` 25, `coffee` 21, `material` 11. Only `watch`, at 3, fits.

So for most documents the cache holds nothing useful from one frame to the next and every string is
shaped again, twice, every frame. That work is done by Skia on iOS and by `android.text` on
Android, which is where the two platforms genuinely differ — and it is the one part of the frame
this benchmark deliberately excludes. Sizing the cache to the document, and giving both phases the
same density so they share entries, is the largest available win and it is a change to common code.

## Two things about the fixtures

`lazylist` is the slowest fixture per opcode by a wide margin: 51 opcodes, and the highest frame
cost of the ten. Its `LOOP` walks all 500 rows every frame and the `CONDITIONAL_OPERATIONS` inside
discards the ones off screen. That is what the upstream player does too, so it is not a defect —
but the cost is O(total rows), not O(visible rows), and the demo should not be read as showing
otherwise. A document with 5,000 rows would be ten times this.

Every frame allocates a fresh opcode list, a fresh `PaintStyle` per draw opcode, and a copy of the
whole string pool (`context.texts.toMap()` in `RemoteComposeDocument.frame`). For `sample` that is
~700 objects and a map copy per frame.

## Instrument, not conclusion

- **The Android emulator is not a measuring instrument under load.** The same build measured 8.5ms
  and 55.8ms twenty minutes apart, and an early reading of 18.1ms for ART release — reproducible
  four times running — was host contention and nothing else. Every number above was taken with
  Gradle stopped, the other simulator shut down, and the benchmark started directly through
  `adb shell am instrument` or `simctl spawn`. The first run after an install is always cold;
  discard it.
- **The emulator and the simulator are both virtualised on one Apple Silicon Mac**, which is what
  makes them roughly comparable for CPU-bound Kotlin and useless for anything touching the GPU.
- **The draw phase is not measured here at all.** Android draws through HWUI and iOS through
  Skiko/Metal, and there is no shared instrument for them. The emulator's software GPU makes its
  `gfxinfo` numbers meaningless for a real device.
- **Image decoding is a real platform difference that was not measured**: Android decodes through
  `BitmapFactory`, iOS and desktop through Skia. It is a one-off cost when a document opens.

Nothing here says what happens on real hardware. It says what the two runtimes do with the same
Kotlin, which was the question.

## Reproducing

```bash
# JVM
./gradlew desktopTest --tests "*PipelineBenchmarkTest*"

# Kotlin/Native, both configurations — release needs linking first
./gradlew linkDebugTestIosSimulatorArm64 linkReleaseTestIosSimulatorArm64
xcrun simctl spawn <device> build/bin/iosSimulatorArm64/releaseTest/test.kexe \
  --ktest_filter=com.example.remotecompose.PipelineBenchmarkTest.benchmark

# ART — testBuildType = "release" in build.gradle.kts switches which variant this builds
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.remotecompose.PipelineBenchmarkTest
adb logcat -d -s System.out:I | grep RCBENCH
```

Stop the Gradle daemon and shut down whichever device is not being measured before reading
anything off the result.

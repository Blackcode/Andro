# CascoScan

An Android app for the penetration check on a casco (shell-and-core) QC audit.

You load the casco drawing PDF, the app finds the penetrations on every sheet — sleeves, cores, wall
and slab openings — and you walk the building ticking them off. What the drawings require but the
building does not have comes out as a list of findings and a marked-up sheet you can send from site.

## What it does

**Reads the drawing set** — a multi-page PDF, rasterised sheet by sheet with the platform's own PDF
renderer — and finds the penetrations it requires: sleeves, cores, wall and slab openings. This is the
*requirement* side, what should exist. Nothing leaves the device.

**Looks at the building.** You stand where the work is, photograph the wall or slab, and the app finds
the holes that are actually there — the *real* penetrations. Tap two points a known distance apart and it
measures their diameters too.

**Says what is missing.** The holes in the photograph are matched against the penetrations the drawing
asks for in that area. Anything required and not found is a finding, positioned and sized from the
drawing. Holes that exist but were never asked for are reported as well: on a casco audit an unrequested
opening is a structural change.

**Keeps the audit.** Every penetration is a checklist row — present, missing, wrong size, wrong position,
obstructed, not sealed, not applicable — with the note and the photograph that justify the verdict.

**Produces the deliverable.** One self-contained HTML report with the marked-up sheets embedded, plus a
CSV for the office. Both state plainly what the machine was unsure about.

**Learns from you.** Correct a detection on the drawing and the shape is remembered for the rest of the
project: a drawing set is internally consistent, so one correction on sheet 3 improves sheets 4 to 20.

### The two detectors

They solve different problems and share only their primitives and their explainable scoring.

**On the drawing** the task is telling a penetration symbol from everything else circular and
rectangular on a dense sheet — column bubbles, grid marks, the digit 0 — and finding the sleeves drawn
*through* walls, which share ink with the wall and are invisible to plain shape detection.

**In the photograph** there are no symbols and no conventions: there is a grey wall, uneven light, and a
dark patch that is either a hole or a shadow. They are the same shape, the same size, as convex, and
under a work lamp as dark. What separates them is that a hole's edge is abrupt and a shadow's is not, so
edge width is measured properly and weighted to outvote everything else.

Both are described in [DETECTION.md](DETECTION.md).

## Layout

```
detection/    The detection engine. Plain Kotlin, no Android, no third-party dependencies.
              Unit-tested on a JVM: 77 tests, including an end-to-end synthetic drawing sheet
              and a simulated scan of it.
app/          The Android app: PDF rendering, Room storage, Compose UI, reporting.
```

The split is deliberate. Everything that decides whether a penetration is found is in `detection/`,
where it can be tested without an emulator; `app/` is glue, storage and UI.

## Building

### What you need

| | |
|---|---|
| JDK | 17 or 21 (AGP 8.5 requires 17+) |
| Android SDK | platform 34, build-tools 34.0.0 |
| Network | Maven Central **and** Google's Maven (`dl.google.com`) — AGP, Compose, Room and ML Kit all come from Google's repository |

### The easy way

Open the project folder in Android Studio (Ladybug or newer), let it sync — it will offer to install
the SDK bits it is missing — and press Run. That is the whole procedure.

### From the command line

Point Gradle at your SDK, either with an environment variable:

```bash
export ANDROID_HOME=$HOME/Android/Sdk        # macOS: ~/Library/Android/sdk
```

or by creating `local.properties` in the project root (this file is git-ignored, and Android Studio
writes it for you):

```properties
sdk.dir=/home/you/Android/Sdk
```

Then:

```bash
./gradlew :app:assembleDebug                 # APK -> app/build/outputs/apk/debug/
./gradlew :app:installDebug                  # build and install on a connected device
```

### Headless or CI, with no Android Studio

```bash
# Command-line tools, then the two packages this project needs.
export ANDROID_HOME=$HOME/android-sdk
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses

./gradlew :app:assembleDebug
```

### Getting an APK without setting anything up

Push the branch and GitHub Actions builds it - this is the path the project is known to build by:
`.github/workflows/build.yml` installs the SDK, runs
`:app:assembleDebug`, and attaches the APK to the run as an artifact named **cascoscan-debug-apk**
(Actions tab -> the run -> Artifacts). The same workflow runs the engine's tests in a separate job that
needs no Android SDK at all. If the build fails, the run also carries `app-build-reports`.

### Just the engine, with no Android toolchain at all

The detection engine is a plain JVM library, so it needs nothing but a JDK:

```bash
./gradlew -Pcascoscan.includeApp=false :detection:test
```

`-Pcascoscan.includeApp=false` drops the `:app` module from the build entirely, which is how the
engine's 77 tests run in a bare container. Without the flag `:app` is included and Gradle will ask for
an SDK.

### What is verified, and what is not

**The app builds.** CI assembles a debug APK from a clean checkout
([workflow](.github/workflows/build.yml)), so the whole chain resolves and compiles: AGP 8.5.2,
Compose, Room with its KSP processor, ML Kit and PDFBox. The debug APK is about 44 MB, which is what an
unshrunk debug build of `material-icons-extended` plus ML Kit's bundled Latin model plus PDFBox costs;
a release build with R8 is a great deal smaller.

The engine's 77 tests run in a separate job with no Android SDK at all, so they still report when the
app build fails.

Also checked, mechanically: manifest resource references resolve, no API newer than `minSdk` 24 is used,
and the build is free of compiler warnings.

**Not verified: nothing has been run.** No emulator, no device, no real drawing set through the UI. The
detection engine is tested thoroughly against synthetic sheets with known ground truth, but the
end-to-end path — pick a PDF, render it, detect, inspect, reconcile, export — has never executed. Treat
the first run on a real project as a shakedown, and expect the things that only appear on a device:
memory pressure on a large sheet, permission and `Uri` lifetime behaviour, camera round-trips, and how
long a twenty-sheet A0 set actually takes to scan.

`com.google.mlkit:text-recognition:16.0.1` resolved in CI, so the OCR fallback is real. If you ever need
to drop it, delete that line from `app/build.gradle.kts` and the `fromOcr` function in
`pdf/TextSources.kt`; nothing else depends on it, and detection falls back to the PDF's own text layer.

## Using it

1. **Add the drawing set** and give it the plot scale. The scale is what turns a 17-pixel ring into "a
   110 mm sleeve", and it is the biggest single lever on accuracy; the dialog tells you the smallest
   opening still findable at each scale.
2. **Scan the sheets.** Each is rasterised in tiles, so an A0 plan at 300 dpi need not fit in memory.
3. **Review.** Detections the app is unsure about are ringed with a dashed line. Tap one to see why it
   was flagged, term by term; reject it and the app learns. Exclude the title block if it produces
   rubbish, and re-scan.
4. **Go to the wall and press the camera button.** Four steps: tap the drawing where you are standing,
   photograph the wall, tap two points a known distance apart, then check the matches and record them.
   - Locating yourself is what makes the comparison mean anything — only the penetrations near that point
     are compared, so the rest of the floor is not reported missing.
   - Calibration is offered, not demanded. Skip it and the app still says which holes are there; it
     simply makes no claim about their size.
   - Choose **along a wall** or **slab from above**. A plan draws a wall as a line, so a hole's height
     does not exist on the drawing and the two images cannot be lined up; the holes are matched in order
     along the wall instead. For a slab shot from above they can be lined up, and are.
5. **Anything the camera missed** can be added by tapping the drawing, and any row can be set by hand.
6. **Build the report** and share it.

## Honest limitations

**On the photographic side:**

- **Shadows are rejected by edge sharpness, so anything that blurs edges hurts.** Motion blur, a dirty
  lens, heavy noise reduction in low light, or a photograph taken from far away all narrow the gap
  between a hole's edge and a shadow's. Stand reasonably close, hold still, and light the wall.
- **Sizes need calibration, and it is per photograph.** Millimetres per pixel depends on how far away you
  were, so every shot needs its own two taps. Without them, holes are still found and still matched; no
  size is reported and no size finding is raised.
- **A steeply angled shot measures badly.** The major axis of the projected ellipse is the true diameter,
  so an off-axis photograph still gives a size — but past about 60° the app marks the reading indicative
  and withholds size findings rather than accusing anyone on the strength of it.
- **Matching along a wall cannot tell identical holes apart.** If four identical sleeves are required and
  you photograph three, order and size alone cannot say which is absent; the app pairs what it can and
  you resolve the rest. Differing sizes, or a slab shot that can be lined up, remove the ambiguity.
- **Colour is not used.** The detector works in greyscale, so a sleeve is recognised by its collar rather
  than by being orange.
- **A hole with light coming through it is not handled.** Detection looks for dark regions; an opening
  onto a brightly lit room reads as bright and will be missed.

**On the drawing side:**

- Coarse plot scales genuinely lose small openings. At 1:200, 300 dpi puts a 110 mm sleeve at 6.5 pixels,
  below what any shape measurement can resolve. The app says so rather than quietly missing them.
- Symbol conventions vary by office. The scoring weights are the tunable surface, and the prototype
  learning adapts within a few corrections; a set using a form the classifier has no shape for will need
  a weight change.
- A scanned sheet is harder than a CAD export: measured on a simulated scan, with OCR text all eight test
  penetrations are still found; with no text at all, seven of eight survive and up to two pieces of
  clutter can be accepted. Blur also inflates measured sizes by 10–15%, so declared label sizes are the
  ones to trust on a scan.
- Non-axis-aligned structure keeps more wall ink in the symbol pass, so a building on a 30° grid produces
  more candidates for review.

**Throughout:** nothing is claimed to be exhaustive. The report separates what a person confirmed from
what the machine guessed, and never presents an unreviewed detection as a finding.

## Privacy

Everything is on the device: the drawings, the photographs, the audit and the report. There is no
network code in the app, and no analytics.

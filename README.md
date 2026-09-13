# CascoScan

An Android app for the penetration check on a casco (shell-and-core) QC audit.

You load the casco drawing PDF, the app finds the penetrations on every sheet — sleeves, cores, wall
and slab openings — and you walk the building ticking them off. What the drawings require but the
building does not have comes out as a list of findings and a marked-up sheet you can send from site.

## What it does

**Reads the drawing set.** A multi-page PDF, rasterised sheet by sheet with the platform's own PDF
renderer. Nothing leaves the device.

**Finds the penetrations.** Not "finds circles" — the detector distinguishes a sleeve symbol from the
dimension text, the column, the grid bubble and the title block, and it finds symbols drawn *across*
walls, which plain shape detection cannot see at all because they are part of the wall's own ink. Each
find carries a confidence and an explanation of how it got there. See [DETECTION.md](DETECTION.md).

**Works out what is missing.** Two sheets are aligned and compared: everything the first sheet
requires and the second does not contain is reported as missing, positioned where it should have been.
Openings that exist but were never asked for are reported too — on a casco audit an unrequested hole
is a structural change.

**Keeps the audit.** Every penetration is a checklist row: present, missing, wrong size, wrong
position, obstructed, not sealed, not applicable. With a note and site photographs.

**Produces the deliverable.** A single self-contained HTML report with the marked-up sheets embedded,
plus a CSV for the office. Both state plainly what the machine was unsure about.

**Learns from you.** Confirm or reject a detection and the shape is remembered for the rest of the
project. A drawing set is internally consistent, so one correction on sheet 3 improves sheets 4 to 20.

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

### Just the engine, with no Android toolchain at all

The detection engine is a plain JVM library, so it needs nothing but a JDK:

```bash
./gradlew -Pcascoscan.includeApp=false :detection:test
```

`-Pcascoscan.includeApp=false` drops the `:app` module from the build entirely, which is how the
engine's 77 tests run in a bare container. Without the flag `:app` is included and Gradle will ask for
an SDK.

### First build

Expect the `:app` module to need a few fixes. It was written in an environment with no Android SDK and
no access to Google's Maven repository, so **it has never been compiled** — the engine is the part that
is verified. Everything that decides whether a penetration is found is in `:detection:test`, which is
green.

If a compile error does appear it will be a signature or import mismatch against your AGP/Compose
versions, not a design problem. The one dependency coordinate that could not be checked against a live
repository is `com.google.mlkit:text-recognition` (the OCR fallback for scanned sheets); if it fails to
resolve, delete that line from `app/build.gradle.kts` and the `fromOcr` function in
`app/src/main/kotlin/com/blackcode/cascoscan/pdf/TextSources.kt`. Nothing else depends on it — detection
falls back to the PDF's own text layer, which is what a CAD export always has.

## Using it

1. **Add the drawing set** and give it the plot scale. The scale is not a formality — it is what turns
   a 17-pixel ring into "a 110 mm sleeve", and it is the single biggest lever on accuracy. The dialog
   tells you the smallest opening that can still be found at each scale.
2. **Scan the sheets.** A sheet is rasterised in tiles, so an A0 plan at 300 dpi does not have to fit
   in memory. Progress is per tile.
3. **Review.** Detections the app is unsure about are ringed with a dashed line and listed under
   *Unsure*. Tap one to see why it was flagged, term by term. Reject it and the app learns.
   If the title block or a legend is producing rubbish, exclude that area and re-scan.
4. **Compare sheets** to find what is missing. The app tries to line the two sheets up by itself and
   tells you how well it fitted; if the fit is poor — which is likely when the second sheet is missing
   most of its openings — tap two common points instead, such as a pair of grid intersections.
5. **Walk the building**, filtering to *To inspect*, and record what you find. Add anything the scan
   missed by tapping the drawing.
6. **Build the report** and share it.

## Honest limitations

- **Nothing is claimed to be exhaustive.** The report distinguishes what a person confirmed from what
  the machine guessed, and never presents an unreviewed detection as a finding. A penetration the
  detector missed entirely cannot be reported — which is why adding one by hand is a first-class
  action, and why the review step matters.
- **Coarse plot scales genuinely lose small openings.** At 1:200, 300 dpi puts a 110 mm sleeve at 6.5
  pixels, which is below what any shape measurement can resolve. The app says so rather than quietly
  missing them.
- **Symbols conventions vary by office.** The scoring weights are the tunable surface
  (`ScoringWeights`), and the prototype learning adapts to a set within a few corrections. A drawing
  set using a symbol the classifier has no form for (say, a filled triangle) will need a weight change.
- **A scanned sheet is harder than a CAD export.** Measured on a simulated scan of the test sheet
  (illumination gradient, grain, 3x3 blur): with the text layer recovered by OCR, all eight
  penetrations are still found and accepted with nothing spurious; with no text at all, seven of eight
  survive and up to two pieces of clutter can be accepted. Blur also inflates measured sizes by
  10-15%, so declared label sizes are the ones to trust on a scan.
- **Cross-sheet comparison needs both sheets to be scanned first**, and its verdicts are only as good
  as the alignment — which is why the alignment quality is shown rather than hidden.
- Rotated (non-axis-aligned) structure is handled for symbols but the structural-line pass looks for
  axis-aligned runs, so a building on a 30° grid will keep more of its wall ink in the symbol pass and
  produce more candidates for review.

## Privacy

Everything is on the device: the drawings, the photographs, the audit and the report. There is no
network code in the app, and no analytics.

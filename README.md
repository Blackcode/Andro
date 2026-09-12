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

Requires the Android SDK (API 34) and JDK 17+.

```bash
./gradlew :app:assembleDebug      # or open the project in Android Studio
```

The engine builds and tests anywhere a JDK exists, with no Android SDK at all:

```bash
gradle -Pcascoscan.includeApp=false :detection:test
```

`settings.gradle.kts` includes `:app` only when it can find an Android SDK, so the engine's tests keep
working in a bare CI container. Force either way with `-Pcascoscan.includeApp=true|false`.

### State of the build

The engine is compiled and its full test suite is green. **The `app` module has not been compiled**:
it was written in an environment with no Android SDK and no access to Google's Maven repository, so
AGP, Compose, Room and ML Kit could not be resolved. Expect to fix the ordinary things a first build
turns up. The one dependency coordinate that could not be checked against a repository is
`com.google.mlkit:text-recognition` (the OCR fallback for scanned sheets) — if it does not resolve,
deleting that line from `app/build.gradle.kts` and `TextSources.fromOcr` removes the feature and
nothing else: detection falls back to the PDF's own text layer, which is what a CAD export always has.

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

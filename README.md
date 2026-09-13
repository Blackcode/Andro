# CascoScan

An augmented-reality app for the penetration check on a casco (shell-and-core) QC audit.

Load the casco drawing PDF, hold the phone up to the wall, and the penetrations the drawing requires
appear **on the real wall** where they should be. The ones that are there get confirmed and measured; the
ones that are not stay on the wall as red crossed rings until you record them as findings.

## What it does

**Reads the drawing set** — a multi-page PDF, rasterised sheet by sheet with the platform's own PDF
renderer — and finds the penetrations it requires: sleeves, cores, wall and slab openings. This is the
*requirement* side. Nothing leaves the device.

**Pins the drawing to the room.** You aim at something you can also find on the sheet — a column face, a
door reveal, a grid intersection — tap to fix it, then tap the same feature on the drawing. Twice. From
then on ARCore tracks the phone and every required penetration is anchored to its real position.

**Shows you where every hole should be.** Look through the screen and they are on the wall: labelled with
their reference and diameter, sized correctly for how far away they are, and colour-coded by what you have
recorded so far. A missing one is a red crossed ring hanging in exactly the wrong empty space.

**Finds and measures the real ones.** Press *Check this view* and the app detects the holes actually in
front of it, works out where in the room each one is, and measures its diameter — with **no calibration
step at all**, because a tracked session already knows how far away the wall is.

**Says what is missing.** Both sides are now in the same metric space, so the comparison is simply
whether a required penetration and a real hole are in the same place to within centimetres. Anything
required and not found is a finding. Holes that exist but were never asked for are reported too: on a
casco audit an unrequested opening is a structural change.

**Keeps the audit** — a checklist row per penetration, present / missing / wrong size / wrong position /
obstructed / not sealed, with the note and evidence behind each verdict — and **produces the deliverable**:
one self-contained HTML report with the marked-up sheets embedded, plus a CSV for the office.

**Without AR as well.** ARCore supports a limited set of devices, so the photographic flow is still there:
photograph a wall, tap two points a known distance apart, and get the same comparison without tracking.
It is the fallback, not the main event.

### The three engines

### The two detectors

They solve different problems and share only their primitives and their explainable scoring.

**Putting the drawing in the room** is a smaller problem than it looks: the plot scale already fixes the
scale and gravity fixes the tilt, so two tapped points determine the rest. What has to be got right is the
orientation convention, because two points cannot tell a reflection from a rotation and a mirrored plan
puts every marker on the wrong side of the room.

**On the drawing** the task is telling a penetration symbol from everything else circular and
rectangular on a dense sheet — column bubbles, grid marks, the digit 0 — and finding the sleeves drawn
*through* walls, which share ink with the wall and are invisible to plain shape detection.

**In the photograph** there are no symbols and no conventions: there is a grey wall, uneven light, and a
dark patch that is either a hole or a shadow. They are the same shape, the same size, as convex, and
under a work lamp as dark. What separates them is that a hole's edge is abrupt and a shadow's is not, so
edge width is measured properly and weighted to outvote everything else.

All three are described in [DETECTION.md](DETECTION.md).

## Layout

```
detection/    The engines: drawing symbols, photographic hole detection, and the augmented-reality
              geometry. Plain Kotlin, no Android, no third-party dependencies - 129 tests on a JVM,
              including a synthetic drawing sheet, a simulated scan of it, and an independently
              written forward model for the AR registration.
app/          The Android app: ARCore, PDF rendering, Room storage, Compose UI, reporting.
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

1. **Add the drawing set** and give it the plot scale. In AR the scale is doubly load-bearing: it decides
   both what counts as a plausible symbol and how the plan is sized into the room.
2. **Scan the sheets** to get the requirement list. Review anything the app was unsure about.
3. **Stand in the room and open the AR view.** Aim at a feature you can find on the sheet, press *Fix
   point*, then tap that feature on the drawing. Do it twice, as far apart as you can manage. The app
   reports the fit in centimetres and refuses to pretend a bad one is good.
4. **Walk the wall.** The required penetrations are on it. Press *Check this view* where you want a
   verdict; the app measures what is there and proposes present / missing / wrong size, which you record
   or discard.
5. **Tell it whether you are looking at walls or the slab.** A plan cannot say how high up a wall a sleeve
   is, so wall markers float at your own height with a vertical guide; slab penetrations are placed exactly.
6. **Anything the app missed** can still be added by tapping the drawing, and any row set by hand.
7. **Build the report** and share it.

## Honest limitations

**On the augmented reality:**

- **ARCore supports a limited set of devices.** The manifest marks AR optional, so the app installs
  anywhere and falls back to the photographic flow; it will tell you if the device cannot do AR.
- **Everything rests on the registration.** Two reference points far apart on identifiable features give a
  good fit; two close together, or a mis-tap, and every marker is out by the same error. The app reports
  the residual in centimetres and the implied-versus-declared scale ratio, and refuses to call a bad fit
  trustworthy — but it cannot detect that you tapped the wrong column.
- **Tracking drifts.** ARCore is good but not perfect: over a long walk the overlay can creep. Re-placing
  from two fresh points takes seconds and is worth doing per room rather than per floor.
- **A plan does not know heights.** Wall markers are floated at your own height with a vertical guide
  rather than pinned, and wall penetrations are matched on horizontal position alone. That is the drawing's
  limitation, not a shortcut.
- **AR needs texture and light.** A bare, evenly-lit plasterboard wall gives the tracker little to hold
  onto; it will say when tracking is poor rather than quietly drifting.
- **Depth error carries straight into diameter.** Size comes from the surface distance, so a 5% error in
  depth is 5% on the bore. The app warns when a wall was seen more than about 55° off square.

**On detecting the real holes:**

- **Shadows are rejected by edge sharpness, so anything that blurs edges hurts** — motion blur, a dirty
  lens, low light. Get reasonably close and hold still.
- **A hole with light coming through it is not handled.** Detection looks for dark regions; an opening onto
  a brightly lit room reads as bright and will be missed.
- **Colour is not used.** A sleeve is recognised by its collar rather than by being orange.
- **One surface per check.** Positions come from casting rays onto the single surface under the crosshair,
  which is what a wall or a slab is — but a view spanning two planes at different depths will place some
  holes wrongly.

**On the drawing side:**

- Coarse plot scales genuinely lose small openings. At 1:200, 300 dpi puts a 110 mm sleeve at 6.5 pixels,
  below what any shape measurement can resolve. The app says so rather than quietly missing them.
- Symbol conventions vary by office. The scoring weights are the tunable surface, and the prototype
  learning adapts within a few corrections.
- A scanned sheet is harder than a CAD export: with OCR text all eight test penetrations are still found;
  with no text at all, seven of eight survive and up to two pieces of clutter can be accepted.
- Non-axis-aligned structure keeps more wall ink in the symbol pass, so a building on a 30° grid produces
  more candidates for review.

**Throughout:** nothing is claimed to be exhaustive. The report separates what a person confirmed from what
the machine guessed, and never presents an unreviewed detection as a finding.

## Privacy

Everything is on the device: the drawings, the photographs, the audit and the report. There is no
network code in the app, and no analytics.

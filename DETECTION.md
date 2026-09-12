# How the detection works

This is the part of the app that has to earn trust, so here is exactly what it does and why.

The problem is not "find circles in an image". A casco drawing is dense line art in which a great many
things are circular, rectangular, and the right size to be an opening: column bubbles, grid marks,
socket symbols, level markers, the digit 0. Meanwhile the penetrations that matter most are often *not*
free-standing shapes at all — a sleeve drawn through a wall shares ink with the wall.

## The pipeline

Implemented in `PenetrationDetector`, one sheet at a time.

### 1. Ink or paper

`Binarize.auto` measures how bimodal the page histogram is and picks accordingly: a global Otsu cut for
a CAD export (nearly all the mass sits in the two extreme bins), a local Sauvola threshold for a scan
or a photograph, where shading would make a global cut erase thin lines. Sauvola runs off integral
images, so it is O(1) per pixel regardless of window size.

### 2. Structure, set aside

`StructuralLines.lineMask` marks every pixel belonging to an axis-aligned ink run of at least 60 px —
the morphological opening with a long line kernel, computed with run lengths so it costs one pass per
axis. Those are the wall faces, slab edges, grid and dimension lines.

### 3. Blobs

8-connected labelling with union-find. Free-standing blobs of plausible size are candidates directly.

### 4. Mining the oversized blobs

This step is what makes the app work on real drawings. A wall network is one enormous connected
component, and any symbol touching it has been swallowed. For each oversized blob, `StructureAnalyzer`:

- **strips its long runs and re-labels what is left** — an embedded sleeve outline, snipped into arcs by
  the removal, is reconnected by labelling a dilated copy while keeping only the true ink
  (`labelBridged`), so the shape is not distorted the way a dilate/erode pair would distort it;
- **takes the voids it encloses** — a large rectangular opening is drawn as a gap between two wall
  faces closed off at each end, so the *opening itself* is a hole in the wall's blob, not a shape.

Rooms are enclosed voids too, and are excluded by size.

### 5. What does it look like?

`ComponentFeatures` measures, then `ShapeClassifier` names the form. Two measurement decisions carry
most of the accuracy:

**Measure the enclosed region, not the ink.** A penetration symbol is usually an outline, and the ink
statistics of a thin ring and a thin square are nearly identical. So every shape test runs on the
region the blob *encloses*, recovered by flooding the background in from outside. A disc inscribed in
its bounding box fills π/4 = 0.785 of it while a rectangle fills ~1.0; the centre-to-boundary radius of
a circle barely varies (cv ≈ 0.03) while a square's corners push it to ≈ 0.105. Both separations
disappear entirely if you measure the ink.

**Tolerate breaks in the outline.** Outlines on real drawings are not closed: dashed, clipped by a
leader line, breached where a wall line was removed in step 4, or a pixel lost to a scanner. A
single-pixel leak lets the flood into the interior and destroys every shape measurement at once. The
flood therefore runs against a morphologically *closed* copy and the result is eroded back, so gaps of
up to about 4 px no longer open a shape.

The forms recognised: circle and rectangle, each as outline, filled, hatched or crossed.
Hatching is tested before a cross and vetoes it — 45° hatching lies along the bounding-box diagonal and
saturates the cross test completely, while a two-stroke cross gives scanlines four ink runs whose
spacing shifts from line to line, which is exactly what the hatch score measures the absence of.

### 6. One symbol, one finding

`Grouping` folds interior marks into their enclosing symbol — a ring and the cross inside it are two
blobs, and reporting them separately is worse than missing the sleeve, because an auditor stops
trusting the list. Containment, not overlap, is the test: two overlapping boxes can be two real
neighbouring sleeves. Three or more sliver children are read as hatching clipped inside its outline.

### 7. Context

Repetition (symbol sets repeat; a one-off shape on a sheet of similar shapes is more often something
else), proximity to structural lines, user-excluded regions, and **which blobs are actually characters**.

That last one deserves its own note, because size cannot answer it. On a 1:50 plan a 110 mm sleeve is
2.2 mm on paper and the annotation beside it is 2.5 mm; a drawn "0" is a ring and a drawn "X" is a
cross by every shape measure available. What differs is *company*: characters sit on a shared baseline
with gaps smaller than their own height, while penetration symbols are spread across the plan.
`TextRuns` finds those rows geometrically, so it works on a scan with no text layer — precisely when it
is needed.

Two exclusions are load-bearing, both because of one confusion: a wall opening flanked by its two
closing lines is the same height, on the same baseline, and touching — a perfect three-character run by
every other test. Slivers cannot be run members, which handles it on a clean drawing; but a scan
thickens those closing lines until they are no longer slender, so members must also be **comparable in
width**. That one holds at any scale and on any quality of input, because an opening is by definition
many times wider than the lines that close it, whereas an "i" beside a "W" is about as far as real
lettering stretches.

### 8. The annotations

`LabelParser` reads the vocabulary casco sets actually use — `Ø110`, `D=125`, `DN200`, `300x200`,
`SP-14`, and keywords in Dutch, German, English and French, because one project routinely mixes two.
`LabelMatcher` assigns each label to at most one symbol, nearest-first, so one label never counts as
evidence for two penetrations.

A label that declares a diameter also reveals the **scale**: the ratio between the declared millimetres
and the measured pixels *is* mm-per-pixel, and the median over many such pairs shrugs off a
mis-association (`ScaleEstimator`). An estimate is only used unattended when several symbols agree.

### 9. Scoring

A weighted sum of evidence in logit units, through a logistic:

| Term | Default weight | What it says |
|---|---|---|
| bias | −1.70 | most ink on a drawing is not a penetration |
| shape prior | −0.80 … +2.00 | a crossed ring is strong; a plain filled rectangle is almost nothing |
| size | ±1.20 | plausible real-world size — *necessary*, not sufficient |
| label | +2.40 | the strongest single cue the drawing offers |
| size agreement | ±1.40 | does the measurement match the label |
| structure context | +0.85 / +1.10 | recovered from inside a wall, or a void it encloses |
| repetition | +0.70 | the symbol occurs elsewhere on the sheet |
| learned | ±1.60 | resembles what you confirmed, or what you rejected |
| annotation | −2.60 | it is a character |
| slenderness | −2.00 | a sliver is not an opening |
| sprawl | −1.10 | encloses nothing; ink merely passing through |
| excluded region | −3.50 | inside the title block |

A linear model rather than something learned, for three reasons: there is no labelled corpus of casco
drawings to train on; an auditor signs the report and is entitled to see why something was flagged,
term by term, which the app shows them; and every weight has to be adjustable by an engineer whose
office draws sleeves differently.

Two thresholds, and nothing plausible is ever dropped silently: above `acceptThreshold` (0.55) a
detection is presented as found, between that and `reviewThreshold` (0.28) it is queued for review with
a dashed ring, and below that it is discarded.

### 10. Reading order

Ids are assigned top-to-bottom, left-to-right in horizontal bands, so the checklist matches the way
someone walks a floor.

## Tiling

A symbol needs ~12 px across before its form can be measured, so pages are rendered at 200–300 dpi —
several hundred megabytes for an A0 sheet. `TiledPenetrationDetector` runs the pipeline over
overlapping tiles with a bounded raster budget, discards any candidate touching an interior seam
(guaranteed to sit whole inside the neighbouring tile as long as it is smaller than the overlap),
de-duplicates the overlap band and renumbers in page reading order. A test asserts tiled and
single-pass detection agree, penetration for penetration.

`RenderPlan` picks the resolution from the plot scale and reports the smallest size that survives it,
so the app can tell the user what it will miss instead of missing it quietly.

## Finding what is missing

`PointSetAligner` fits the transform between two sheets by RANSAC over point *pairs* — two
correspondences determine a similarity transform exactly, and pairings are pruned by requiring similar
drawn sizes and an implied scale in range. `Reconciler` then matches greedily, shortest links first,
each penetration used once, and reports what is left over on each side.

Sizes are compared declared-against-declared or measured-against-measured, **never mixed**: a label
states the clear opening while a measurement reads the drawn outline including its line weight, and on
a small symbol that difference alone exceeds any sensible tolerance. Mixing them manufactures findings.

Automatic alignment cannot be relied on in the very case the app exists for — a sheet missing most of
its openings has little to lock onto — so the app reports the fit quality and offers control points.
A reference detection below 0.5 confidence never raises a finding: an unreviewed guess must not accuse
the builder.

## Accuracy, as measured

There is no public corpus of casco drawings, so the suite draws its own sheet
(`SyntheticSheet`) containing eight penetrations across six drawn forms — free-standing crossed rings,
a labelled ring inside a wall band, a ring on a grid line that only structural-line removal can
recover, an opening enclosed by wall faces, a hatched recess and a crossed rectangle — plus the clutter
that produces false positives: a solid column of penetration-like size, three runs of dimension text
and room names (some of it cross-shaped), a title block, and the fragments left over from chopping up
structural lines.

The tests assert **full recall and no accepted false positive**, both for single-pass and tiled
detection, and that the two agree penetration for penetration.

The same sheet is then put through a simulated scan — illumination gradient, grain, and a 3x3 blur —
because paper arrives as often as CAD does, and a scan attacks three different parts of the pipeline at
once while also removing the text layer. Measured there: with text (as OCR would supply it) nothing is
lost and nothing spurious is accepted; with no text at all, seven of eight survive and up to two pieces
of clutter reach the accept threshold, since annotation can then only be ruled out geometrically. Blur
also inflates measured sizes by 10–15%, which is why a declared label size is the one to trust on a
scan and why the two are never compared against each other.

That is a floor, not a field accuracy figure: real drawings are messier, and the review step and
prototype learning exist because of it.

Two bugs the scan test caught, as illustrations of what it is for:

- a **negative shape prior was being scaled by how well the blob fitted that form**, so an
  unrecognisable blob — the one case the prior exists for — received no penalty at all and could be
  accepted on plausible size alone. Positive priors are scaled by fit; negative ones are now applied in
  full, because a worse fit argues *harder* against the candidate;
- **void detection was not gap-tolerant** like the rest of the pipeline, so one lost pixel in the short
  line closing off a wall opening meant the opening stopped being enclosed and vanished. The flood now
  runs against a sealed copy and the void is dilated back, the same trick the shape measurements use.

## Tuning

`DetectionConfig` holds the geometric limits (plausible sizes, run lengths, gap tolerances, thresholds)
and `ScoringWeights` the evidence weights. Both are plain data classes with documented defaults, passed
in rather than referenced globally, so a project-level override is a `copy()`.

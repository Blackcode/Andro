# How the detection works

Two detectors, solving two different problems: reading the requirement off the drawing, and finding what
was actually built. [Jump to the photographic one](#finding-penetrations-in-a-photograph).

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


---

# Finding penetrations in a photograph

The drawing says what should exist. This says what does.

It is not the same problem as reading line art, and almost none of the reasoning carries over. There are
no symbols and no conventions. There is a grey wall, light falling unevenly across it, and a dark patch
that is either a hole or a shadow — and a shadow is the same shape as a hole, the same size, just as
convex, and under a single work lamp very nearly as dark.

## Removing the light, rather than compensating for it

`GrayMorphology.blackTopHat` closes the image with a kernel wider than the largest hole. Closing fills
dark features smaller than its kernel, so the result is the wall as it would look with no holes in it —
the illumination field. Subtracting the photograph from that leaves the holes against a flat zero however
the wall happened to be lit.

A locally adaptive threshold cannot do this job. With a window smaller than the hole, the middle of a
200 mm hole has nothing bright nearby to be dark *relative to*, so it comes out hollow or vanishes
entirely. Both morphology passes are separable and use a monotonic deque, so a deliberately huge kernel
still costs one pass per axis.

## Measuring a hole from wherever you were standing

`Ellipse.fromMoments` fits the equivalent ellipse of inertia — for a uniform ellipse the central moments
are `mu20 = a²/4` and `mu02 = b²/4`, so the eigenvalues of the covariance matrix give the axes back as
`2·sqrt(lambda)`. Moments use every pixel rather than just the outline, which is far steadier on a ragged
boundary than fitting a conic to edge points.

The geometry that makes this practical: **a circular hole photographed off-axis projects to an ellipse
whose major axis is still the true diameter.** Tilt foreshortens one direction only, so the minor axis
shrinks by cos(tilt) and the major axis is untouched. So a usable diameter comes out of a shot taken from
wherever the auditor happened to be, and the axis ratio recovers how far off-axis that was — worth
knowing in itself, because past about 60° the app marks the reading indicative and withholds size
findings.

## The one measurement that matters

Every term in the scoring model can be fully satisfied by a hard shadow: it is round, convex, the right
size, far darker than the wall, and dark in absolute terms. **Edge width is the only thing that separates
them**, so it is measured properly and weighted to outvote the rest of the model combined.

`EdgeProfile` samples the intensity along the outward normal at 48 points around the boundary and records
the distance over which it rises from 20% to 80% of the local range — the optical definition of edge
width — taking the median so one occluded neighbour cannot drag the reading.

The obvious shortcut, contrast divided by boundary gradient, was tried first and was nearly inert: the
ring used as the "outside" reference still sits inside the penumbra, so a shadow edge measured about a
third of its real width. Worth recording because the thresholds are stated in the units actually
measured: a 20–80% rise is roughly 0.43 of the full width of a smooth transition, so an edge a human
would call twelve pixels wide measures about five here. A void's edge comes in under 2 px even through
lens and JPEG softening; a penumbra runs from 4 px upwards.

## What else the scoring uses

| Term | Weight | What it says |
|---|---|---|
| bias | −3.20 | most dark patches on a site are not holes |
| hard edge | ±4.00 | the decisive one, above |
| shape fit | +2.00 | matches its own fitted ellipse, or its oriented box |
| shapeless | −1.60 | matches neither, applied in full rather than scaled by the fit |
| contrast | ±1.10 | darker than the wall |
| absolute darkness | ±1.20 | you can see *into* a hole; concrete texture is never near-black |
| solidity | +1.00 | convex, not a ragged stain |
| size | ±1.20 | plausible in millimetres, once calibrated |
| sleeve ring | +0.60 | a collar, and only on a hard-edged opening |
| elongation | −2.20 | plus a hard gate below a 0.12 axis ratio: cracks and joints |
| clipped | −1.30 | only partly in frame, so shape and size are guesses |

Round versus rectangular is decided by which the region actually matches: a solid ellipse fills its own
fitted ellipse and only about 79% of its oriented box, and a solid rectangle does the reverse.

## Deciding what is missing

`SiteMatcher` compares the holes in a photograph with the penetrations the drawing requires *near where
the auditor said they were standing* — without that narrowing the comparison is meaningless, since three
holes would be matched against the whole sheet and the rest all reported missing.

Two strategies, because neither works everywhere:

- **Planar** fits a similarity transform from photograph to drawing by RANSAC. Right when the photograph
  really is a flat view of the plane the drawing shows — a slab from above. With both sides calibrated the
  scale is not a free parameter (it is `photoMmPerPx / drawingMmPerPx`), which is what lets RANSAC lock on
  from two or three shared holes.
- **Order and size** matches the two sequences along their dominant axis with no registration at all.
  This is what a wall needs: the plan draws that wall as a line, so a hole's height does not exist on the
  drawing and no 2-D transform can be fitted. Order is a real constraint — holes cannot swap places
  between the drawing and the building.

The sequence match is **scored, not counted**, and that distinction decides real cases. Given a wall
wanting 110, 160, 200, 110 and a photograph showing 110, 200, 110, any tolerance loose enough to absorb
photographic error also lets 160 pair with 200 — so two readings tie on the number of pairs and the wrong
one can win. Maximising total size agreement against a gap cost prefers the reading where every pair
matches well, and correctly names the 160 as the hole nobody drilled. The gap cost follows from the
tolerance rather than being tuned, and brings a property worth having: a hole bored to the wrong size
stays paired and is reported as the wrong size, instead of becoming a missing hole *and* an unrequested
one at the same spot.

Findings never rest on a measurement that cannot carry them. A size finding is withheld when the hole was
photographed too far off-axis to measure, a detection too faint to trust is not evidence that a hole
exists, and an empty photograph warns rather than quietly condemning a whole wall.

## Accuracy, as measured

Photographs of real buildings cannot go in a repository either, so the suite paints its own wall — and
what matters is that it contains the *confusions* rather than just the targets. A detector that only sees
holes on flat grey passes any test and fails on site.

The synthetic wall carries: a hole and a shadow of identical size, darkness and shape side by side; a
construction joint; a ragged damp patch; correlated concrete grain; light falling off across the frame;
and holes photographed both square-on and at 60°.

Asserted: holes are found and measured to within a few percent, the off-axis one included; shadows,
joints, stains and bare texture are all rejected; the same hole measures the same in the lit corner and
the shaded one; an uncalibrated photograph reports no size at all; and a blank wall reports nothing.

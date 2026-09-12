package com.blackcode.cascoscan.detect

/**
 * A piece of text on the page with its position in page-pixel space.
 *
 * Two very different things implement the provider that yields these: a PDF text extractor (exact,
 * available whenever the drawing is a real CAD export) and OCR (needed for scans). The engine does
 * not care which - it only needs text with boxes.
 */
data class TextBox(val text: String, val box: IBox) {
    val center: Pt get() = box.center
}

/** Source of page text. [NoText] is a first-class option: detection degrades, it does not break. */
interface TextProvider {
    fun textFor(pageIndex: Int): List<TextBox>

    object NoText : TextProvider {
        override fun textFor(pageIndex: Int): List<TextBox> = emptyList()
    }
}

/** A label understood well enough to use as evidence. */
data class ParsedLabel(
    val raw: String,
    val box: IBox,
    /** Nominal diameter in mm, from "Ø110", "D=110", "dia 110". */
    val diameterMm: Double? = null,
    /** Rectangular nominal size in mm, from "300x200". */
    val widthMm: Double? = null,
    val heightMm: Double? = null,
    /** A penetration keyword was present ("sparing", "doorvoer", "Durchbruch", "sleeve", ...). */
    val keyword: String? = null,
    /** A tag such as "SP-014" that the report should carry through verbatim. */
    val tag: String? = null,
) {
    val declaredSizeMm: Double?
        get() = diameterMm ?: listOfNotNull(widthMm, heightMm).maxOrNull()

    val sizeText: String?
        get() = when {
            diameterMm != null -> "Ø${diameterMm.toInt()}"
            widthMm != null && heightMm != null -> "${widthMm.toInt()}x${heightMm.toInt()}"
            else -> null
        }

    /** How much this label alone argues for a penetration, before distance is taken into account. */
    val strength: Double
        get() {
            var s = 0.0
            if (keyword != null) s += 0.75
            if (diameterMm != null) s += 0.55
            if (widthMm != null && heightMm != null) s += 0.45
            if (tag != null) s += 0.35
            return s.coerceAtMost(1.0)
        }

    val isInformative: Boolean get() = keyword != null || diameterMm != null || tag != null ||
        (widthMm != null && heightMm != null)
}

/**
 * Reads the annotations that sit next to penetration symbols on casco drawings.
 *
 * The vocabulary is deliberately multilingual: casco/"sparing" is Dutch-Belgian usage,
 * "Durchbruch"/"Aussparung" German, and English-language projects use sleeve/penetration/opening.
 * A drawing set on one project routinely mixes two of these.
 */
object LabelParser {

    /** Keyword -> the family it belongs to. Matching is case-insensitive and diacritic-tolerant. */
    private val keywords: List<String> = listOf(
        // Dutch / Flemish
        "sparing", "sparingen", "vloersparing", "wandsparing", "dakdoorvoer",
        "doorvoer", "doorvoering", "mantelbuis", "kernboring", "leidingschacht", "sleuf",
        // German
        "durchbruch", "aussparung", "kernbohrung", "schlitz", "huelse", "hulse", "wanddurchbruch",
        // English
        "penetration", "sleeve", "opening", "coring", "core hole", "chase", "duct opening", "void",
        // French
        "reservation", "percement", "trémie", "tremie",
    )

    // "DN" is matched before a bare "D" so that DN200 is not read as D + "N200"; a bare D needs a
    // separator or whitespace before the number, which keeps it off ordinary drawing text.
    private val diameterRegex = Regex(
        """(?:Ø|⌀|\bdia\b|\bDN|\bD)\s*[:=]?\s*(\d{2,4})(?:\s*mm)?""",
        RegexOption.IGNORE_CASE,
    )
    private val dimensionRegex = Regex("""\b(\d{2,4})\s*[x×*]\s*(\d{2,4})\b""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\b(SP|VS|WS|DV|PN|DB|AS)[\-\s.]?(\d{1,4})\b""", RegexOption.IGNORE_CASE)

    fun parse(textBox: TextBox): ParsedLabel? {
        val raw = textBox.text.trim()
        if (raw.isEmpty()) return null
        val normalised = normalise(raw)

        val keyword = keywords.firstOrNull { normalised.contains(it) }
        val diameter = diameterRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val dims = dimensionRegex.find(raw)
        val width = dims?.groupValues?.get(1)?.toDoubleOrNull()
        val height = dims?.groupValues?.get(2)?.toDoubleOrNull()
        val tag = tagRegex.find(raw)?.let { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}" }

        val label = ParsedLabel(
            raw = raw,
            box = textBox.box,
            diameterMm = diameter?.takeIf { it in 10.0..4000.0 },
            widthMm = width?.takeIf { it in 10.0..6000.0 },
            heightMm = height?.takeIf { it in 10.0..6000.0 },
            keyword = keyword,
            tag = tag,
        )
        return label.takeIf { it.isInformative }
    }

    fun parseAll(boxes: List<TextBox>): List<ParsedLabel> = boxes.mapNotNull(::parse)

    /** Lowercase, strip accents, collapse whitespace - so "Trémie" and "TREMIE" both match. */
    private fun normalise(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            sb.append(
                when (ch) {
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'à', 'á', 'â', 'ä' -> 'a'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ó', 'ò', 'ô', 'ö' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    else -> ch
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }
}

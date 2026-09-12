package com.blackcode.cascoscan.data

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.DetectionConfig
import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.Evidence
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.Origin
import com.blackcode.cascoscan.detect.ParsedLabel
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.PrototypeLibrary
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.SizeMm
import com.blackcode.cascoscan.detect.Similarity
import com.blackcode.cascoscan.detect.SymbolKind

/**
 * Translation between the stored rows and the engine's value types.
 *
 * Enums cross this boundary by name with an explicit fallback, never by ordinal: a reordered enum
 * must not silently turn every "missing" finding into something else.
 */
object Mappers {

    /** Row ids are scoped to the sheet, so the same display number on two sheets cannot collide. */
    fun rowId(sheetId: String, displayId: String) = "$sheetId:$displayId"

    fun toEntity(
        penetration: Penetration,
        projectId: String,
        sheetId: String,
        now: Long = System.currentTimeMillis(),
    ) = PenetrationEntity(
        id = rowId(sheetId, penetration.id),
        projectId = projectId,
        sheetId = sheetId,
        pageIndex = penetration.pageIndex,
        displayId = penetration.id,
        centerX = penetration.center.x,
        centerY = penetration.center.y,
        boxLeft = penetration.box.left,
        boxTop = penetration.box.top,
        boxRight = penetration.box.right,
        boxBottom = penetration.box.bottom,
        kind = penetration.kind.name,
        confidence = penetration.confidence,
        evidence = Codec.encodeTerms(penetration.evidence.terms),
        evidenceLogit = penetration.evidence.logit,
        sizeWidthMm = penetration.sizeMm?.width,
        sizeHeightMm = penetration.sizeMm?.height,
        labelRaw = penetration.label?.raw,
        labelDiameterMm = penetration.label?.diameterMm,
        labelWidthMm = penetration.label?.widthMm,
        labelHeightMm = penetration.label?.heightMm,
        labelKeyword = penetration.label?.keyword,
        labelTag = penetration.label?.tag,
        origin = penetration.origin.name,
        status = penetration.status.name,
        note = penetration.note,
        photoUris = Codec.encodeList(penetration.photoUris),
        requiredByPageIndex = penetration.requiredByPageIndex,
        shapeBoxFill = penetration.signature?.boxFill,
        shapeRadialCv = penetration.signature?.radialCv,
        shapeInkRatio = penetration.signature?.inkRatio,
        shapeHatchScore = penetration.signature?.hatchScore,
        shapeDiagonal = penetration.signature?.diagonal,
        updatedAt = now,
    )

    fun toModel(entity: PenetrationEntity): Penetration {
        val box = IBox(entity.boxLeft, entity.boxTop, entity.boxRight, entity.boxBottom)
        val terms = Codec.decodeTerms(entity.evidence)
        return Penetration(
            id = entity.displayId,
            pageIndex = entity.pageIndex,
            center = Pt(entity.centerX, entity.centerY),
            box = box,
            kind = enumOrDefault(entity.kind, SymbolKind.UNKNOWN),
            confidence = entity.confidence,
            evidence = Evidence(
                terms = terms,
                bias = 0.0,
                logit = entity.evidenceLogit,
                confidence = entity.confidence,
            ),
            sizeMm = if (entity.sizeWidthMm != null && entity.sizeHeightMm != null) {
                SizeMm(entity.sizeWidthMm, entity.sizeHeightMm)
            } else {
                null
            },
            label = toLabel(entity, box),
            origin = enumOrDefault(entity.origin, Origin.DETECTED),
            status = enumOrDefault(entity.status, AuditStatus.PENDING),
            note = entity.note,
            photoUris = Codec.decodeList(entity.photoUris),
            requiredByPageIndex = entity.requiredByPageIndex,
            signature = toSignature(entity, box),
        )
    }

    /** Null when the row predates shape storage, or was added by hand and has no measured shape. */
    private fun toSignature(entity: PenetrationEntity, box: IBox): PrototypeLibrary.Signature? {
        val boxFill = entity.shapeBoxFill ?: return null
        return PrototypeLibrary.Signature(
            kind = enumOrDefault(entity.kind, SymbolKind.UNKNOWN),
            boxFill = boxFill,
            radialCv = entity.shapeRadialCv ?: 0.0,
            inkRatio = entity.shapeInkRatio ?: 1.0,
            hatchScore = entity.shapeHatchScore ?: 0.0,
            diagonal = entity.shapeDiagonal ?: 0.0,
            squareness = box.squareness,
            logSizeMm = entity.sizeWidthMm
                ?.let { width -> entity.sizeHeightMm?.let { height -> (width + height) / 2.0 } }
                ?.takeIf { it > 1.0 }
                ?.let { kotlin.math.ln(it) },
        )
    }

    private fun toLabel(entity: PenetrationEntity, fallbackBox: IBox): ParsedLabel? {
        val raw = entity.labelRaw ?: return null
        return ParsedLabel(
            raw = raw,
            // The label's own box is not stored: nothing downstream of detection needs it, and the
            // symbol's box is the right anchor for anything that wants to draw the annotation.
            box = fallbackBox,
            diameterMm = entity.labelDiameterMm,
            widthMm = entity.labelWidthMm,
            heightMm = entity.labelHeightMm,
            keyword = entity.labelKeyword,
            tag = entity.labelTag,
        )
    }

    fun scaleOf(sheet: SheetEntity): DrawingScale? {
        val mmPerPx = sheet.mmPerPx ?: return null
        if (mmPerPx <= 0.0) return null
        val source = sheet.scaleSource
            ?.let { name -> DrawingScale.Source.entries.firstOrNull { it.name == name } }
            ?: DrawingScale.Source.DECLARED_RATIO
        return DrawingScale(mmPerPx, source)
    }

    fun excludedRegionOf(sheet: SheetEntity): IBox? {
        val left = sheet.excludedLeft ?: return null
        val top = sheet.excludedTop ?: return null
        val right = sheet.excludedRight ?: return null
        val bottom = sheet.excludedBottom ?: return null
        if (right < left || bottom < top) return null
        return IBox(left, top, right, bottom)
    }

    fun configFor(sheet: SheetEntity, base: DetectionConfig = DetectionConfig()): DetectionConfig =
        base.copy(excludedRegions = listOfNotNull(excludedRegionOf(sheet)))

    fun toEntity(signature: PrototypeLibrary.Signature, projectId: String, wasPenetration: Boolean) =
        PrototypeEntity(
            projectId = projectId,
            wasPenetration = wasPenetration,
            kind = signature.kind.name,
            boxFill = signature.boxFill,
            radialCv = signature.radialCv,
            inkRatio = signature.inkRatio,
            hatchScore = signature.hatchScore,
            diagonal = signature.diagonal,
            squareness = signature.squareness,
            logSizeMm = signature.logSizeMm,
        )

    fun toSignature(entity: PrototypeEntity) = PrototypeLibrary.Signature(
        kind = enumOrDefault(entity.kind, SymbolKind.UNKNOWN),
        boxFill = entity.boxFill,
        radialCv = entity.radialCv,
        inkRatio = entity.inkRatio,
        hatchScore = entity.hatchScore,
        diagonal = entity.diagonal,
        squareness = entity.squareness,
        logSizeMm = entity.logSizeMm,
    )

    fun toLibrary(rows: List<PrototypeEntity>): PrototypeLibrary = PrototypeLibrary.from(
        accepted = rows.filter { it.wasPenetration }.map(::toSignature),
        rejected = rows.filterNot { it.wasPenetration }.map(::toSignature),
    )

    fun transformOf(alignment: AlignmentEntity) = Similarity(alignment.a, alignment.b, alignment.tx, alignment.ty)

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

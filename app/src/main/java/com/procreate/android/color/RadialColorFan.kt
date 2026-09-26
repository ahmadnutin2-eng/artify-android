package com.procreate.android.color

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * A Concepts-style COPIC polar grid.
 *
 * The reference wheel is a complete wheel whose centre lives just outside the lower-left part of
 * the canvas. What looks like a fan is simply the on-screen sector of that wheel. Its swatches
 * are annular tiles (not floating cards): short, connected, curved cells grouped in hue clusters.
 */
class RadialColorFan(context: Context) : View(context) {
    var onColorPicked: ((Int) -> Unit)? = null
    var onDismissRequested: (() -> Unit)? = null
    var onLockToggleRequested: (() -> Unit)? = null
    var isCanvasLocked = false

    private var cx = 0f; private var cy = 0f; private var rotation = -126f
    /** The four supplied Concepts shots are 1280 x 799. Scale from that artboard, not density. */
    private val referenceScale: Float
        get() = min(
            if (width > 0) width / 1280f else resources.displayMetrics.density,
            if (height > 0) height / 799f else resources.displayMetrics.density
        )
    private val hubRadius get() = 62f * referenceScale
    private val neutralRadius get() = 150f * referenceScale
    private val neutralThickness get() = 38f * referenceScale
    /** Inner edge of row zero. The biggest stacks finish at the 440px reference radius. */
    private val firstTileInnerRadius get() = 232f * referenceScale
    private val rowPitch get() = 30f * referenceScale
    private val tileRadial get() = 29f * referenceScale
    private val maxRows = 7
    private val outerRadius get() = firstTileInnerRadius + (maxRows - 1) * rowPitch + tileRadial
    private var selected: Placed? = null
    private var selectedNeutralCode: String? = null
    private var downX = 0f; private var downY = 0f; private var startRotation = 0f
    private var rotating = false; private var pressedLock = false; private var pressedNeutral = false
    private var dismissed = false

    private data class Copic(
        val code: String, val color: Int, val family: String, val familyCode: String, val blend: Int
    )
    private data class Lane(val family: String, val ordinal: Int, val cells: List<Copic>)
    private data class Cluster(val family: String, val lanes: List<Lane>)
    private data class Placed(
        val copic: Copic, val x: Float, val y: Float, val angle: Float,
        val row: Int, val innerRadius: Float
    )

    private val hueOrder = listOf("B", "BV", "V", "RV", "R", "E", "YR", "Y", "YG", "G", "BG")
    /** 309 chromatic COPIC colours fill a 72-slot wheel as 4–5 shade stacks. */
    private val laneCounts = mapOf(
        "B" to 8, "BV" to 4, "V" to 4, "RV" to 7, "R" to 7, "E" to 13,
        "YR" to 6, "Y" to 5, "YG" to 6, "G" to 6, "BG" to 6
    )
    private val catalogue: List<Copic> by lazy { loadCatalogue() }
    private val clusters: List<Cluster> by lazy { buildClusters() }
    private val laneAngle: Float
        get() = (360f - 0.9f * clusters.size) / clusters.sumOf { it.lanes.size }.coerceAtLeast(1)
    /** A one-pixel-equivalent seam keeps neighbouring annular cells legible without card gaps. */
    private val tileSweep: Float get() = laneAngle - 0.09f
    private val neutralColours: List<Copic> by lazy {
        catalogue.filter { it.familyCode in setOf("C", "N", "T", "W", "") }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val neutralTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(66, 67, 72) }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(248,27,28,33) }
    private val lock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    fun configure(anchorX: Float, anchorY: Float, angleOffsetDeg: Float = 0f) {
        cx = anchorX; cy = anchorY; rotation = -126f + angleOffsetDeg
    }
    fun wheelDiameter(): Float = outerRadius * 2f

    private fun loadCatalogue(): List<Copic> = context.assets.open("copic_358.csv").bufferedReader().useLines { lines ->
        lines.drop(1).mapNotNull { line ->
            val parts = line.split(',', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val parsed = Regex("^(?:([A-Z]+)-?)?([0-9]+)$").matchEntire(parts[0]) ?: return@mapNotNull null
            runCatching {
                Copic(
                    code = parts[0], color = Color.parseColor(parts[1]), family = parts[2],
                    familyCode = parsed.groupValues[1], blend = parsed.groupValues[2].first().digitToInt()
                )
            }.getOrNull()
        }.toList()
    }

    private fun brightness(c: Copic): Double =
        .2126 * Color.red(c.color) + .7152 * Color.green(c.color) + .0722 * Color.blue(c.color)

    private fun displayFamily(familyCode: String): String = when (familyCode) {
        "FB" -> "B"
        "FV" -> "V"
        "FRV" -> "RV"
        "FYR" -> "YR"
        "FY" -> "Y"
        "FYG" -> "YG"
        "FBG" -> "BG"
        else -> familyCode
    }

    private fun numericCode(copic: Copic): Int =
        Regex("[0-9]+$").find(copic.code)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * The physical wheel has 72 angular slots. Each hue keeps contiguous short lanes, while the
     * marker shades in a lane run dark-to-light from the inner edge outward. This keeps the
     * irregular outside edge visible instead of turning the catalogue into equal long spokes.
     */
    private fun buildClusters(): List<Cluster> = hueOrder.map { hue ->
        val ordered = catalogue.filter { displayFamily(it.familyCode) == hue }
            .sortedWith(compareBy<Copic> { it.blend }.thenBy { numericCode(it) }.thenBy { it.code })
        val laneCount = laneCounts.getValue(hue)
        val base = ordered.size / laneCount
        val remainder = ordered.size % laneCount
        var offset = 0
        val lanes = buildList {
            repeat(laneCount) { ordinal ->
                val amount = base + if (ordinal < remainder) 1 else 0
                val cells = ordered.subList(offset, offset + amount)
                    .sortedWith(compareBy<Copic> { brightness(it) }.thenBy { numericCode(it) })
                add(Lane(hue, ordinal, cells))
                offset += amount
            }
        }
        Cluster(hue, lanes)
    }

    private fun placed(): List<Placed> = buildList {
        val clusterGap = 0.9f
        val slotAngle = laneAngle
        var cursor = rotation
        clusters.forEach { cluster ->
            cursor += clusterGap / 2f
            cluster.lanes.forEach { lane ->
                val angle = cursor + slotAngle / 2f
                val rad = Math.toRadians(angle.toDouble())
                lane.cells.forEachIndexed { row, copic ->
                    val inner = firstTileInnerRadius + row * rowPitch
                    val radius = inner + tileRadial / 2f
                    add(Placed(copic, cx + cos(rad).toFloat() * radius, cy + sin(rad).toFloat() * radius, angle, row, inner))
                }
                cursor += slotAngle
            }
            cursor += clusterGap / 2f
        }
    }

    private fun signedAngleDifference(first: Float, second: Float): Float =
        ((first - second + 540f) % 360f) - 180f

    private fun hit(x: Float, y: Float): Placed? {
        val radius = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
        val angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        return placed().firstOrNull { p ->
            radius in p.innerRadius..(p.innerRadius + tileRadial) &&
                abs(signedAngleDifference(angle, p.angle)) <= tileSweep / 2f
        }
    }

    /**
     * Mirrors [drawNeutralRing]'s exact geometry - same radius band and same fixed `-126f`
     * angular basis (never the rotating [rotation] field, since the neutral ring itself never
     * rotates) - so a tap on a painted neutral swatch actually resolves to that swatch.
     */
    private fun hitNeutral(x: Float, y: Float): Copic? {
        if (neutralColours.isEmpty()) return null
        val index = RadialNeutralHit.indexAt(
            x, y, cx, cy, neutralRadius, neutralThickness, neutralColours.size,
            touchSlop = 7f * referenceScale
        ) ?: return null
        return neutralColours[index]
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dismissed) return true
        val lockX = cx + hubRadius*.62f; val lockY = cy + hubRadius*.62f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX=e.x; downY=e.y; startRotation=rotation; rotating=false
                pressedLock=hypot((e.x-lockX).toDouble(),(e.y-lockY).toDouble()) < 20f*referenceScale
                pressedNeutral = !pressedLock && hitNeutral(e.x, e.y) != null
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedNeutral) {
                    // The neutral band is deliberately fixed while the hue wheel rotates. Treat a
                    // small drag across it as selection, not as a request to rotate the other ring.
                    hitNeutral(e.x, e.y)?.let {
                        selectedNeutralCode = it.code
                        selected = null
                        invalidate()
                    }
                } else if (!pressedLock && hypot((e.x-downX).toDouble(),(e.y-downY).toDouble()) > 8f*referenceScale) {
                    rotating = true
                    // Concepts turns this wheel with an up/down drag rather than a circular drag.
                    rotation = startRotation + (e.y - downY) * .34f
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedLock) onLockToggleRequested?.invoke()
                else if (pressedNeutral) {
                    (hitNeutral(e.x, e.y) ?: hitNeutral(downX, downY))?.let {
                        selectedNeutralCode = it.code
                        selected = null
                        onColorPicked?.invoke(it.color)
                        invalidate()
                    }
                }
                else if (!rotating) {
                    hit(e.x,e.y)?.let {
                        selected=it; selectedNeutralCode = null
                        onColorPicked?.invoke(it.copic.color); invalidate(); return true
                    }
                    hitNeutral(e.x,e.y)?.let {
                        selectedNeutralCode = it.code; selected = null
                        onColorPicked?.invoke(it.color); invalidate(); return true
                    }
                    if (hypot((e.x-cx).toDouble(),(e.y-cy).toDouble()) > outerRadius) { dismissed=true; onDismissRequested?.invoke() }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dismissed=true; onDismissRequested?.invoke(); return true }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // Neutrals are a fixed Copic ring; the outer chromatic catalogue is what turns on drag.
        drawNeutralRing(canvas)
        placed().forEach { p ->
            val path = tilePath(p)
            fill.color=p.copic.color; canvas.drawPath(path,fill)
            if (selected?.copic?.code==p.copic.code) {
                active.strokeWidth = 1.25f * referenceScale
                canvas.drawPath(path, active)
            }
            canvas.save(); canvas.rotate(p.angle+90f,p.x,p.y)
            label.textSize=7f*referenceScale; label.color=if(isLight(p.copic.color)) Color.rgb(22,22,22) else Color.WHITE
            canvas.drawText(p.copic.code,p.x,p.y+label.textSize*.34f,label)
            canvas.restore()
        }
        canvas.drawCircle(cx,cy,hubRadius,hub)
        selected?.let {
            fill.color = it.copic.color
            canvas.drawCircle(cx, cy, hubRadius*.52f, fill)
            active.strokeWidth = 1.25f * referenceScale
            canvas.drawCircle(cx, cy, hubRadius*.52f, active)
        }
        val lx=cx+hubRadius*.62f; val ly=cy+hubRadius*.62f
        fill.color=Color.argb(245,55,55,62);canvas.drawCircle(lx,ly,13f*referenceScale,fill)
        lock.strokeWidth = 1.8f * referenceScale
        canvas.drawRoundRect(RectF(lx-5f*referenceScale,ly-1f*referenceScale,lx+5f*referenceScale,ly+7f*referenceScale),2f*referenceScale,2f*referenceScale,lock)
        canvas.drawArc(lx-5f*referenceScale,ly-8f*referenceScale,lx+5f*referenceScale,ly+3f*referenceScale,180f,if(isCanvasLocked)180f else 135f,false,lock)
    }

    private fun tilePath(p: Placed): Path {
        return annularSectorPath(p.innerRadius, p.innerRadius + tileRadial, p.angle - tileSweep / 2f, tileSweep)
    }

    /**
     * `Path.arcTo` otherwise joins an empty path to (0, 0). Starting explicitly on the outer
     * perimeter is what makes this a small annular cell instead of an accidental pie to screen
     * origin.
     */
    private fun annularSectorPath(inner: Float, outer: Float, start: Float, sweep: Float): Path {
        val end = start + sweep
        fun point(radius: Float, angle: Float) = Pair(
            cx + cos(Math.toRadians(angle.toDouble())).toFloat() * radius,
            cy + sin(Math.toRadians(angle.toDouble())).toFloat() * radius
        )
        val outerStart = point(outer, start)
        val innerEnd = point(inner, end)
        return Path().apply {
            moveTo(outerStart.first, outerStart.second)
            arcTo(RectF(cx - outer, cy - outer, cx + outer, cy + outer), start, sweep, false)
            lineTo(innerEnd.first, innerEnd.second)
            arcTo(RectF(cx - inner, cy - inner, cx + inner, cy + inner), end, -sweep, false)
            close()
        }
    }

    private fun drawNeutralRing(canvas: Canvas) {
        val inner = neutralRadius - neutralThickness / 2f
        val outer = neutralRadius + neutralThickness / 2f
        canvas.drawCircle(cx, cy, neutralRadius, neutralTrack.apply { style = Paint.Style.STROKE; strokeWidth = neutralThickness })
        neutralTrack.style = Paint.Style.FILL
        if (neutralColours.isEmpty()) return
        val pitch = 360f / neutralColours.size
        neutralColours.forEachIndexed { index, copic ->
            val start = -126f + index * pitch + .08f
            val sweep = pitch - .16f
            val path = annularSectorPath(inner, outer, start, sweep)
            fill.color = copic.color
            canvas.drawPath(path, fill)
            if (selectedNeutralCode == copic.code) {
                active.strokeWidth = 1.8f * referenceScale
                canvas.drawPath(path, active)
            }
            val angle = start + sweep / 2f
            val rad = Math.toRadians(angle.toDouble())
            val x = cx + cos(rad).toFloat() * neutralRadius
            val y = cy + sin(rad).toFloat() * neutralRadius
            canvas.save()
            canvas.rotate(angle + 90f, x, y)
            label.textSize = 4.2f * referenceScale
            label.color = if (isLight(copic.color)) Color.rgb(22, 22, 22) else Color.WHITE
            canvas.drawText(copic.code, x, y + label.textSize * .34f, label)
            canvas.restore()
        }
    }

    private fun isLight(c:Int)=.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c)>170
    companion object { fun defaultPalette(): List<Int> = listOf(Color.WHITE,Color.LTGRAY,Color.GRAY,Color.DKGRAY,Color.BLACK) }
}

package com.talapp.mapme.services

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkDatabase
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.data.WalkPoi
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

/**
 * SurfaceCallback implementation rendering a real-time dark-mode GPS map
 * on the vehicle's head unit surface (Toyota / Android Auto display).
 */
class CarMapSurfaceRenderer(
    private val carContext: CarContext,
    private val session: MapMeCarSession
) : SurfaceCallback {

    private var surface: Surface? = null
    private var surfaceWidth = 800
    private var surfaceHeight = 480
    private var densityDpi = 160
    private var visibleRect = Rect()

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderJob: Job? = null

    // Camera / Map View State
    private var centerLat = 32.0853 // Default tel aviv fallback until GPS lock
    private var centerLon = 34.7818
    private var hasLocationLock = false
    private var zoomLevel = 16.0
    private var headingDegrees = 0f

    // Tile Cache
    private val tileCache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
    private val diskCacheDir = File(carContext.cacheDir, "car_osmtiles").apply { mkdirs() }

    // Past walks cache
    private var pastWalks: List<Walk> = emptyList()

    // Paints
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0B0F19")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#162035")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val darkMapFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -0.7f, 0f, 0f, 0f, 220f,
                0f, -0.7f, 0f, 0f, 230f,
                0f, 0f, -0.7f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    private val tilePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        colorFilter = darkMapFilter
    }

    private val pastWalkPaint = Paint().apply {
        color = Color.parseColor("#4400F5FF") // translucent Neon Cyan
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val activeGlowPaint = Paint().apply {
        color = Color.parseColor("#66EF4444") // translucent Red Glow for Drive
        strokeWidth = 16f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val activeLinePaint = Paint().apply {
        color = Color.parseColor("#FFEF4444") // bright solid Red
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val vehiclePaint = Paint().apply {
        color = Color.parseColor("#00F5FF") // Neon Cyan
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val vehicleBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val subTextPaint = Paint().apply {
        color = Color.parseColor("#94A3B8")
        textSize = 20f
        isAntiAlias = true
    }

    private val hudCardPaint = Paint().apply {
        color = Color.parseColor("#CC10172A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBorderPaint = Paint().apply {
        color = Color.parseColor("#3338BDF8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    init {
        loadPastWalks()
    }

    private fun loadPastWalks() {
        renderScope.launch(Dispatchers.IO) {
            try {
                val db = WalkDatabase.getDatabase(carContext)
                val walks = db.walkDao().getAllWalksList()
                pastWalks = walks
                requestRender()
            } catch (_: Exception) {}
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        surfaceWidth = surfaceContainer.width.coerceAtLeast(400)
        surfaceHeight = surfaceContainer.height.coerceAtLeast(300)
        densityDpi = surfaceContainer.dpi
        visibleRect = Rect(0, 0, surfaceWidth, surfaceHeight)
        requestRender()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
        renderJob?.cancel()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visibleRect = visibleArea
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        visibleRect = stableArea
        requestRender()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val deltaLon = (distanceX / (256.0 * (1 shl zoomLevel.toInt()))) * 360.0
        val deltaLat = -(distanceY / (256.0 * (1 shl zoomLevel.toInt()))) * 360.0
        centerLon += deltaLon
        centerLat = (centerLat + deltaLat).coerceIn(-80.0, 80.0)
        requestRender()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        zoomLevel = (zoomLevel + ln(scaleFactor.toDouble()) / ln(2.0)).coerceIn(12.0, 18.5)
        requestRender()
    }

    override fun onClick(x: Float, y: Float) {
        // Re-center on vehicle
        val currentPoints = session.currentPoints
        val latest = currentPoints.lastOrNull()
        if (latest != null) {
            centerLat = latest.latitude
            centerLon = latest.longitude
            requestRender()
        }
    }

    fun requestRender() {
        val s = surface ?: return
        if (!s.isValid) return

        renderJob?.cancel()
        renderJob = renderScope.launch(Dispatchers.Default) {
            renderFrame()
        }
    }

    private fun renderFrame() {
        val s = surface ?: return
        if (!s.isValid) return

        // Update center from active points
        val currentPoints = session.currentPoints
        val latest = currentPoints.lastOrNull()
        if (latest != null) {
            centerLat = latest.latitude
            centerLon = latest.longitude
            hasLocationLock = true
            if (currentPoints.size >= 2) {
                val prev = currentPoints[currentPoints.size - 2]
                headingDegrees = calculateBearing(prev.latitude, prev.longitude, latest.latitude, latest.longitude)
            }
        }

        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null)
            if (canvas != null) {
                drawMap(canvas, currentPoints)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (canvas != null) {
                try {
                    s.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {}
            }
        }
    }

    private fun drawMap(canvas: Canvas, currentPoints: List<WalkPoint>) {
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()

        // 1. Draw Deep Cyber Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Draw OpenStreetMap Tiles
        drawOsmTiles(canvas, w, h)

        // 3. Draw Past Colored Walks (Heatmap)
        drawPastWalks(canvas, w, h)

        // 4. Draw Active Drive / Walk Path
        drawActivePath(canvas, currentPoints, w, h)

        // 5. Draw Active POIs
        drawPois(canvas, session.activePois, w, h)

        // 6. Draw Vehicle / Location Marker
        drawVehicleMarker(canvas, w, h)

        // 7. Draw Telemetry HUD Watermark
        drawTelemetryHud(canvas, w, h)
    }

    private fun drawOsmTiles(canvas: Canvas, screenW: Float, screenH: Float) {
        val z = zoomLevel.toInt()
        val scale = 256.0 * (1 shl z)
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        val minPixelX = centerPixelX - screenW / 2
        val maxPixelX = centerPixelX + screenW / 2
        val minPixelY = centerPixelY - screenH / 2
        val maxPixelY = centerPixelY + screenH / 2

        val minTileX = (minPixelX / 256.0).toInt()
        val maxTileX = (maxPixelX / 256.0).toInt()
        val minTileY = (minPixelY / 256.0).toInt()
        val maxTileY = (maxPixelY / 256.0).toInt()

        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                val tileKey = "$z/$tx/$ty"
                val tileLeft = (screenW / 2f + (tx * 256.0 - centerPixelX)).toFloat()
                val tileTop = (screenH / 2f + (ty * 256.0 - centerPixelY)).toFloat()

                val cached = tileCache.get(tileKey)
                if (cached != null && !cached.isRecycled) {
                    canvas.drawBitmap(cached, tileLeft, tileTop, tilePaint)
                } else {
                    // Draw grid placeholder while tile is downloading
                    canvas.drawRect(tileLeft, tileTop, tileLeft + 256f, tileTop + 256f, gridPaint)
                    fetchTileAsync(z, tx, ty)
                }
            }
        }
    }

    private fun fetchTileAsync(z: Int, x: Int, y: Int) {
        val key = "$z/$x/$y"
        renderScope.launch(Dispatchers.IO) {
            try {
                val diskFile = File(diskCacheDir, "$z-$x-$y.png")
                var bitmap: Bitmap? = null
                if (diskFile.exists() && diskFile.length() > 0) {
                    bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                } else {
                    val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "MapMe-AndroidAuto/3.7")
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.readBytes()
                        diskFile.writeBytes(bytes)
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    conn.disconnect()
                }
                if (bitmap != null) {
                    tileCache.put(key, bitmap)
                    requestRender()
                }
            } catch (_: Exception) {}
        }
    }

    private fun drawPastWalks(canvas: Canvas, screenW: Float, screenH: Float) {
        val z = zoomLevel.toInt()
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        val pathType = object : TypeToken<List<WalkPoint>>() {}.type
        for (walk in pastWalks) {
            try {
                val pts: List<WalkPoint> = Gson().fromJson(walk.pointsJson, pathType) ?: continue
                if (pts.size < 2) continue

                val path = Path()
                var first = true
                for (p in pts) {
                    val px = (screenW / 2f + (lonToPixelX(p.longitude, z) - centerPixelX)).toFloat()
                    val py = (screenH / 2f + (latToPixelY(p.latitude, z) - centerPixelY)).toFloat()
                    if (first) {
                        path.moveTo(px, py)
                        first = false
                    } else {
                        path.lineTo(px, py)
                    }
                }
                canvas.drawPath(path, pastWalkPaint)
            } catch (_: Exception) {}
        }
    }

    private fun drawActivePath(canvas: Canvas, currentPoints: List<WalkPoint>, screenW: Float, screenH: Float) {
        if (currentPoints.size < 2) return

        val z = zoomLevel.toInt()
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        val isDrive = session.isTrackingDrive
        val glowColor = if (isDrive) Color.parseColor("#66EF4444") else Color.parseColor("#6600F5FF")
        val lineColor = if (isDrive) Color.parseColor("#FFEF4444") else Color.parseColor("#FF00F5FF")

        activeGlowPaint.color = glowColor
        activeLinePaint.color = lineColor

        val path = Path()
        var first = true
        for (p in currentPoints) {
            val px = (screenW / 2f + (lonToPixelX(p.longitude, z) - centerPixelX)).toFloat()
            val py = (screenH / 2f + (latToPixelY(p.latitude, z) - centerPixelY)).toFloat()
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
        }

        // Draw outer neon glow, then core line
        canvas.drawPath(path, activeGlowPaint)
        canvas.drawPath(path, activeLinePaint)
    }

    private fun drawPois(canvas: Canvas, pois: List<WalkPoi>, screenW: Float, screenH: Float) {
        if (pois.isEmpty()) return
        val z = zoomLevel.toInt()
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        val poiBgPaint = Paint().apply {
            color = Color.parseColor("#D98B5CF6") // Electric Violet
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val poiBorder = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val poiTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        for (poi in pois) {
            val px = (screenW / 2f + (lonToPixelX(poi.longitude, z) - centerPixelX)).toFloat()
            val py = (screenH / 2f + (latToPixelY(poi.latitude, z) - centerPixelY)).toFloat()

            // Draw pin circle
            canvas.drawCircle(px, py, 18f, poiBgPaint)
            canvas.drawCircle(px, py, 18f, poiBorder)
            canvas.drawText("📍", px, py + 7f, poiTextPaint)
        }
    }

    private fun drawVehicleMarker(canvas: Canvas, screenW: Float, screenH: Float) {
        val cx = screenW / 2f
        val cy = screenH / 2f

        // Outer pulsing radar ring
        val pulsePaint = Paint().apply {
            color = Color.parseColor("#3300F5FF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, 32f, pulsePaint)

        // Arrow vehicle shape
        canvas.save()
        canvas.rotate(headingDegrees, cx, cy)

        val arrowPath = Path().apply {
            moveTo(cx, cy - 20f)
            lineTo(cx + 14f, cy + 16f)
            lineTo(cx, cy + 8f)
            lineTo(cx - 14f, cy + 16f)
            close()
        }

        val isDrive = session.isTrackingDrive
        vehiclePaint.color = if (isDrive) Color.parseColor("#EF4444") else Color.parseColor("#00F5FF")

        canvas.drawPath(arrowPath, vehiclePaint)
        canvas.drawPath(arrowPath, vehicleBorderPaint)
        canvas.restore()
    }

    private fun drawTelemetryHud(canvas: Canvas, screenW: Float, screenH: Float) {
        val speed = session.currentSpeedKmh
        val isTracking = session.isTracking
        val isDrive = session.isTrackingDrive

        val hudW = 220f
        val hudH = 80f
        val margin = 20f
        val left = screenW - hudW - margin
        val top = margin

        val rect = RectF(left, top, left + hudW, top + hudH)
        canvas.drawRoundRect(rect, 16f, 16f, hudCardPaint)
        canvas.drawRoundRect(rect, 16f, 16f, hudBorderPaint)

        val speedStr = String.format(Locale.US, "%.1f km/h", speed)
        val statusStr = if (isTracking) {
            if (isDrive) "🚗 DRIVE ACTIVE" else "🚶 WALK ACTIVE"
        } else {
            "● MAP READY"
        }

        val statusColor = if (isTracking) Color.parseColor("#00F5FF") else Color.parseColor("#94A3B8")
        val statusPaint = Paint().apply {
            color = statusColor
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        canvas.drawText(statusStr, left + 14f, top + 26f, statusPaint)
        canvas.drawText(speedStr, left + 14f, top + 60f, textPaint)
    }

    private fun lonToPixelX(lon: Double, zoom: Int): Double {
        return (lon + 180.0) / 360.0 * (256.0 * (1 shl zoom))
    }

    private fun latToPixelY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val mercN = ln(tan(Math.PI / 4.0 + latRad / 2.0))
        return (1.0 - mercN / Math.PI) / 2.0 * (256.0 * (1 shl zoom))
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val θ = atan2(y, x)
        return ((Math.toDegrees(θ) + 360.0) % 360.0).toFloat()
    }
}

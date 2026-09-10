package com.talapp.mapme.services

import android.content.Context
import android.graphics.*
import android.location.Location
import android.util.LruCache
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.google.android.gms.location.LocationServices
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
 * SurfaceCallback implementation rendering a real-time dark-mode GPS navigation map
 * on the vehicle's head unit surface (Toyota / Android Auto display).
 *
 * Supports regular navigation mode (map rotates to match vehicle heading, road ahead is UP),
 * real-time location streaming, speed HUD, and full-screen telemetry.
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
    private var centerLat = 32.0853
    private var centerLon = 34.7818
    private var vehicleLat = 32.0853
    private var vehicleLon = 34.7818
    private var vehicleSpeedKmh = 0f
    private var hasLocationLock = false
    private var zoomLevel = 16.2
    
    // Heading / Navigation orientation
    var isHeadingUp = true
        private set
    private var isFollowVehicle = true
    private var targetHeadingDegrees = 0f
    private var headingDegrees = 0f

    // Tile Cache
    private val tileCache = object : LruCache<String, Bitmap>(96) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
    private val diskCacheDir = File(carContext.cacheDir, "car_osmtiles").apply { mkdirs() }

    // Past walks cache
    private var pastWalks: List<Walk> = emptyList()

    // Paints
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#080D1A")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#131C2E")
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
        color = Color.parseColor("#5500F5FF") // translucent Neon Cyan
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val activeGlowPaint = Paint().apply {
        color = Color.parseColor("#66EF4444") // translucent Red Glow for Drive
        strokeWidth = 18f
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
        color = Color.parseColor("#EF4444") // Drive Red
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
        textSize = 18f
        isAntiAlias = true
    }

    private val hudCardPaint = Paint().apply {
        color = Color.parseColor("#E60F172A") // Deep glassmorphic slate
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBorderPaint = Paint().apply {
        color = Color.parseColor("#4D38BDF8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val poiBgPaint = Paint().apply {
        color = Color.parseColor("#D98B5CF6") // Electric Violet
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val poiBorder = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val poiTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    init {
        loadPastWalks()
        fetchInitialLocation()
    }

    private fun fetchInitialLocation() {
        try {
            val fused = LocationServices.getFusedLocationProviderClient(carContext)
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    updateVehicleLocation(loc)
                }
            }
        } catch (_: SecurityException) {}
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

    /**
     * Called whenever a new GPS location update is received from the device or vehicle.
     */
    fun updateVehicleLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val speed = if (location.hasSpeed()) location.speed * 3.6f else 0f
        vehicleSpeedKmh = speed

        // Only adjust rotation heading if the vehicle is moving to avoid jitter at stops
        if (speed > 1.5f && location.hasBearing()) {
            targetHeadingDegrees = location.bearing
        } else if (hasLocationLock && speed > 2.0f) {
            val dLat = lat - vehicleLat
            val dLon = lon - vehicleLon
            if (abs(dLat) > 0.00001 || abs(dLon) > 0.00001) {
                targetHeadingDegrees = calculateBearing(vehicleLat, vehicleLon, lat, lon)
            }
        }

        vehicleLat = lat
        vehicleLon = lon
        hasLocationLock = true

        if (isFollowVehicle) {
            centerLat = lat
            centerLon = lon
        }

        requestRender()
    }

    fun recenterOnVehicle() {
        isFollowVehicle = true
        centerLat = vehicleLat
        centerLon = vehicleLon
        requestRender()
    }

    fun toggleHeadingMode() {
        isHeadingUp = !isHeadingUp
        requestRender()
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
        isFollowVehicle = false
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
        // Recenter on vehicle if tapped
        recenterOnVehicle()
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

        // Smoothly interpolate heading angle towards target heading
        val diff = normalizeAngleDiff(targetHeadingDegrees, headingDegrees)
        if (abs(diff) > 0.1f) {
            headingDegrees = (headingDegrees + diff * 0.35f + 360f) % 360f
        }

        // Keep vehicle centered when following
        if (isFollowVehicle && hasLocationLock) {
            centerLat = vehicleLat
            centerLon = vehicleLon
        }

        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null)
            if (canvas != null) {
                drawMap(canvas, session.currentPoints)
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

        // Vehicle on-screen position: In navigation mode, position at 65% of screen height
        // to provide forward-looking visibility ahead of the car.
        val vx = w / 2f
        val vy = if (isHeadingUp) h * 0.65f else h / 2f

        // Driving rotation: Rotate world so heading direction points straight UP
        val worldRotation = if (isHeadingUp) -headingDegrees else 0f

        canvas.save()
        canvas.rotate(worldRotation, vx, vy)

        // 2. Draw OpenStreetMap Tiles (Rotated)
        drawOsmTiles(canvas, vx, vy, w, h)

        // 3. Draw Past Recorded Drives & Heatmap (Rotated)
        drawPastWalks(canvas, vx, vy)

        // 4. Draw Active Drive Path (Rotated)
        drawActivePath(canvas, currentPoints, vx, vy)

        // 5. Draw Active POIs (Rotated)
        drawPois(canvas, session.activePois, vx, vy, worldRotation)

        // 6. Draw Vehicle Marker (Center of rotation, forward-facing)
        drawVehicleMarker(canvas, vx, vy, isHeadingUp)

        canvas.restore()

        // --- Screen-Space Overlays (Unrotated for legibility) ---

        // 7. Draw Compass Rose
        drawCompass(canvas, w, h, -headingDegrees)

        // 8. Draw Real-Time Navigation Telemetry HUD
        drawTelemetryHud(canvas, w, h)
    }

    private fun drawOsmTiles(canvas: Canvas, vx: Float, vy: Float, screenW: Float, screenH: Float) {
        val z = zoomLevel.toInt()
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        // Safe radius to guarantee rotated viewport is covered with tiles without black corners
        val radius = hypot(screenW.toDouble(), screenH.toDouble()).toFloat() / 2f + 256f

        val minPixelX = centerPixelX - radius
        val maxPixelX = centerPixelX + radius
        val minPixelY = centerPixelY - radius
        val maxPixelY = centerPixelY + radius

        val minTileX = (minPixelX / 256.0).toInt()
        val maxTileX = (maxPixelX / 256.0).toInt()
        val minTileY = (minPixelY / 256.0).toInt()
        val maxTileY = (maxPixelY / 256.0).toInt()

        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                val tileKey = "//"
                val tileLeft = (vx + (tx * 256.0 - centerPixelX)).toFloat()
                val tileTop = (vy + (ty * 256.0 - centerPixelY)).toFloat()

                val cached = tileCache.get(tileKey)
                if (cached != null && !cached.isRecycled) {
                    canvas.drawBitmap(cached, tileLeft, tileTop, tilePaint)
                } else {
                    canvas.drawRect(tileLeft, tileTop, tileLeft + 256f, tileTop + 256f, gridPaint)
                    fetchTileAsync(z, tx, ty)
                }
            }
        }
    }

    private fun fetchTileAsync(z: Int, x: Int, y: Int) {
        val key = "//"
        renderScope.launch(Dispatchers.IO) {
            try {
                val diskFile = File(diskCacheDir, "--.png")
                var bitmap: Bitmap? = null
                if (diskFile.exists() && diskFile.length() > 0) {
                    bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                } else {
                    val url = URL("https://tile.openstreetmap.org///.png")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "MapMe-AndroidAuto/4.1")
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

    private fun drawPastWalks(canvas: Canvas, vx: Float, vy: Float) {
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
                    val px = (vx + (lonToPixelX(p.longitude, z) - centerPixelX)).toFloat()
                    val py = (vy + (latToPixelY(p.latitude, z) - centerPixelY)).toFloat()
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

    private fun drawActivePath(canvas: Canvas, currentPoints: List<WalkPoint>, vx: Float, vy: Float) {
        if (currentPoints.size < 2) return

        val z = zoomLevel.toInt()
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        val path = Path()
        var first = true
        for (p in currentPoints) {
            val px = (vx + (lonToPixelX(p.longitude, z) - centerPixelX)).toFloat()
            val py = (vy + (latToPixelY(p.latitude, z) - centerPixelY)).toFloat()
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
        }

        // Draw outer neon glow, then core red drive line
        canvas.drawPath(path, activeGlowPaint)
        canvas.drawPath(path, activeLinePaint)
    }

    private fun drawPois(canvas: Canvas, pois: List<WalkPoi>, vx: Float, vy: Float, worldRotation: Float) {
        if (pois.isEmpty()) return
        val z = zoomLevel.toInt()
        val centerPixelX = lonToPixelX(centerLon, z)
        val centerPixelY = latToPixelY(centerLat, z)

        for (poi in pois) {
            val px = (vx + (lonToPixelX(poi.longitude, z) - centerPixelX)).toFloat()
            val py = (vy + (latToPixelY(poi.latitude, z) - centerPixelY)).toFloat()

            canvas.save()
            canvas.translate(px, py)
            // Counter-rotate so waypoint pins and icons remain upright for the driver
            canvas.rotate(-worldRotation)
            canvas.drawCircle(0f, 0f, 18f, poiBgPaint)
            canvas.drawCircle(0f, 0f, 18f, poiBorder)
            canvas.drawText("📍", 0f, 7f, poiTextPaint)
            canvas.restore()
        }
    }

    private fun drawVehicleMarker(canvas: Canvas, vx: Float, vy: Float, isHeadingUp: Boolean) {
        // Outer pulsing radar ring
        val pulsePaint = Paint().apply {
            color = Color.parseColor("#33EF4444") // translucent Red pulse
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(vx, vy, 34f, pulsePaint)

        canvas.save()
        if (!isHeadingUp) {
            // In North-Up mode, rotate the vehicle arrow to show driving heading
            canvas.rotate(headingDegrees, vx, vy)
        }
        // In Heading-Up mode, arrow points straight UP (forward)

        val arrowPath = Path().apply {
            moveTo(vx, vy - 24f) // Tip forward
            lineTo(vx + 16f, vy + 16f)
            lineTo(vx, vy + 8f)
            lineTo(vx - 16f, vy + 16f)
            close()
        }

        vehiclePaint.color = Color.parseColor("#EF4444")
        canvas.drawPath(arrowPath, vehiclePaint)
        canvas.drawPath(arrowPath, vehicleBorderPaint)
        canvas.restore()
    }

    private fun drawCompass(canvas: Canvas, screenW: Float, screenH: Float, northRotation: Float) {
        val cx = 46f
        val cy = 46f
        val r = 26f

        val compassBg = Paint().apply {
            color = Color.parseColor("#CC0F172A")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val compassBorder = Paint().apply {
            color = Color.parseColor("#4D38BDF8")
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, r, compassBg)
        canvas.drawCircle(cx, cy, r, compassBorder)

        canvas.save()
        canvas.rotate(northRotation, cx, cy)

        // North needle (Red)
        val northPaint = Paint().apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val northPath = Path().apply {
            moveTo(cx, cy - 18f)
            lineTo(cx + 6f, cy)
            lineTo(cx - 6f, cy)
            close()
        }
        canvas.drawPath(northPath, northPaint)

        // South needle (White)
        val southPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val southPath = Path().apply {
            moveTo(cx, cy + 18f)
            lineTo(cx + 6f, cy)
            lineTo(cx - 6f, cy)
            close()
        }
        canvas.drawPath(southPath, southPaint)

        canvas.restore()
    }

    private fun drawTelemetryHud(canvas: Canvas, screenW: Float, screenH: Float) {
        val speed = if (session.isTracking) session.currentSpeedKmh else vehicleSpeedKmh
        val isTracking = session.isTracking
        val points = session.currentPoints
        val isPaused = !isTracking && points.isNotEmpty()

        val hudW = 240f
        val hudH = if (isTracking || isPaused) 100f else 68f
        val left = 20f
        val top = screenH - hudH - 24f

        val rect = RectF(left, top, left + hudW, top + hudH)
        canvas.drawRoundRect(rect, 18f, 18f, hudCardPaint)
        canvas.drawRoundRect(rect, 18f, 18f, hudBorderPaint)

        val statusStr = when {
            isTracking -> "🚗 DRIVE ACTIVE"
            isPaused -> "⏸️ PAUSED"
            else -> "● READY TO DRIVE"
        }

        val statusColor = when {
            isTracking -> Color.parseColor("#22C55E") // Emerald Green
            isPaused -> Color.parseColor("#F59E0B") // Amber
            else -> Color.parseColor("#94A3B8") // Slate
        }

        val statusPaint = Paint().apply {
            color = statusColor
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        canvas.drawText(statusStr, left + 16f, top + 24f, statusPaint)

        val speedStr = String.format(Locale.US, "%.0f km/h", speed)
        canvas.drawText(speedStr, left + 16f, top + 54f, textPaint)

        if (isTracking || isPaused) {
            val distStr = formatDistance(session.totalDistanceMeters)
            val durStr = formatDuration(session.elapsedTimeSeconds)
            val tripStats = "  •  "
            canvas.drawText(tripStats, left + 16f, top + 84f, subTextPaint)
        }
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

    private fun normalizeAngleDiff(target: Float, current: Float): Float {
        var diff = (target - current) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun formatDuration(seconds: Long): String {
        val nonNegative = if (seconds < 0) 0L else seconds
        val h = nonNegative / 3600
        val m = (nonNegative % 3600) / 60
        val s = nonNegative % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun formatDistance(meters: Double): String {
        val nonNegative = if (meters < 0) 0.0 else meters
        return if (nonNegative < 1000) {
            String.format(Locale.US, "%.0f m", nonNegative)
        } else {
            String.format(Locale.US, "%.1f km", nonNegative / 1000.0)
        }
    }
}

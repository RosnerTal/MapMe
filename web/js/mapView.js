// ==========================================================================
// MapMe Web Dashboard - Leaflet Map Controller & Visualizer
// Cyberpunk Glassmorphic Map with Corridor Deduplication & Glowing Polylines
// ==========================================================================

import { WALK_SPEED_LIMIT_KMH } from "./config.js";
import { consolidateRoutes } from "./routeConsolidator.js";
import { formatDate } from "./formatters.js";

let map = null;
let mapTileLayer = null;
let isDarkMap = true;

// Active and background layer tracking
let activeRouteLayers = [];
let activeMarkerStart = null;
let activeMarkerEnd = null;
let activePoiLayers = [];
let pastCorridorLayers = [];
let pastPoiLayers = [];

/**
 * Initializes the Leaflet map instance.
 * @param {string} containerId
 */
export function initMap(containerId = "map") {
    if (map) return map;

    map = L.map(containerId, {
        zoomControl: false,
        attributionControl: false
    });

    L.control.zoom({ position: "topleft" }).addTo(map);

    setTileLayer();
    map.setView([20, 0], 2);
    return map;
}

/**
 * Sets or updates map tile source and theme filters.
 */
function setTileLayer() {
    if (!map) return;
    if (mapTileLayer) {
        map.removeLayer(mapTileLayer);
    }
    const tileUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    mapTileLayer = L.tileLayer(tileUrl, {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank">OpenStreetMap</a> contributors',
        className: isDarkMap ? "map-tiles-dark" : "map-tiles-light"
    }).addTo(map);
}

/**
 * Toggles dark / light map tile mode.
 * @returns {boolean} Current dark mode state.
 */
export function toggleMapTheme() {
    isDarkMap = !isDarkMap;
    setTileLayer();
    return isDarkMap;
}

export function isDarkTheme() {
    return isDarkMap;
}

/**
 * Parses points from pointsJson string safely.
 * @param {Object} walk
 * @returns {Array<{latitude: number, longitude: number, speed: number, timestamp: number}>}
 */
export function parseWalkPoints(walk) {
    if (!walk || !walk.pointsJson) return [];
    try {
        const parsed = JSON.parse(walk.pointsJson);
        return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
        console.warn("Failed to parse pointsJson for walk", walk.id, e);
        return [];
    }
}

/**
 * Parses POIs from poisJson safely.
 * @param {Object} walk
 * @returns {Array<{latitude: number, longitude: number, text?: string, imageBase64?: string, timestamp: number}>}
 */
export function parseWalkPois(walk) {
    if (!walk || !walk.poisJson) return [];
    try {
        const parsed = JSON.parse(walk.poisJson);
        return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
        console.warn("Failed to parse poisJson for walk", walk.id, e);
        return [];
    }
}

/**
 * Determines travel mode for each point or segment of a walk.
 * Respects title override ("Drive on...", "Walk on...") or computes rolling speed.
 * @param {Object} walk
 * @param {Array} points
 * @returns {Array<boolean>} Array of isDrive booleans
 */
export function classifyPointModes(walk, points) {
    const titleLower = (walk.title || "").toLowerCase();
    const isDriveByTitle = titleLower.startsWith("drive on") || titleLower.startsWith("drive at");
    const isWalkByTitle = titleLower.startsWith("walk on") || titleLower.startsWith("walk at");

    return points.map((pt, i) => {
        if (isDriveByTitle) return true;
        if (isWalkByTitle) return false;

        // Rolling 3-point average speed to prevent GPS jitter flip-flops
        const startIdx = Math.max(0, i - 1);
        const endIdx = Math.min(points.length - 1, i + 1);
        let sumSpeed = 0;
        let count = 0;
        for (let j = startIdx; j <= endIdx; j++) {
            sumSpeed += (points[j].speed || 0);
            count++;
        }
        const avgKmh = (sumSpeed / count) * 3.6;
        return avgKmh >= WALK_SPEED_LIMIT_KMH;
    });
}

/**
 * Splits points into contiguous segments of the same travel mode.
 */
function splitContiguousSegments(walk, points) {
    if (points.length < 2) return [];
    const modes = classifyPointModes(walk, points);
    const segments = [];
    let currentPts = [points[0]];
    let currentMode = modes[0];

    for (let i = 1; i < points.length; i++) {
        const pt = points[i];
        const mode = modes[i];
        if (mode === currentMode) {
            currentPts.push(pt);
        } else {
            if (currentPts.length >= 2) {
                segments.push({ walk, points: currentPts, isDrive: currentMode });
            }
            // Overlap boundary point so polyline does not have gap
            currentPts = [points[i - 1], pt];
            currentMode = mode;
        }
    }

    if (currentPts.length >= 2) {
        segments.push({ walk, points: currentPts, isDrive: currentMode });
    }

    return segments;
}

/**
 * Clears all active walk route layers and markers.
 */
export function clearActiveLayers() {
    if (!map) return;
    activeRouteLayers.forEach(l => map.removeLayer(l));
    activeRouteLayers = [];

    activePoiLayers.forEach(l => map.removeLayer(l));
    activePoiLayers = [];

    if (activeMarkerStart) {
        map.removeLayer(activeMarkerStart);
        activeMarkerStart = null;
    }
    if (activeMarkerEnd) {
        map.removeLayer(activeMarkerEnd);
        activeMarkerEnd = null;
    }
}

/**
 * Clears all past corridor layers and past POIs.
 */
export function clearPastLayers() {
    if (!map) return;
    pastCorridorLayers.forEach(l => map.removeLayer(l));
    pastCorridorLayers = [];

    pastPoiLayers.forEach(l => map.removeLayer(l));
    pastPoiLayers = [];
}

/**
 * Builds HTML popup for a Point of Interest.
 */
function createPoiPopupHtml(poi) {
    let html = `<div class="poi-popup">`;
    html += `<h4><span style="color: var(--color-primary);">📍</span> Point of Interest</h4>`;
    html += `<span class="poi-time">${formatDate(poi.timestamp)}</span>`;
    if (poi.text) {
        html += `<p class="poi-desc">${poi.text}</p>`;
    }
    if (poi.imageBase64) {
        html += `<img src="data:image/jpeg;base64,${poi.imageBase64}" class="poi-img" alt="POI Photo" title="Click to view full size" />`;
    }
    html += `</div>`;
    return html;
}

/**
 * Draws active selected walk with glowing cyber neon styling.
 */
export function drawActiveWalk(walk, { showWalks = true, showDrives = true, showPois = true, fitBounds = false } = {}) {
    if (!map || !walk) return;

    clearActiveLayers();

    const points = parseWalkPoints(walk);
    if (points.length < 2) return;

    const segments = splitContiguousSegments(walk, points);

    // Neon Cyberpunk colors
    const walkGlowColor = "#00E5FF"; // Neon Cyan
    const driveGlowColor = "#EF4444"; // Drive Red/Coral

    const activeBoundsLatLngs = [];

    segments.forEach(seg => {
        if (seg.isDrive && !showDrives) return;
        if (!seg.isDrive && !showWalks) return;

        const color = seg.isDrive ? driveGlowColor : walkGlowColor;
        const latlngs = seg.points.map(p => [p.latitude, p.longitude]);
        latlngs.forEach(ll => activeBoundsLatLngs.push(ll));

        // Outer glow layer
        const glow = L.polyline(latlngs, {
            color: color,
            opacity: 0.32,
            weight: 18,
            lineCap: "round",
            lineJoin: "round"
        }).addTo(map);

        // Crisp inner core layer
        const core = L.polyline(latlngs, {
            color: color,
            opacity: 0.95,
            weight: 6,
            lineCap: "round",
            lineJoin: "round"
        }).addTo(map);

        activeRouteLayers.push(glow, core);
    });

    // Start & End markers
    const startPt = points[0];
    const endPt = points[points.length - 1];

    const startDot = L.divIcon({ className: "map-dot start-dot" });
    const endDot = L.divIcon({ className: "map-dot end-dot" });

    activeMarkerStart = L.marker([startPt.latitude, startPt.longitude], { icon: startDot })
        .addTo(map)
        .bindPopup("<strong>Start Point</strong>", { className: "custom-leaflet-popup" });

    activeMarkerEnd = L.marker([endPt.latitude, endPt.longitude], { icon: endDot })
        .addTo(map)
        .bindPopup("<strong>End Point</strong>", { className: "custom-leaflet-popup" });

    // Active POIs
    if (showPois) {
        const pois = parseWalkPois(walk);
        pois.forEach(poi => {
            const poiDot = L.divIcon({ className: "map-poi-dot active-poi-dot" });
            const marker = L.marker([poi.latitude, poi.longitude], { icon: poiDot })
                .addTo(map)
                .bindPopup(createPoiPopupHtml(poi), { maxWidth: 260, className: "custom-leaflet-popup" });
            activePoiLayers.push(marker);
        });
    }

    if (fitBounds && activeBoundsLatLngs.length > 0) {
        map.fitBounds(L.latLngBounds(activeBoundsLatLngs), { padding: [50, 50], maxZoom: 17 });
    }
}

/**
 * Draws consolidated background corridors and background POIs.
 * Corridors are spatial corridors (35m deduplication) that merge repeated walks/drives cleanly.
 */
export function drawConsolidatedCorridors(walks, activeWalkId, {
    showAllHistory = true,
    showWalks = true,
    showDrives = true,
    showPois = true,
    onSelectWalk = null
} = {}) {
    if (!map) return;

    clearPastLayers();
    if (!showAllHistory || !walks || walks.length === 0) return;

    // Collect all background route segments (skipping the active selected walk)
    const backgroundSegments = [];
    walks.forEach(w => {
        if (activeWalkId && w.id === activeWalkId) return;
        const pts = parseWalkPoints(w);
        if (pts.length >= 2) {
            backgroundSegments.push(...splitContiguousSegments(w, pts));
        }
    });

    if (backgroundSegments.length === 0) return;

    // Run spatial corridor consolidation engine
    const consolidated = consolidateRoutes(backgroundSegments);

    const walkCorridorColor = "#8B5CF6"; // Electric Violet
    const driveCorridorColor = "#EE5859"; // Electric Coral

    consolidated.forEach(item => {
        if (item.isDrive && !showDrives) return;
        if (!item.isDrive && !showWalks) return;

        const color = item.isDrive ? driveCorridorColor : walkCorridorColor;
        const latlngs = item.points.map(p => [p.latitude, p.longitude]);

        // Background polyline with subtle glow
        const polyline = L.polyline(latlngs, {
            color: color,
            opacity: 0.70,
            weight: 5.0,
            lineCap: "round",
            lineJoin: "round"
        }).addTo(map);

        if (item.sourceWalk && typeof onSelectWalk === "function") {
            polyline.on("click", () => {
                onSelectWalk(item.sourceWalk);
            });
            polyline.on("mouseover", () => {
                polyline.setStyle({ opacity: 1.0, weight: 7.0 });
            });
            polyline.on("mouseout", () => {
                polyline.setStyle({ opacity: 0.70, weight: 5.0 });
            });
        }

        pastCorridorLayers.push(polyline);
    });

    // Background POIs
    if (showPois) {
        walks.forEach(w => {
            if (activeWalkId && w.id === activeWalkId) return;
            const pois = parseWalkPois(w);
            pois.forEach(poi => {
                const poiDot = L.divIcon({ className: "map-poi-dot past-poi-dot" });
                const marker = L.marker([poi.latitude, poi.longitude], { icon: poiDot })
                    .addTo(map)
                    .bindPopup(createPoiPopupHtml(poi), { maxWidth: 260, className: "custom-leaflet-popup" });
                pastPoiLayers.push(marker);
            });
        });
    }
}

/**
 * Fits the map view to enclose all points across the given walks.
 */
export function fitAllWalks(walks) {
    if (!map || !walks || walks.length === 0) return;
    const allLatLngs = [];
    walks.forEach(w => {
        const pts = parseWalkPoints(w);
        pts.forEach(p => allLatLngs.push([p.latitude, p.longitude]));
    });
    if (allLatLngs.length > 0) {
        map.fitBounds(L.latLngBounds(allLatLngs), { padding: [40, 40], maxZoom: 16 });
    }
}

/**
 * Invalidates map size (call when sidebar or container resizes).
 */
export function resizeMap() {
    if (map) {
        map.invalidateSize();
    }
}

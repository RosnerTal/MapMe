// ==========================================================================
// MapMe Web Dashboard - Spatial Corridor Route Consolidator
// Matches Android RouteConsolidator.kt engine.
// ==========================================================================

export const ROUTE_CONSOLIDATION_THRESHOLD_METERS = 35.0;
export const ROUTE_OVERLAP_THRESHOLD = 0.75;

const CELL_SIZE = 0.001; // ~100 meters spatial grid

function cellKey(lat, lon) {
    const cx = Math.floor(lon / CELL_SIZE);
    const cy = Math.floor(lat / CELL_SIZE);
    return `${cx},${cy}`;
}

export function pointToSegmentDistanceMeters(latP, lonP, latA, lonA, latB, lonB) {
    const R = 6371000; // meters
    const toRad = Math.PI / 180;

    const xA = lonA * toRad * Math.cos(((latA + latB) / 2) * toRad) * R;
    const yA = latA * toRad * R;

    const xB = lonB * toRad * Math.cos(((latA + latB) / 2) * toRad) * R;
    const yB = latB * toRad * R;

    const xP = lonP * toRad * Math.cos(((latA + latB) / 2) * toRad) * R;
    const yP = latP * toRad * R;

    const dx = xB - xA;
    const dy = yB - yA;
    const lenSq = dx * dx + dy * dy;

    if (lenSq === 0) {
        return Math.hypot(xP - xA, yP - yA);
    }

    const t = Math.max(0, Math.min(1, ((xP - xA) * dx + (yP - yA) * dy) / lenSq));
    const projX = xA + t * dx;
    const projY = yA + t * dy;

    return Math.hypot(xP - projX, yP - projY);
}

class SpatialIndex {
    constructor() {
        this.grid = new Map();
    }

    insert(seg) {
        const midLat = (seg.lat1 + seg.lat2) / 2.0;
        const midLon = (seg.lon1 + seg.lon2) / 2.0;
        const k = cellKey(midLat, midLon);
        if (!this.grid.has(k)) {
            this.grid.set(k, []);
        }
        this.grid.get(k).push(seg);
    }

    isPointNearAny(lat, lon, thresholdMeters) {
        const cx = Math.floor(lon / CELL_SIZE);
        const cy = Math.floor(lat / CELL_SIZE);

        for (let dx = -1; dx <= 1; dx++) {
            for (let dy = -1; dy <= 1; dy++) {
                const k = `${cx + dx},${cy + dy}`;
                const list = this.grid.get(k);
                if (!list) continue;
                for (let i = 0; i < list.length; i++) {
                    const seg = list[i];
                    const d = pointToSegmentDistanceMeters(lat, lon, seg.lat1, seg.lon1, seg.lat2, seg.lon2);
                    if (d <= thresholdMeters) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

/**
 * Consolidate a list of parsed routes into clean non-redundant polylines.
 * Walks and Drives are processed independently so they never merge together.
 *
 * @param {Array<{walk: Object, points: Array, isDrive: boolean}>} routes
 * @param {number} thresholdMeters
 * @param {number} overlapThreshold
 * @returns {Array<{points: Array, isDrive: boolean, sourceWalk: Object}>}
 */
export function consolidateRoutes(
    routes,
    thresholdMeters = ROUTE_CONSOLIDATION_THRESHOLD_METERS,
    overlapThreshold = ROUTE_OVERLAP_THRESHOLD
) {
    if (!routes || routes.length === 0) return [];

    const driveRoutes = routes.filter(r => r.isDrive);
    const walkRoutes = routes.filter(r => !r.isDrive);

    const outPolylines = [];
    consolidateGroup(driveRoutes, true, thresholdMeters, overlapThreshold, outPolylines);
    consolidateGroup(walkRoutes, false, thresholdMeters, overlapThreshold, outPolylines);

    return outPolylines;
}

function consolidateGroup(routes, isDrive, thresholdMeters, overlapThreshold, outPolylines) {
    // Sort by point count descending so the longest/most complete routes become baseline corridors
    const validRoutes = routes.filter(r => r.points && r.points.length >= 2)
        .sort((a, b) => b.points.length - a.points.length);

    const index = new SpatialIndex();

    for (const route of validRoutes) {
        const pts = route.points;
        const totalPoints = pts.length;

        let coveredCount = 0;
        const sampleStep = Math.max(1, Math.floor(totalPoints / 60)); // Check up to 60 sample points for speed

        let sampledCount = 0;
        for (let i = 0; i < totalPoints; i += sampleStep) {
            sampledCount++;
            if (index.isPointNearAny(pts[i].latitude, pts[i].longitude, thresholdMeters)) {
                coveredCount++;
            }
        }

        const overlapFraction = sampledCount > 0 ? (coveredCount / sampledCount) : 0;

        if (overlapFraction >= overlapThreshold) {
            // Highly overlapping route along an already-drawn corridor -> consolidate/skip duplicate line
            continue;
        }

        // Accept as a new master corridor line
        outPolylines.push({
            points: pts,
            isDrive: isDrive,
            sourceWalk: route.walk
        });

        // Index all segments of this accepted corridor
        for (let i = 0; i < totalPoints - 1; i++) {
            index.insert({
                lat1: pts[i].latitude,
                lon1: pts[i].longitude,
                lat2: pts[i + 1].latitude,
                lon2: pts[i + 1].longitude
            });
        }
    }
}

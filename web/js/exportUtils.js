// ==========================================================================
// MapMe Web Dashboard - GPX & KML Exporters
// ==========================================================================

function escapeXml(unsafe) {
    if (!unsafe) return "";
    return unsafe.replace(/[<>&'"]/g, (c) => {
        switch (c) {
            case "<": return "&lt;";
            case ">": return "&gt;";
            case "&": return "&amp;";
            case "'": return "&apos;";
            case "\"": return "&quot;";
        }
    });
}

/**
 * Generates a valid GPX 1.1 XML string from a walk record, points, and POIs.
 */
export function generateGpxXml(walk, points, pois = []) {
    const startTimeIso = new Date(walk.startTime || Date.now()).toISOString();
    const safeTitle = escapeXml(walk.title || "MapMe Route");

    let xml = `<?xml version="1.0" encoding="UTF-8"?>\n`;
    xml += `<gpx version="1.1" creator="MapMe" xmlns="http://www.topografix.com/GPX/1/1">\n`;
    xml += `  <metadata>\n`;
    xml += `    <name>${safeTitle}</name>\n`;
    xml += `    <time>${startTimeIso}</time>\n`;
    xml += `  </metadata>\n`;

    // Waypoints
    for (const poi of pois) {
        const name = escapeXml(poi.text || "Waypoint");
        const poiTimeIso = new Date(poi.timestamp || walk.startTime).toISOString();
        xml += `  <wpt lat="${poi.latitude}" lon="${poi.longitude}">\n`;
        xml += `    <name>${name}</name>\n`;
        xml += `    <time>${poiTimeIso}</time>\n`;
        xml += `  </wpt>\n`;
    }

    // Track
    xml += `  <trk>\n`;
    xml += `    <name>${safeTitle}</name>\n`;
    xml += `    <trkseg>\n`;
    for (const pt of points) {
        const ptTimeIso = new Date(pt.timestamp || walk.startTime).toISOString();
        const speed = pt.speed != null ? pt.speed : 0;
        xml += `      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">\n`;
        xml += `        <time>${ptTimeIso}</time>\n`;
        xml += `        <speed>${speed}</speed>\n`;
        xml += `      </trkpt>\n`;
    }
    xml += `    </trkseg>\n`;
    xml += `  </trk>\n`;
    xml += `</gpx>\n`;

    return xml;
}

/**
 * Generates a valid KML 2.2 XML string from a walk record, points, and POIs.
 */
export function generateKmlXml(walk, points, pois = []) {
    const safeTitle = escapeXml(walk.title || "MapMe Route");

    let xml = `<?xml version="1.0" encoding="UTF-8"?>\n`;
    xml += `<kml xmlns="http://www.opengis.net/kml/2.2">\n`;
    xml += `  <Document>\n`;
    xml += `    <name>${safeTitle}</name>\n`;
    xml += `    <Style id="routeLine">\n`;
    xml += `      <LineStyle>\n`;
    xml += `        <color>ff00ffff</color>\n`; // AABBGGRR: cyan
    xml += `        <width>5</width>\n`;
    xml += `      </LineStyle>\n`;
    xml += `    </Style>\n`;

    // Waypoints (Placemarks)
    for (let i = 0; i < pois.length; i++) {
        const poi = pois[i];
        const name = escapeXml(poi.text || `POI #${i + 1}`);
        xml += `    <Placemark>\n`;
        xml += `      <name>${name}</name>\n`;
        xml += `      <Point>\n`;
        xml += `        <coordinates>${poi.longitude},${poi.latitude},0</coordinates>\n`;
        xml += `      </Point>\n`;
        xml += `    </Placemark>\n`;
    }

    // Track Polyline
    if (points.length > 0) {
        xml += `    <Placemark>\n`;
        xml += `      <name>Track: ${safeTitle}</name>\n`;
        xml += `      <styleUrl>#routeLine</styleUrl>\n`;
        xml += `      <LineString>\n`;
        xml += `        <tessellate>1</tessellate>\n`;
        xml += `        <coordinates>\n`;
        const coordStr = points.map(pt => `${pt.longitude},${pt.latitude},0`).join(" ");
        xml += `          ${coordStr}\n`;
        xml += `        </coordinates>\n`;
        xml += `      </LineString>\n`;
        xml += `    </Placemark>\n`;
    }

    xml += `  </Document>\n`;
    xml += `</kml>\n`;

    return xml;
}

/**
 * Triggers a browser file download of string content.
 */
export function downloadFile(filename, content, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

/**
 * Convenience helper to export GPX file from walk data.
 */
export function exportGpx(walk, points, pois = []) {
    const xml = generateGpxXml(walk, points, pois);
    const safeName = (walk.title || "route").replace(/[^a-zA-Z0-9_-]/g, "_");
    downloadFile(`${safeName}_${walk.id || Date.now()}.gpx`, xml, "application/gpx+xml");
}

/**
 * Convenience helper to export KML file from walk data.
 */
export function exportKml(walk, points, pois = []) {
    const xml = generateKmlXml(walk, points, pois);
    const safeName = (walk.title || "route").replace(/[^a-zA-Z0-9_-]/g, "_");
    downloadFile(`${safeName}_${walk.id || Date.now()}.kml`, xml, "application/vnd.google-earth.kml+xml");
}

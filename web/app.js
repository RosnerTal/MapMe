// ==========================================================================
// MapMe Web Dashboard Javascript (Firebase Integration & Mapping)
// ==========================================================================

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { 
    getAuth, 
    signInWithPopup, 
    GoogleAuthProvider, 
    signInAnonymously, 
    signOut, 
    onAuthStateChanged 
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { 
    getFirestore, 
    collection, 
    doc, 
    setDoc, 
    query, 
    orderBy, 
    onSnapshot 
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";

// Firebase configuration
const firebaseConfig = {
  apiKey: "AIzaSyCpaljr7hHzbhUCrNMS5jfsl5jY2z5H4Gw",
  authDomain: "travel-39d90.firebaseapp.com",
  projectId: "travel-39d90",
  storageBucket: "travel-39d90.firebasestorage.app",
  messagingSenderId: "439123831099",
  appId: "1:439123831099:web:bbe9b31179680cd9a06f1e",
  measurementId: "G-4PFVZ3PJYQ"
};

// Initialize Firebase
const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);
const googleProvider = new GoogleAuthProvider();

// DOM elements
const authScreen = document.getElementById("auth-screen");
const dashboardScreen = document.getElementById("dashboard-screen");
const googleSigninBtn = document.getElementById("google-signin-btn");
const guestSigninBtn = document.getElementById("guest-signin-btn");
const signoutBtn = document.getElementById("signout-btn");

const userName = document.getElementById("user-name");
const userEmail = document.getElementById("user-email");
const userAvatar = document.getElementById("user-avatar");

const statWalks = document.getElementById("stat-walks");
const statDistance = document.getElementById("stat-distance");
const statDuration = document.getElementById("stat-duration");
const walksList = document.getElementById("walks-list");

const mapStatsCard = document.getElementById("map-stats-card");
const mapWalkTitle = document.getElementById("map-walk-title");
const mapWalkDistance = document.getElementById("map-walk-distance");
const mapWalkDuration = document.getElementById("map-walk-duration");
const mapWalkSpeed = document.getElementById("map-walk-speed");

// Leaflet map setup
let map = null;
let mapTileLayer = null;
let isDarkMap = true;
let showAllWalks = true;
let showWalks = true;
let showDrives = true;
let showPois = true;
let currentWalksData = []; // Store loaded walks
let pastWalkLayers = []; // Keep track of all drawn layers for past walks
let activePoiLayers = []; // Active POI layers
let pastPoiLayers = []; // Past POI layers
let activePolylineCore = null;
let activePolylineGlow = null;
let activeMarkerStart = null;
let activeMarkerEnd = null;

function initMap() {
    if (map) return;
    
    // Initialize map with neutral coordinates
    map = L.map("map", {
        zoomControl: false,
        attributionControl: false
    });
    
    L.control.zoom({ position: 'topleft' }).addTo(map);

    setMapTileSource();
    map.setView([0, 0], 2);
}

function setMapTileSource() {
    if (mapTileLayer) {
        map.removeLayer(mapTileLayer);
    }
    const tileUrl = isDarkMap 
        ? "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
        : "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png";
        
    mapTileLayer = L.tileLayer(tileUrl, {
        maxZoom: 20,
        attribution: '&copy; <a href="https://carto.com/">CartoDB</a> contributors'
    }).addTo(map);
}

// Formatting utilities
function formatDistance(meters) {
    if (meters < 1000) {
        return `${Math.round(meters)} m`;
    }
    return `${(meters / 1000).toFixed(2)} km`;
}

function formatDuration(millis) {
    const seconds = Math.floor(millis / 1000);
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    
    if (h > 0) {
        return `${h}h ${m}m`;
    } else if (m > 0) {
        return `${m}m ${s}s`;
    }
    return `${s}s`;
}

function formatSpeed(metersPerSec) {
    const kmh = metersPerSec * 3.6;
    return `${kmh.toFixed(1)} km/h`;
}

function getSegmentKey(lat1, lon1, lat2, lon2) {
    const rLat1 = lat1.toFixed(5);
    const rLon1 = lon1.toFixed(5);
    const rLat2 = lat2.toFixed(5);
    const rLon2 = lon2.toFixed(5);
    return (rLat1 + rLon1 < rLat2 + rLon2) 
        ? `${rLat1},${rLon1}_${rLat2},${rLon2}`
        : `${rLat2},${rLon2}_${rLat1},${rLon1}`;
}

// ----------------------------------------------------
// UI Screen Toggles & Listeners
// ----------------------------------------------------
onAuthStateChanged(auth, (user) => {
    if (user) {
        // Logged In state
        authScreen.classList.remove("active");
        dashboardScreen.classList.add("active");
        
        // Populate user profiles
        if (user.isAnonymous) {
            userName.textContent = "Guest Explorer";
            userEmail.textContent = "Offline Local Guest";
            userAvatar.src = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=100&h=100&q=80";
        } else {
            userName.textContent = user.displayName || "User";
            userEmail.textContent = user.email;
            userAvatar.src = user.photoURL || "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=100&h=100&q=80";
        }
        
        initMap();
        
        // Write profile info to database for statistics
        if (!user.isAnonymous) {
            setDoc(doc(db, "users", user.uid), {
                displayName: user.displayName,
                email: user.email,
                lastActive: new Date()
            }, { merge: true });
        }

        // Fetch walks logs in real-time
        loadWalks(user.uid);
    } else {
        // Logged Out state
        dashboardScreen.classList.remove("active");
        authScreen.classList.add("active");
        
        // Clear maps
        if (map) {
            map.remove();
            map = null;
        }
        activePolylineCore = null;
        activePolylineGlow = null;
        activeMarkerStart = null;
        activeMarkerEnd = null;
    }
});

// Load and listen to Firestore walk collection
function loadWalks(uid) {
    const walksRef = collection(db, "users", uid, "walks");
    const q = query(walksRef, orderBy("startTime", "desc"));
    
    // Empty loading state
    walksList.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>Fetching walks...</p></div>`;
    
    onSnapshot(q, (snapshot) => {
        walksList.innerHTML = "";
        
        if (snapshot.empty) {
            currentWalksData = [];
            redrawAllMapLayers();
            walksList.innerHTML = `
                <div class="empty-state">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="empty-icon"><circle cx="12" cy="12" r="10"></circle><line x1="8" y1="12" x2="16" y2="12"></line></svg>
                    <p>No walks uploaded yet</p>
                    <span style="font-size: 11px; text-align: center; color: var(--text-secondary); max-width: 200px;">Start exploring on your MapMe mobile app and upload walks to see them here!</span>
                </div>`;
                
            statWalks.textContent = "0";
            statDistance.textContent = "0 m";
            statDuration.textContent = "0s";
            mapStatsCard.classList.add("hidden");
            return;
        }
        
        let rawWalks = [];
        snapshot.forEach((doc) => {
            const data = doc.data();
            rawWalks.push({ id: doc.id, ...data });
        });

        currentWalksData = rawWalks;
        
        // Define timeframe filter rendering function
        window.filterTimeframe = function(timeframeType) {
            const cutoffTime = getCutoffTime(timeframeType);
            const filteredWalks = cutoffTime === 0 
                ? currentWalksData 
                : currentWalksData.filter(w => w.startTime >= cutoffTime);

            // Re-render statistics
            let totalDist = 0;
            let totalDur = 0;
            filteredWalks.forEach(w => {
                totalDist += w.totalDistanceMeters || 0;
                totalDur += w.totalDurationMillis || 0;
            });

            statWalks.textContent = filteredWalks.length.toString();
            statDistance.textContent = formatDistance(totalDist);
            statDuration.textContent = formatDuration(totalDur);

            // Re-render walks list elements
            walksList.innerHTML = "";
            if (filteredWalks.length === 0) {
                walksList.innerHTML = `
                    <div class="empty-state">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="empty-icon"><circle cx="12" cy="12" r="10"></circle><line x1="8" y1="12" x2="16" y2="12"></line></svg>
                        <p>No walks in this timeframe</p>
                    </div>`;
                return;
            }

            filteredWalks.forEach((walk, index) => {
                let points = [];
                try {
                    points = JSON.parse(walk.pointsJson);
                } catch(e) {}
                
                // Classify as drive if > 50% of the speeds are >= 7 km/h (1.944 m/s)
                let driveCount = 0;
                points.forEach(pt => {
                    if (pt.speed * 3.6 >= 7.0) driveCount++;
                });
                const isDrive = points.length > 0 && (driveCount / points.length) > 0.5;
                
                let displayTitle = walk.title;
                if (walk.title.startsWith("Walk at") || walk.title.startsWith("Drive at") || walk.title.startsWith("Walk on") || walk.title.startsWith("Drive on")) {
                    const separator = walk.title.includes("at ") ? "at " : "on ";
                    const timeLabel = walk.title.substring(walk.title.indexOf(separator) + separator.length);
                    displayTitle = isDrive ? `Drive ${separator}${timeLabel}` : `Walk ${separator}${timeLabel}`;
                }

                const card = document.createElement("div");
                card.className = "walk-card";
                card.dataset.id = walk.id;
                card.style.animationDelay = `${index * 0.05}s`;
                
                const distanceText = formatDistance(walk.totalDistanceMeters);
                const durationText = formatDuration(walk.totalDurationMillis);
                
                const startTimeDate = new Date(walk.startTime);
                const formattedDate = startTimeDate.toLocaleDateString(undefined, {
                    month: 'short',
                    day: 'numeric'
                });
                const formattedTime = startTimeDate.toLocaleTimeString(undefined, {
                    hour: '2-digit',
                    minute: '2-digit'
                });

                // Set different icons depending on mode
                const modeIcon = isDrive 
                    ? `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="var(--drive-accent, #ff3b30)" stroke-width="2" class="meta-icon" style="width:16px; height:16px;"><rect x="1" y="3" width="22" height="13" rx="2" ry="2"></rect><path d="M5 21a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm14 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"></path></svg>`
                    : `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="var(--neon-cyan)" stroke-width="2" class="meta-icon" style="width:16px; height:16px;"><path d="M18 19v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>`;
                
                card.innerHTML = `
                    <div class="walk-card-header">
                        <div class="walk-title" style="display: flex; align-items: center; gap: 6px;">
                            ${modeIcon}
                            <span>${displayTitle}</span>
                        </div>
                        <span class="walk-date-badge">${formattedDate}</span>
                    </div>
                    <div class="walk-meta">
                        <div class="meta-item">
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="meta-icon"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path><circle cx="12" cy="10" r="3"></circle></svg>
                            <span>${distanceText}</span>
                        </div>
                        <div class="meta-item">
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="meta-icon"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
                            <span>${durationText}</span>
                        </div>
                        <div class="meta-item" style="margin-left: auto; font-size: 11px; color: var(--text-muted);">
                            <span>${formattedTime}</span>
                        </div>
                    </div>
                `;
                
                card.addEventListener("click", () => selectWalk(walk, card));
                walksList.appendChild(card);
            });
        };

        // Render initially with selected active timeframe button state (week)
        const activeTimeframeBtn = document.querySelector(".timeframe-btn.active");
        const activeType = activeTimeframeBtn ? activeTimeframeBtn.id.replace("timeframe-", "").replace("-btn", "") : "week";
        window.filterTimeframe(activeType);

        // Trigger map drawing
        redrawAllMapLayers();
    });
}

// Utility timeframe math
function getCutoffTime(type) {
    const now = Date.now();
    switch(type) {
        case "week": return now - 7 * 24 * 60 * 60 * 1000;
        case "month": return now - 30 * 24 * 60 * 60 * 1000;
        case "three": return now - 90 * 24 * 60 * 60 * 1000;
        case "lifetime": default: return 0;
    }
}

// Select a walk card to draw route
function selectWalk(walk, cardElement) {
    const isAlreadyActive = cardElement.classList.contains("active");
    document.querySelectorAll(".walk-card").forEach(c => c.classList.remove("active"));
    
    if (isAlreadyActive) {
        // Deselect if clicked again
        mapStatsCard.classList.add("hidden");
    } else {
        cardElement.classList.add("active");
        
        // Populate stats overlay
        mapWalkTitle.textContent = walk.title;
        mapWalkDistance.textContent = formatDistance(walk.totalDistanceMeters);
        mapWalkDuration.textContent = formatDuration(walk.totalDurationMillis);
        
        const durationSeconds = walk.totalDurationMillis / 1000;
        const avgSpeed = durationSeconds > 0 ? walk.totalDistanceMeters / durationSeconds : 0;
        mapWalkSpeed.textContent = formatSpeed(avgSpeed);
        
        mapStatsCard.classList.remove("hidden");
    }
    
    redrawAllMapLayers(true); // Fit bounds only when explicitly clicked
}

let activeRouteLayers = []; // Keep track of current active polylines

function drawWalkRoute(walk, fitBounds = false) {
    // 2. Parse pointsJson
    let points = [];
    try {
        points = JSON.parse(walk.pointsJson);
    } catch(e) {
        console.error("Failed to parse pointsJson", e);
        return;
    }
    
    if (points.length === 0) return;
    
    // Draw segmented path based on speed (7 km/h = 1.944 m/s threshold)
    const baseWalkColor = '#06b6d4'; // Cyan for selected active walk
    const driveColor = '#ff3b30'; // Coral/Red for driving
    
    for (let i = 0; i < points.length - 1; i++) {
        const pt1 = points[i];
        const pt2 = points[i + 1];
        
        // Smart speed threshold: Compute the average speed of the local window
        const pointsWindow = [];
        if (i > 0) pointsWindow.push(points[i - 1]);
        pointsWindow.push(pt1);
        pointsWindow.push(pt2);
        if (i < points.length - 2) pointsWindow.push(points[i + 2]);
        
        // Determine travel mode (Walk vs Drive) by title prefix for backwards compatibility
        const isDriveModeByTitle = walk.title.startsWith("Drive on") || walk.title.startsWith("Drive at");
        const isWalkModeByTitle = walk.title.startsWith("Walk on") || walk.title.startsWith("Walk at");
        
        let isDriving = false;
        if (isDriveModeByTitle) {
            isDriving = true;
        } else if (isWalkModeByTitle) {
            isDriving = false;
        } else {
            // Fallback to speed threshold calculation if title has no clear mode keyword
            const avgSpeedKmh = (pointsWindow.reduce((sum, p) => sum + p.speed, 0) / pointsWindow.length) * 3.6;
            isDriving = avgSpeedKmh >= 7.0;
        }
        
        // Skip rendering if filtered out
        if (isDriving && !showDrives) continue;
        if (!isDriving && !showWalks) continue;

        const segmentLatLngs = [[pt1.latitude, pt1.longitude], [pt2.latitude, pt2.longitude]];
        const finalColor = isDriving ? driveColor : baseWalkColor;
        
        const glow = L.polyline(segmentLatLngs, {
            color: finalColor,
            opacity: 0.25,
            weight: 20,
            lineCap: 'round',
            lineJoin: 'round'
        }).addTo(map);
        
        const core = L.polyline(segmentLatLngs, {
            color: finalColor,
            opacity: 0.95,
            weight: 7,
            lineCap: 'round',
            lineJoin: 'round'
        }).addTo(map);
        
        activeRouteLayers.push(glow, core);
    }
    
    // 4. Draw start/end markers
    const greenDot = L.divIcon({ className: 'map-dot start-dot' });
    const redDot = L.divIcon({ className: 'map-dot end-dot' });
    
    const startLatLng = [points[0].latitude, points[0].longitude];
    const endLatLng = [points[points.length - 1].latitude, points[points.length - 1].longitude];
    
    activeMarkerStart = L.marker(startLatLng, { icon: greenDot }).addTo(map).bindPopup("Start point");
    activeMarkerEnd = L.marker(endLatLng, { icon: redDot }).addTo(map).bindPopup("End point");
    
    // 4.5 Draw POIs
    let pois = [];
    if (walk.poisJson) {
        try {
            pois = JSON.parse(walk.poisJson);
        } catch(e) {
            console.error("Failed to parse poisJson", e);
        }
    }
    
    if (showPois) {
        pois.forEach(poi => {
            const poiDot = L.divIcon({ className: 'map-poi-dot active-poi-dot' });
            
            let popupContent = `<div class="poi-popup">`;
            popupContent += `<h4>Point of Interest</h4>`;
            
            const dateStr = new Date(poi.timestamp).toLocaleDateString() + ' ' + new Date(poi.timestamp).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
            popupContent += `<span class="poi-time">${dateStr}</span>`;
            
            if (poi.text) {
                popupContent += `<p class="poi-desc">${poi.text}</p>`;
            }
            if (poi.imageBase64) {
                popupContent += `<img src="data:image/jpeg;base64,${poi.imageBase64}" class="poi-img" />`;
            }
            popupContent += `</div>`;
            
            const m = L.marker([poi.latitude, poi.longitude], { icon: poiDot })
                .addTo(map)
                .bindPopup(popupContent, { maxWidth: 240, className: 'custom-leaflet-popup' });
                
            activePoiLayers.push(m);
        });
    }
    
    // 5. Fit bounds to fit the route on screen
    if (fitBounds && activeRouteLayers.length > 0) {
        const group = new L.featureGroup(activeRouteLayers);
        map.fitBounds(group.getBounds(), { padding: [50, 50] });
    }
}

function redrawAllMapLayers(fitActiveWalk = false) {
    if (!map) return;

    // 1. Clean previous layers
    pastWalkLayers.forEach(layer => map.removeLayer(layer));
    pastWalkLayers = [];

    pastPoiLayers.forEach(layer => map.removeLayer(layer));
    pastPoiLayers = [];

    activePoiLayers.forEach(layer => map.removeLayer(layer));
    activePoiLayers = [];

    activeRouteLayers.forEach(layer => map.removeLayer(layer));
    activeRouteLayers = [];

    if (activeMarkerStart) map.removeLayer(activeMarkerStart);
    if (activeMarkerEnd) map.removeLayer(activeMarkerEnd);
    activeMarkerStart = null;
    activeMarkerEnd = null;

    if (currentWalksData.length === 0) return;

    const allLatLns = [];

    // 2. If showAllWalks is checked, draw all routes segmented by speed with thickness/opacity matching density
    if (showAllWalks) {
        // Pre-parse past walks and compute coordinates overlaps
        const parsedWalks = [];
        const segmentCountMap = {};

        currentWalksData.forEach(walk => {
            let pts = [];
            try {
                pts = JSON.parse(walk.pointsJson);
            } catch(e) {
                return;
            }
            if (pts.length === 0) return;
            parsedWalks.push({ walk, pts });

            // Count traversal frequency
            for (let i = 0; i < pts.length - 1; i++) {
                const pt1 = pts[i];
                const pt2 = pts[i + 1];
                const key = getSegmentKey(pt1.latitude, pt1.longitude, pt2.latitude, pt2.longitude);
                segmentCountMap[key] = (segmentCountMap[key] || 0) + 1;
            }
        });

        parsedWalks.forEach(({ walk, pts }) => {
            const activeCard = document.querySelector(".walk-card.active");
            const isSelected = activeCard && activeCard.dataset.id === walk.id;

            // Speed coloring constants for past walks
            const basePastColor = isSelected ? "#10b981" : "#8b5cf6"; // Neon Emerald if selected, otherwise Electric Violet
            const driveColor = isSelected ? "#ff3b30" : "#ee5859"; // Bright red if selected, otherwise lighter red
            
            // Build mode information for every point
            const pointsMode = []; // Array of booleans: true = driving, false = walking
            for (let i = 0; i < pts.length; i++) {
                // Determine travel mode (Walk vs Drive) by title prefix for backwards compatibility
                const isDriveModeByTitle = walk.title.startsWith("Drive on") || walk.title.startsWith("Drive at");
                const isWalkModeByTitle = walk.title.startsWith("Walk on") || walk.title.startsWith("Walk at");
                
                let isDriving = false;
                if (isDriveModeByTitle) {
                    isDriving = true;
                } else if (isWalkModeByTitle) {
                    isDriving = false;
                } else {
                    // Fallback to speed threshold calculation if title has no clear mode keyword
                    const pointsWindow = [];
                    const startIdx = Math.max(0, i - 1);
                    const endIdx = Math.min(pts.length - 1, i + 2);
                    for (let w = startIdx; w <= endIdx; w++) {
                        pointsWindow.push(pts[w]);
                    }
                    
                    const avgSpeedKmh = (pointsWindow.reduce((sum, p) => sum + p.speed, 0) / pointsWindow.length) * 3.6;
                    isDriving = avgSpeedKmh >= 7.0;
                }
                pointsMode.push(isDriving);
            }

            // Group contiguous points of the same travel mode
            let currentGroup = [];
            let currentGroupMode = null;

            for (let i = 0; i < pts.length; i++) {
                const pt = pts[i];
                const ptMode = pointsMode[i];

                if (currentGroup.length === 0) {
                    currentGroup.push([pt.latitude, pt.longitude]);
                    currentGroupMode = ptMode;
                } else if (ptMode === currentGroupMode) {
                    currentGroup.push([pt.latitude, pt.longitude]);
                } else {
                    // Draw current segment group
                    drawContiguousSegment(currentGroup, currentGroupMode, isSelected, walk);
                    // Start new group, carrying over the last point of the previous group to avoid gaps
                    currentGroup = [[pts[i - 1].latitude, pts[i - 1].longitude], [pt.latitude, pt.longitude]];
                    currentGroupMode = ptMode;
                }
            }

            if (currentGroup.length > 1) {
                drawContiguousSegment(currentGroup, currentGroupMode, isSelected, walk);
            }

            function drawContiguousSegment(latlngs, isDriving, isSelected, walk) {
                // Skip rendering if filtered out
                if (isDriving && !showDrives) return;
                if (!isDriving && !showWalks) return;

                latlngs.forEach(ll => allLatLns.push(ll));

                const finalColor = isDriving ? driveColor : basePastColor;
                
                // Group opacity and thickness properties
                const dynamicOpacity = isSelected ? 1.0 : 0.65;
                const dynamicWeight = isSelected ? 8.5 : 5.0;

                const pastLine = L.polyline(latlngs, {
                    color: finalColor,
                    opacity: dynamicOpacity,
                    weight: dynamicWeight,
                    lineCap: 'round',
                    lineJoin: 'round'
                }).addTo(map);

                pastLine.on("click", () => {
                    const card = document.querySelector(`.walk-card[data-id="${walk.id}"]`);
                    if (card) {
                        selectWalk(walk, card);
                        card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    }
                });

                pastWalkLayers.push(pastLine);
            }

            if (showPois) {
                let pois = [];
                if (walk.poisJson) {
                    try {
                        pois = JSON.parse(walk.poisJson);
                    } catch(e) {}
                }
                pois.forEach(poi => {
                    const poiDot = L.divIcon({ className: 'map-poi-dot past-poi-dot' });
                    
                    let popupContent = `<div class="poi-popup">`;
                    popupContent += `<h4>Point of Interest</h4>`;
                    const dateStr = new Date(poi.timestamp).toLocaleDateString() + ' ' + new Date(poi.timestamp).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
                    popupContent += `<span class="poi-time">${dateStr}</span>`;
                    if (poi.text) {
                        popupContent += `<p class="poi-desc">${poi.text}</p>`;
                    }
                    if (poi.imageBase64) {
                        popupContent += `<img src="data:image/jpeg;base64,${poi.imageBase64}" class="poi-img" />`;
                    }
                    popupContent += `</div>`;
                    
                    const m = L.marker([poi.latitude, poi.longitude], { icon: poiDot })
                        .addTo(map)
                        .bindPopup(popupContent, { maxWidth: 240, className: 'custom-leaflet-popup' });
                        
                    pastPoiLayers.push(m);
                });
            }
        });
    }

    // 3. Draw active walk in neon cyan (if selected)
    const activeCard = document.querySelector(".walk-card.active");
    if (activeCard) {
        const activeId = activeCard.dataset.id;
        const activeWalk = currentWalksData.find(w => w.id === activeId);
        if (activeWalk) {
            drawWalkRoute(activeWalk, fitActiveWalk);
        }
    } else if (allLatLns.length > 0 && showAllWalks && !fitActiveWalk) {
        // Fit map bounds to show all walks
        map.fitBounds(L.latLngBounds(allLatLns), { padding: [40, 40] });
    }
}

// Authentications Trigger click
googleSigninBtn.addEventListener("click", () => {
    signInWithPopup(auth, googleProvider).catch((error) => {
        console.error("Google Sign-In failed", error);
        alert(`Authentication failed: ${error.message}`);
    });
});

guestSigninBtn.addEventListener("click", () => {
    signInAnonymously(auth).catch((error) => {
        console.error("Anonymous Sign-In failed", error);
        alert(`Guest login failed: ${error.message}`);
    });
});

signoutBtn.addEventListener("click", () => {
    signOut(auth).catch((error) => {
        console.error("Sign Out failed", error);
    });
});

// Map theme toggle listener
const mapThemeBtn = document.getElementById("map-theme-btn");
const themeIconSvg = document.getElementById("theme-icon-svg");

if (mapThemeBtn) {
    mapThemeBtn.addEventListener("click", () => {
        isDarkMap = !isDarkMap;
        setMapTileSource();
        
        // Update Sun/Moon icon or button styling
        if (isDarkMap) {
            themeIconSvg.innerHTML = `<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>`;
            mapThemeBtn.title = "Switch to Light Map";
        } else {
            themeIconSvg.innerHTML = `<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>`;
            mapThemeBtn.title = "Switch to Dark Map";
        }
    });
}

// Timeframe Selector Buttons Listener
const timeframeBtns = document.querySelectorAll(".timeframe-btn");
timeframeBtns.forEach(btn => {
    btn.addEventListener("click", () => {
        timeframeBtns.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        
        const type = btn.id.replace("timeframe-", "").replace("-btn", "");
        if (typeof window.filterTimeframe === "function") {
            window.filterTimeframe(type);
        }
    });
});

// Show all walks checkbox listener
const showAllWalksCb = document.getElementById("show-all-walks-cb");
if (showAllWalksCb) {
    showAllWalksCb.addEventListener("change", (e) => {
        showAllWalks = e.target.checked;
        redrawAllMapLayers();
    });
}

// Show Walks filter checkbox listener
const showWalksCb = document.getElementById("show-walks-cb");
if (showWalksCb) {
    showWalksCb.addEventListener("change", (e) => {
        showWalks = e.target.checked;
        redrawAllMapLayers();
    });
}

// Show Drives filter checkbox listener
const showDrivesCb = document.getElementById("show-drives-cb");
if (showDrivesCb) {
    showDrivesCb.addEventListener("change", (e) => {
        showDrives = e.target.checked;
        redrawAllMapLayers();
    });
}

// Show POIs filter checkbox listener
const showPoisCb = document.getElementById("show-pois-cb");
if (showPoisCb) {
    showPoisCb.addEventListener("change", (e) => {
        showPois = e.target.checked;
        redrawAllMapLayers();
    });
}

// Lightbox Modal Image Preview Logic
const lightboxModal = document.getElementById("lightbox-modal");
const lightboxImg = document.getElementById("lightbox-img");
const lightboxClose = document.querySelector(".lightbox-close");

if (lightboxModal && lightboxImg && lightboxClose) {
    // Click on dynamically generated Leaflet popup images
    document.addEventListener("click", (e) => {
        if (e.target && e.target.classList.contains("poi-img")) {
            const src = e.target.src;
            if (src) {
                lightboxImg.src = src;
                lightboxModal.classList.add("active");
            }
        }
    });

    // Close on clicking 'X'
    lightboxClose.addEventListener("click", () => {
        lightboxModal.classList.remove("active");
    });

    // Close on clicking backdrop
    lightboxModal.addEventListener("click", (e) => {
        if (e.target === lightboxModal) {
            lightboxModal.classList.remove("active");
        }
    });
}

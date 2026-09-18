// ==========================================================================
// MapMe Web Dashboard - Main Application Entry Point
// ==========================================================================

import { collection, query, orderBy, onSnapshot } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { db } from "./js/config.js";
import { initAuth, signInWithGoogle, signInAsGuest, signOutUser } from "./js/auth.js";
import { 
    initMap, 
    toggleMapTheme, 
    isDarkTheme, 
    drawActiveWalk, 
    drawConsolidatedCorridors, 
    fitAllWalks, 
    parseWalkPoints, 
    parseWalkPois,
    clearActiveLayers,
    clearPastLayers 
} from "./js/mapView.js";
import { 
    initUi, 
    showAuthScreen, 
    showDashboardScreen, 
    updateThemeIcon, 
    renderTopStats, 
    renderCorridorBanner, 
    renderWalksList, 
    renderSelectedWalkDetails, 
    hideSelectedWalkDetails, 
    getActiveTimeframe, 
    getFilterStates 
} from "./js/ui.js";
import { exportGpx, exportKml } from "./js/exportUtils.js";
import { consolidateRoutes } from "./js/routeConsolidator.js";

// Global Application State
let allWalks = [];
let activeWalk = null;
let firestoreUnsubscribe = null;

// Timeframe math
function getCutoffTime(type) {
    const now = Date.now();
    switch (type) {
        case "week": return now - 7 * 24 * 60 * 60 * 1000;
        case "month": return now - 30 * 24 * 60 * 60 * 1000;
        case "three": return now - 90 * 24 * 60 * 60 * 1000;
        case "lifetime": default: return 0;
    }
}

function getFilteredWalks() {
    const timeframe = getActiveTimeframe();
    const cutoff = getCutoffTime(timeframe);
    if (cutoff === 0) return allWalks;
    return allWalks.filter(w => (w.startTime || 0) >= cutoff);
}

/**
 * Refreshes dashboard statistics, corridor banner, walk list, and map polylines.
 */
function refreshDashboard({ fitActive = false, fitAll = false } = {}) {
    const filteredWalks = getFilteredWalks();
    const filters = getFilterStates();

    // 1. Calculate and update summary stats
    let totalDist = 0;
    let totalDur = 0;
    filteredWalks.forEach(w => {
        totalDist += (w.totalDistanceMeters || 0);
        totalDur += (w.totalDurationMillis || 0);
    });

    renderTopStats({
        trackCount: filteredWalks.length,
        totalDistanceMeters: totalDist,
        totalDurationMillis: totalDur
    });

    // 2. Count consolidated corridors for the showcase banner
    let totalTripsWithCoords = 0;
    const segmentsForCounting = [];
    filteredWalks.forEach(w => {
        const pts = parseWalkPoints(w);
        if (pts.length >= 2) {
            totalTripsWithCoords++;
            segmentsForCounting.push({
                walk: w,
                points: pts,
                isDrive: ((pts[0].speed || 0) * 3.6) >= 7.0
            });
        }
    });

    const consolidatedCount = consolidateRoutes(segmentsForCounting).length;
    renderCorridorBanner({
        totalTrips: totalTripsWithCoords,
        condensedCorridors: consolidatedCount
    });

    // 3. Render sidebar walks list
    renderWalksList(filteredWalks, activeWalk ? activeWalk.id : null);

    // 4. Update map display
    drawConsolidatedCorridors(filteredWalks, activeWalk ? activeWalk.id : null, {
        showAllHistory: filters.showAllHistory,
        showWalks: filters.showWalks,
        showDrives: filters.showDrives,
        showPois: filters.showPois,
        onSelectWalk: handleSelectWalk
    });

    if (activeWalk) {
        drawActiveWalk(activeWalk, {
            showWalks: filters.showWalks,
            showDrives: filters.showDrives,
            showPois: filters.showPois,
            fitBounds: fitActive
        });
        renderSelectedWalkDetails(activeWalk);
    } else {
        clearActiveLayers();
        hideSelectedWalkDetails();
        if (fitAll && filteredWalks.length > 0) {
            fitAllWalks(filteredWalks);
        }
    }
}

/**
 * Handles selecting or toggling a walk.
 */
function handleSelectWalk(walk) {
    if (activeWalk && activeWalk.id === walk.id) {
        // Deselect
        activeWalk = null;
        refreshDashboard({ fitActive: false, fitAll: false });
    } else {
        // Select
        activeWalk = walk;
        refreshDashboard({ fitActive: true, fitAll: false });
    }
}

/**
 * Handles explicit deselect from stats card close button.
 */
function handleDeselectWalk() {
    activeWalk = null;
    refreshDashboard({ fitActive: false, fitAll: false });
}

/**
 * Subscribes to real-time walk records for user.
 */
function subscribeToWalks(uid) {
    if (firestoreUnsubscribe) {
        firestoreUnsubscribe();
        firestoreUnsubscribe = null;
    }

    const walksRef = collection(db, "users", uid, "walks");
    const q = query(walksRef, orderBy("startTime", "desc"));

    firestoreUnsubscribe = onSnapshot(q, (snapshot) => {
        const loaded = [];
        snapshot.forEach(doc => {
            loaded.push({ id: doc.id, ...doc.data() });
        });
        allWalks = loaded;

        // If previously selected walk was deleted or updated, keep in sync
        if (activeWalk) {
            const updated = allWalks.find(w => w.id === activeWalk.id);
            activeWalk = updated || null;
        }

        refreshDashboard({ fitActive: false, fitAll: true });
    }, (error) => {
        console.error("Firestore onSnapshot error:", error);
    });
}

/**
 * Handles GPX export for selected walk.
 */
function handleExportGpx() {
    if (!activeWalk) return;
    const points = parseWalkPoints(activeWalk);
    const pois = parseWalkPois(activeWalk);
    exportGpx(activeWalk, points, pois);
}

/**
 * Handles KML export for selected walk.
 */
function handleExportKml() {
    if (!activeWalk) return;
    const points = parseWalkPoints(activeWalk);
    const pois = parseWalkPois(activeWalk);
    exportKml(activeWalk, points, pois);
}

// --------------------------------------------------------------------------
// Bootstrap Application
// --------------------------------------------------------------------------
document.addEventListener("DOMContentLoaded", () => {
    // Initialize UI and handlers
    initUi({
        onSelectWalk: handleSelectWalk,
        onDeselectWalk: handleDeselectWalk,
        onTimeframeChange: () => {
            refreshDashboard({ fitActive: false, fitAll: true });
        },
        onFilterChange: () => {
            refreshDashboard({ fitActive: false, fitAll: false });
        },
        onExportGpx: handleExportGpx,
        onExportKml: handleExportKml,
        onSignInGoogle: () => {
            signInWithGoogle().catch(err => {
                console.error("Google Sign-In error:", err);
                alert(`Google Sign-In failed: ${err.message}`);
            });
        },
        onSignInGuest: () => {
            signInAsGuest().catch(err => {
                console.error("Guest Sign-In error:", err);
                alert(`Guest Sign-In failed: ${err.message}`);
            });
        },
        onSignOut: () => {
            signOutUser().catch(err => {
                console.error("Sign Out error:", err);
            });
        },
        onThemeToggle: () => {
            const isDark = toggleMapTheme();
            updateThemeIcon(isDark);
        }
    });

    // Initialize Auth listener
    initAuth({
        onLogin: (user) => {
            showDashboardScreen(user);
            initMap("map");
            updateThemeIcon(isDarkTheme());
            subscribeToWalks(user.uid);
        },
        onLogout: () => {
            if (firestoreUnsubscribe) {
                firestoreUnsubscribe();
                firestoreUnsubscribe = null;
            }
            allWalks = [];
            activeWalk = null;
            clearActiveLayers();
            clearPastLayers();
            showAuthScreen();
        }
    });
});

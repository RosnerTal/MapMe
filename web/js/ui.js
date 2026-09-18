// ==========================================================================
// MapMe Web Dashboard - UI Components & DOM Orchestration
// Cyberpunk Glassmorphic Visuals matching Android App v4.7
// ==========================================================================

import { formatDistance, formatTime, formatSpeed, formatDate, formatCompactDate } from "./formatters.js";
import { parseWalkPoints, parseWalkPois, classifyPointModes } from "./mapView.js";

// DOM Elements cache
let authScreen = null;
let dashboardScreen = null;
let googleSigninBtn = null;
let guestSigninBtn = null;
let signoutBtn = null;
let mapThemeBtn = null;
let themeIconSvg = null;

let userNameEl = null;
let userEmailEl = null;
let userAvatarEl = null;

let statWalksEl = null;
let statDistanceEl = null;
let statDurationEl = null;
let corridorBannerEl = null;

let walksListEl = null;
let mapStatsCard = null;
let mapWalkTitle = null;
let mapWalkBadge = null;
let mapWalkDistance = null;
let mapWalkDuration = null;
let mapWalkSpeed = null;
let mapWalkMaxSpeed = null;
let mapWalkPoints = null;
let mapWalkDate = null;
let exportGpxBtn = null;
let exportKmlBtn = null;
let closeStatsBtn = null;

let showAllWalksCb = null;
let showWalksCb = null;
let showDrivesCb = null;
let showPoisCb = null;

let lightboxModal = null;
let lightboxImg = null;
let lightboxClose = null;

let eventHandlers = {};

/**
 * Initializes DOM element references and sets up event listeners.
 */
export function initUi(handlers = {}) {
    eventHandlers = handlers;

    authScreen = document.getElementById("auth-screen");
    dashboardScreen = document.getElementById("dashboard-screen");
    googleSigninBtn = document.getElementById("google-signin-btn");
    guestSigninBtn = document.getElementById("guest-signin-btn");
    signoutBtn = document.getElementById("signout-btn");
    mapThemeBtn = document.getElementById("map-theme-btn");
    themeIconSvg = document.getElementById("theme-icon-svg");

    userNameEl = document.getElementById("user-name");
    userEmailEl = document.getElementById("user-email");
    userAvatarEl = document.getElementById("user-avatar");

    statWalksEl = document.getElementById("stat-walks");
    statDistanceEl = document.getElementById("stat-distance");
    statDurationEl = document.getElementById("stat-duration");
    corridorBannerEl = document.getElementById("corridor-banner");

    walksListEl = document.getElementById("walks-list");
    mapStatsCard = document.getElementById("map-stats-card");
    mapWalkTitle = document.getElementById("map-walk-title");
    mapWalkBadge = document.getElementById("map-walk-badge");
    mapWalkDistance = document.getElementById("map-walk-distance");
    mapWalkDuration = document.getElementById("map-walk-duration");
    mapWalkSpeed = document.getElementById("map-walk-speed");
    mapWalkMaxSpeed = document.getElementById("map-walk-max-speed");
    mapWalkPoints = document.getElementById("map-walk-points");
    mapWalkDate = document.getElementById("map-walk-date");
    exportGpxBtn = document.getElementById("export-gpx-btn");
    exportKmlBtn = document.getElementById("export-kml-btn");
    closeStatsBtn = document.getElementById("close-stats-btn");

    showAllWalksCb = document.getElementById("show-all-walks-cb");
    showWalksCb = document.getElementById("show-walks-cb");
    showDrivesCb = document.getElementById("show-drives-cb");
    showPoisCb = document.getElementById("show-pois-cb");

    lightboxModal = document.getElementById("lightbox-modal");
    lightboxImg = document.getElementById("lightbox-img");
    lightboxClose = document.querySelector(".lightbox-close");

    attachEventListeners();
}

function attachEventListeners() {
    // Auth buttons
    if (googleSigninBtn) {
        googleSigninBtn.addEventListener("click", () => {
            if (eventHandlers.onSignInGoogle) eventHandlers.onSignInGoogle();
        });
    }

    if (guestSigninBtn) {
        guestSigninBtn.addEventListener("click", () => {
            if (eventHandlers.onSignInGuest) eventHandlers.onSignInGuest();
        });
    }

    if (signoutBtn) {
        signoutBtn.addEventListener("click", () => {
            if (eventHandlers.onSignOut) eventHandlers.onSignOut();
        });
    }

    // Map theme toggle
    if (mapThemeBtn) {
        mapThemeBtn.addEventListener("click", () => {
            if (eventHandlers.onThemeToggle) eventHandlers.onThemeToggle();
        });
    }

    // Timeframe selector buttons
    const timeframeBtns = document.querySelectorAll(".timeframe-btn");
    timeframeBtns.forEach(btn => {
        btn.addEventListener("click", () => {
            timeframeBtns.forEach(b => b.classList.remove("active"));
            btn.classList.add("active");
            const type = btn.id.replace("timeframe-", "").replace("-btn", "");
            if (eventHandlers.onTimeframeChange) eventHandlers.onTimeframeChange(type);
        });
    });

    // Layer checkboxes
    const triggerFilterChange = () => {
        if (eventHandlers.onFilterChange) {
            eventHandlers.onFilterChange(getFilterStates());
        }
    };

    if (showAllWalksCb) showAllWalksCb.addEventListener("change", triggerFilterChange);
    if (showWalksCb) showWalksCb.addEventListener("change", triggerFilterChange);
    if (showDrivesCb) showDrivesCb.addEventListener("change", triggerFilterChange);
    if (showPoisCb) showPoisCb.addEventListener("change", triggerFilterChange);

    // Export buttons
    if (exportGpxBtn) {
        exportGpxBtn.addEventListener("click", () => {
            if (eventHandlers.onExportGpx) eventHandlers.onExportGpx();
        });
    }

    if (exportKmlBtn) {
        exportKmlBtn.addEventListener("click", () => {
            if (eventHandlers.onExportKml) eventHandlers.onExportKml();
        });
    }

    // Close stats card
    if (closeStatsBtn) {
        closeStatsBtn.addEventListener("click", () => {
            if (eventHandlers.onDeselectWalk) eventHandlers.onDeselectWalk();
        });
    }

    // Lightbox modal closing
    if (lightboxClose) {
        lightboxClose.addEventListener("click", closeLightbox);
    }
    if (lightboxModal) {
        lightboxModal.addEventListener("click", (e) => {
            if (e.target === lightboxModal) closeLightbox();
        });
    }

    // Delegated click on Leaflet popup image for Lightbox preview
    document.addEventListener("click", (e) => {
        if (e.target && e.target.classList.contains("poi-img")) {
            openLightbox(e.target.src);
        }
    });
}

/**
 * Gets current filter checkbox states.
 */
export function getFilterStates() {
    return {
        showAllHistory: showAllWalksCb ? showAllWalksCb.checked : true,
        showWalks: showWalksCb ? showWalksCb.checked : true,
        showDrives: showDrivesCb ? showDrivesCb.checked : true,
        showPois: showPoisCb ? showPoisCb.checked : true
    };
}

/**
 * Gets active timeframe type: 'week' | 'month' | 'three' | 'lifetime'
 */
export function getActiveTimeframe() {
    const activeBtn = document.querySelector(".timeframe-btn.active");
    if (!activeBtn) return "week";
    return activeBtn.id.replace("timeframe-", "").replace("-btn", "");
}

/**
 * Switches screen to Auth view.
 */
export function showAuthScreen() {
    if (dashboardScreen) dashboardScreen.classList.remove("active");
    if (authScreen) authScreen.classList.add("active");
}

/**
 * Switches screen to Dashboard view and renders user profile.
 */
export function showDashboardScreen(user) {
    if (authScreen) authScreen.classList.remove("active");
    if (dashboardScreen) dashboardScreen.classList.add("active");

    if (user.isAnonymous) {
        if (userNameEl) userNameEl.textContent = "Guest Explorer";
        if (userEmailEl) userEmailEl.textContent = "Offline / Local Guest";
        if (userAvatarEl) userAvatarEl.src = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=100&h=100&q=80";
    } else {
        if (userNameEl) userNameEl.textContent = user.displayName || "Explorer";
        if (userEmailEl) userEmailEl.textContent = user.email || "";
        if (userAvatarEl) userAvatarEl.src = user.photoURL || "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=100&h=100&q=80";
    }
}

/**
 * Updates map theme toggle icon.
 */
export function updateThemeIcon(isDark) {
    if (!themeIconSvg || !mapThemeBtn) return;
    if (isDark) {
        themeIconSvg.innerHTML = `<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>`;
        mapThemeBtn.title = "Switch to Light Map";
    } else {
        themeIconSvg.innerHTML = `<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>`;
        mapThemeBtn.title = "Switch to Dark Map";
    }
}

/**
 * Renders top stats cards.
 */
export function renderTopStats({ trackCount = 0, totalDistanceMeters = 0, totalDurationMillis = 0 }) {
    if (statWalksEl) statWalksEl.textContent = trackCount.toString();
    if (statDistanceEl) statDistanceEl.textContent = formatDistance(totalDistanceMeters);
    if (statDurationEl) statDurationEl.textContent = formatTime(totalDurationMillis);
}

/**
 * Renders corridor banner showcasing deduplication stats.
 */
export function renderCorridorBanner({ totalTrips = 0, condensedCorridors = 0 }) {
    if (!corridorBannerEl) return;
    if (totalTrips <= 1 || condensedCorridors >= totalTrips) {
        corridorBannerEl.classList.add("hidden");
        return;
    }
    corridorBannerEl.classList.remove("hidden");
    const saved = totalTrips - condensedCorridors;
    corridorBannerEl.innerHTML = `
        <div class="corridor-banner-content">
            <span class="corridor-sparkle">✨</span>
            <span class="corridor-text">
                <strong>Spatial Corridor Deduplication Active:</strong>
                ${totalTrips} recorded routes cleanly consolidated into ${condensedCorridors} corridors (${saved} duplicates hidden from map clutter).
            </span>
        </div>
    `;
}

/**
 * Classifies a walk as primarily drive or walk.
 */
export function isWalkDrive(walk) {
    const titleLower = (walk.title || "").toLowerCase();
    if (titleLower.startsWith("drive on") || titleLower.startsWith("drive at")) return true;
    if (titleLower.startsWith("walk on") || titleLower.startsWith("walk at")) return false;

    const points = parseWalkPoints(walk);
    if (points.length === 0) return false;

    let driveCount = 0;
    points.forEach(p => {
        if ((p.speed || 0) * 3.6 >= 7.0) driveCount++;
    });
    return (driveCount / points.length) > 0.5;
}

/**
 * Renders walks list sidebar with cyberpunk cards.
 */
export function renderWalksList(walks, activeWalkId) {
    if (!walksListEl) return;
    walksListEl.innerHTML = "";

    if (!walks || walks.length === 0) {
        walksListEl.innerHTML = `
            <div class="empty-state">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="empty-icon"><circle cx="12" cy="12" r="10"></circle><line x1="8" y1="12" x2="16" y2="12"></line></svg>
                <p>No routes found</p>
                <span class="empty-subtext">Record walking or driving tracks on your MapMe mobile app to view them here.</span>
            </div>
        `;
        return;
    }

    walks.forEach((walk, index) => {
        const isDrive = isWalkDrive(walk);
        const pois = parseWalkPois(walk);
        const points = parseWalkPoints(walk);

        const card = document.createElement("div");
        card.className = "walk-card";
        card.dataset.id = walk.id;
        if (walk.id === activeWalkId) {
            card.classList.add("active");
        }
        card.style.animationDelay = `${Math.min(index * 0.04, 0.4)}s`;

        const distanceText = formatDistance(walk.totalDistanceMeters || 0);
        const durationText = formatTime(walk.totalDurationMillis || 0);
        const dateText = formatCompactDate(walk.startTime);

        // Icons
        const modeBadge = isDrive
            ? `<span class="mode-pill drive"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="pill-icon"><rect x="1" y="3" width="22" height="13" rx="2" ry="2"></rect><circle cx="6" cy="18" r="2"></circle><circle cx="18" cy="18" r="2"></circle></svg> Drive</span>`
            : `<span class="mode-pill walk"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="pill-icon"><path d="M18 19v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg> Walk</span>`;

        const poiIndicator = pois.length > 0
            ? `<span class="poi-counter-pill" title="${pois.length} Geotagged Points of Interest">📷 ${pois.length}</span>`
            : "";

        card.innerHTML = `
            <div class="walk-card-header">
                <div class="walk-title-group">
                    <span class="walk-title">${walk.title || "Route"}</span>
                </div>
                ${modeBadge}
            </div>
            <div class="walk-meta">
                <div class="meta-item" title="Distance">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="meta-icon"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path><circle cx="12" cy="10" r="3"></circle></svg>
                    <span>${distanceText}</span>
                </div>
                <div class="meta-item" title="Duration">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="meta-icon"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
                    <span>${durationText}</span>
                </div>
                <div class="meta-item date-item">
                    <span>${dateText}</span>
                </div>
                ${poiIndicator}
            </div>
        `;

        card.addEventListener("click", () => {
            if (eventHandlers.onSelectWalk) {
                eventHandlers.onSelectWalk(walk);
            }
        });

        walksListEl.appendChild(card);
    });
}

/**
 * Updates selected walk analytics overlay card with 2x3 metrics and export actions.
 */
export function renderSelectedWalkDetails(walk) {
    if (!mapStatsCard || !walk) return;

    const isDrive = isWalkDrive(walk);
    const points = parseWalkPoints(walk);
    const pois = parseWalkPois(walk);

    const durationSeconds = (walk.totalDurationMillis || 0) / 1000;
    const avgSpeed = durationSeconds > 0 ? (walk.totalDistanceMeters || 0) / durationSeconds : 0;

    let maxSpeed = 0;
    points.forEach(p => {
        if ((p.speed || 0) > maxSpeed) maxSpeed = p.speed;
    });

    if (mapWalkTitle) mapWalkTitle.textContent = walk.title || "Route Details";
    if (mapWalkBadge) {
        mapWalkBadge.className = `mode-pill ${isDrive ? "drive" : "walk"}`;
        mapWalkBadge.textContent = isDrive ? "DRIVE" : "WALK";
    }
    if (mapWalkDistance) mapWalkDistance.textContent = formatDistance(walk.totalDistanceMeters || 0);
    if (mapWalkDuration) mapWalkDuration.textContent = formatTime(walk.totalDurationMillis || 0);
    if (mapWalkSpeed) mapWalkSpeed.textContent = formatSpeed(avgSpeed);
    if (mapWalkMaxSpeed) mapWalkMaxSpeed.textContent = formatSpeed(maxSpeed);
    if (mapWalkPoints) mapWalkPoints.textContent = `${points.length} pts ${pois.length > 0 ? `• ${pois.length} POIs` : ""}`;
    if (mapWalkDate) mapWalkDate.textContent = formatDate(walk.startTime);

    mapStatsCard.classList.remove("hidden");
}

/**
 * Hides selected walk card.
 */
export function hideSelectedWalkDetails() {
    if (mapStatsCard) {
        mapStatsCard.classList.add("hidden");
    }
    document.querySelectorAll(".walk-card.active").forEach(c => c.classList.remove("active"));
}

/**
 * Opens image in full-screen Lightbox modal.
 */
export function openLightbox(src) {
    if (!lightboxModal || !lightboxImg || !src) return;
    lightboxImg.src = src;
    lightboxModal.classList.add("active");
}

/**
 * Closes the image Lightbox modal.
 */
export function closeLightbox() {
    if (!lightboxModal) return;
    lightboxModal.classList.remove("active");
    if (lightboxImg) lightboxImg.src = "";
}

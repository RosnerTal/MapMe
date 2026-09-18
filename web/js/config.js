// ==========================================================================
// MapMe Web Dashboard - Configuration & Constants
// ==========================================================================

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { getAuth, GoogleAuthProvider } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { getFirestore } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";

export const APP_VERSION = "4.7";
export const WALK_SPEED_LIMIT_KMH = 7.0;
export const ROUTE_CONSOLIDATION_THRESHOLD_METERS = 35.0;
export const ROUTE_OVERLAP_THRESHOLD = 0.75;

export const firebaseConfig = {
    apiKey: "AIzaSyCpaljr7hHzbhUCrNMS5jfsl5jY2z5H4Gw",
    authDomain: "travel-39d90.firebaseapp.com",
    projectId: "travel-39d90",
    storageBucket: "travel-39d90.firebasestorage.app",
    messagingSenderId: "439123831099",
    appId: "1:439123831099:web:bbe9b31179680cd9a06f1e",
    measurementId: "G-4PFVZ3PJYQ"
};

// Initialize Firebase services
export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app);
export const googleProvider = new GoogleAuthProvider();

// ==========================================================================
// MapMe Web Dashboard - Authentication Module
// ==========================================================================

import { 
    signInWithPopup, 
    signInAnonymously, 
    signOut, 
    onAuthStateChanged 
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { doc, setDoc } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { auth, db, googleProvider } from "./config.js";

let currentUser = null;

/**
 * Initializes auth state observer.
 * @param {Object} options
 * @param {function(User): void} options.onLogin
 * @param {function(): void} options.onLogout
 */
export function initAuth({ onLogin, onLogout }) {
    onAuthStateChanged(auth, async (user) => {
        currentUser = user;
        if (user) {
            // Write profile info to database for statistics if non-anonymous
            if (!user.isAnonymous) {
                try {
                    await setDoc(doc(db, "users", user.uid), {
                        displayName: user.displayName,
                        email: user.email,
                        photoURL: user.photoURL,
                        lastActive: new Date()
                    }, { merge: true });
                } catch (err) {
                    console.warn("Could not sync user profile:", err);
                }
            }
            if (typeof onLogin === "function") {
                onLogin(user);
            }
        } else {
            if (typeof onLogout === "function") {
                onLogout();
            }
        }
    });
}

/**
 * Signs in using Google Popup.
 */
export async function signInWithGoogle() {
    return signInWithPopup(auth, googleProvider);
}

/**
 * Signs in anonymously for guest/preview testing.
 */
export async function signInAsGuest() {
    return signInAnonymously(auth);
}

/**
 * Signs out current user.
 */
export async function signOutUser() {
    return signOut(auth);
}

/**
 * Gets currently authenticated user.
 */
export function getCurrentUser() {
    return currentUser;
}

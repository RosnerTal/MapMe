package com.talapp.mapme

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.talapp.mapme.theme.MapMeTheme
import com.talapp.mapme.ui.WalkViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {

  private val walkViewModel: WalkViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Dynamic Firebase initialization using your web configuration parameters
    try {
      val options = com.google.firebase.FirebaseOptions.Builder()
        .setApiKey("AIzaSyCbhXlhUCzQB0ol_D6rfUnjqkJu2L7iF-8")
        .setApplicationId("1:439123831099:android:52211075740d8340a06f1e") // Updated to com.talapp.mapme client ID
        .setProjectId("travel-39d90")
        .setStorageBucket("travel-39d90.firebasestorage.app")
        .build()

      if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(this, options)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }

    enableEdgeToEdge()

    // Configure Google Sign-In
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
      .requestIdToken("439123831099-t4n1aoiq074fmepsanmheedprqpgjk5q.apps.googleusercontent.com")
      .requestEmail()
      .build()
    val googleSignInClient = GoogleSignIn.getClient(this, gso)

    val googleSignInLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
      try {
          val account = task.getResult(ApiException::class.java)
          account.idToken?.let { token ->
              walkViewModel.signInWithGoogleCredential(token)
          } ?: run {
              android.widget.Toast.makeText(this, "Sign-In Error: idToken is null", android.widget.Toast.LENGTH_LONG).show()
          }
      } catch (e: ApiException) {
          android.widget.Toast.makeText(this, "Google Sign-In Failed: API Exception Code " + e.statusCode, android.widget.Toast.LENGTH_LONG).show()
          e.printStackTrace()
      } catch (e: Exception) {
          android.widget.Toast.makeText(this, "Sign-In Failed: " + e.localizedMessage, android.widget.Toast.LENGTH_LONG).show()
          e.printStackTrace()
      }
    }

    setContent {
      MapMeTheme {
        val permissionLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { /* Handles results */ }

        LaunchedEffect(Unit) {
          val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
          )
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
          }
          permissionLauncher.launch(list.toTypedArray())
        }

        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          MainNavigation(
            walkViewModel = walkViewModel,
            onGoogleSignInClick = {
              val signInIntent = googleSignInClient.signInIntent
              googleSignInLauncher.launch(signInIntent)
            }
          )
        }
      }
    }
  }
}

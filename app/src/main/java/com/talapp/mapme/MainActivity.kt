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
        .setApiKey("AIzaSyCpaljr7hHzbhUCrNMS5jfsl5jY2z5H4Gw")
        .setApplicationId("1:439123831099:android:a4a6e8df81878d38") // Changed to Android app id
        .setProjectId("travel-39d90")
        .setStorageBucket("travel-39d90.appspot.com")
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
          }
      } catch (e: ApiException) {
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

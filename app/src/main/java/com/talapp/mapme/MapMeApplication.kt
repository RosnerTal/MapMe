package com.talapp.mapme

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class MapMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyCbhXlhUCzQB0ol_D6rfUnjqkJu2L7iF-8")
                .setApplicationId("1:439123831099:android:52211075740d8340a06f1e")
                .setProjectId("travel-39d90")
                .setStorageBucket("travel-39d90.firebasestorage.app")
                .build()

            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            org.osmdroid.config.Configuration.getInstance().load(
                this,
                getSharedPreferences("osmdroid", MODE_PRIVATE)
            )
            org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

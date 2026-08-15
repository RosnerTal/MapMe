package com.talapp.mapme.services

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.Row
import androidx.car.app.model.Header
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.CarLocation
import androidx.car.app.validation.HostValidator

class MapMeCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return MapMeDashboardScreen(carContext)
            }
        }
    }
}

class MapMeDashboardScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        // Render a Simple POI List informing user of MapMe Android Auto mode
        val row = Row.Builder()
            .setTitle("MapMe Active Tracking")
            .addText("MapMe is running on your phone. Please manage active trips and view maps directly on your device screens.")
            .build()

        val pane = Pane.Builder()
            .addRow(row)
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(Header.Builder().setTitle("MapMe").build())
            .build()
    }
}

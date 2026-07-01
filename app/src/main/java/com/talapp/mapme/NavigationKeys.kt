package com.talapp.mapme

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Dashboard : NavKey
@Serializable data object Record : NavKey
@Serializable data class Detail(val walkId: Long) : NavKey
@Serializable data object AllWalksMap : NavKey

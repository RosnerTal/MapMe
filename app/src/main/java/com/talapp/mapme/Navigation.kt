package com.talapp.mapme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.talapp.mapme.ui.AllWalksMapScreen
import com.talapp.mapme.ui.DashboardScreen
import com.talapp.mapme.ui.DetailScreen
import com.talapp.mapme.ui.LoginScreen
import com.talapp.mapme.ui.RecordScreen
import com.talapp.mapme.ui.WalkViewModel

@Composable
fun MainNavigation(
  walkViewModel: WalkViewModel,
  onGoogleSignInClick: () -> Unit = {}
) {
  val currentUser by walkViewModel.currentUser.collectAsState()

  if (currentUser == null) {
    LoginScreen(
      viewModel = walkViewModel,
      onGoogleSignInClick = onGoogleSignInClick
    )
  } else {
    val backStack = rememberNavBackStack(Dashboard)

    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<Dashboard> {
            DashboardScreen(
              viewModel = walkViewModel,
              onStartWalkClick = { backStack.add(Record) },
              onWalkClick = { walkId -> backStack.add(Detail(walkId)) },
              onViewMapClick = { backStack.add(AllWalksMap) },
              onGoogleSignInClick = onGoogleSignInClick
            )
          }
          entry<Record> {
            RecordScreen(
              viewModel = walkViewModel,
              onBackClick = { backStack.removeLastOrNull() }
            )
          }
          entry<Detail> { key ->
            DetailScreen(
              walkId = key.walkId,
              viewModel = walkViewModel,
              onBackClick = { backStack.removeLastOrNull() }
            )
          }
          entry<AllWalksMap> {
            AllWalksMapScreen(
              viewModel = walkViewModel,
              onBackClick = { backStack.removeLastOrNull() }
            )
          }
        },
    )
  }
}


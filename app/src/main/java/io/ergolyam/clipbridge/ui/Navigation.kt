package io.ergolyam.clipbridge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.ergolyam.clipbridge.ui.screen.ClipBridgeScreen

@Composable
fun MainNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "clipbridge") {
        composable("clipbridge") { ClipBridgeScreen(modifier = Modifier.padding(16.dp)) }
    }
}


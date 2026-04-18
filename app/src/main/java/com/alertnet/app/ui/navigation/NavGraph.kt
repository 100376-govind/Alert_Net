package com.alertnet.app.ui.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alertnet.app.AlertNetApplication
import com.alertnet.app.ui.screen.ChatScreen
import com.alertnet.app.ui.screen.MeshStatsScreen
import com.alertnet.app.ui.screen.PeersScreen
import com.alertnet.app.ui.viewmodel.ChatViewModel
import com.alertnet.app.ui.viewmodel.PeersViewModel
import com.alertnet.app.ui.viewmodel.ViewModelFactory

/**
 * Navigation graph for AlertNet.
 *
 * Routes:
 * - "peers" → Peer discovery screen (home)
 * - "chat/{peerId}/{peerName}" → Chat with a specific peer
 * - "stats" → Mesh network statistics
 */
@Composable
fun NavGraph(app: AlertNetApplication) {
    val navController = rememberNavController()
    val factory = remember { ViewModelFactory(app) }

    NavHost(
        navController = navController,
        startDestination = "peers"
    ) {
        composable("peers") {
            val viewModel: PeersViewModel = viewModel(factory = factory)
            val peers by viewModel.peers.collectAsState()
            val meshStats by viewModel.meshStats.collectAsState()
            val isDiscovering by viewModel.isDiscovering.collectAsState()
            val discoveryState by viewModel.discoveryState.collectAsState()

            PeersScreen(
                peers = peers,
                meshStats = meshStats,
                isDiscovering = isDiscovering,
                discoveryState = discoveryState,
                onPeerClick = { peer ->
                    val name = peer.displayName.replace("/", "-")
                    navController.navigate("chat/${peer.deviceId}/$name")
                },
                onRefresh = { viewModel.refreshPeers() },
                onStatsClick = { navController.navigate("stats") }
            )
        }

        composable(
            route = "chat/{peerId}/{peerName}",
            arguments = listOf(
                navArgument("peerId") { type = NavType.StringType },
                navArgument("peerName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            val peerName = backStackEntry.arguments?.getString("peerName") ?: "Peer"
            val viewModel: ChatViewModel = viewModel(factory = factory)

            ChatScreen(
                peerId = peerId,
                peerName = peerName,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("stats") {
            val viewModel: PeersViewModel = viewModel(factory = factory)
            val meshStats by viewModel.meshStats.collectAsState()

            MeshStatsScreen(
                stats = meshStats,
                deviceId = app.deviceId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

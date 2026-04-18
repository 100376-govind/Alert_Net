package com.alertnet.app.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alertnet.app.mesh.MeshStats
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.ui.components.MeshStatusBar
import com.alertnet.app.ui.components.PeerCard
import com.alertnet.app.ui.theme.*

/**
 * Main screen showing discovered mesh peers and network status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    peers: List<MeshPeer>,
    meshStats: MeshStats,
    isDiscovering: Boolean,
    onPeerClick: (MeshPeer) -> Unit,
    onRefresh: () -> Unit,
    onStatsClick: () -> Unit
) {
    Scaffold(
        containerColor = MeshNavy,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MeshBlue,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AlertNet",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 22.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onStatsClick) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = "Mesh Stats",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MeshNavy
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Mesh status bar
            MeshStatusBar(stats = meshStats)

            Spacer(modifier = Modifier.height(8.dp))

            // Peer list with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = isDiscovering,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (peers.isEmpty()) {
                    // Empty state
                    EmptyPeersState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "Nearby Devices (${peers.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    bottom = 4.dp
                                )
                            )
                        }

                        items(
                            items = peers,
                            key = { it.deviceId }
                        ) { peer ->
                            PeerCard(
                                peer = peer,
                                onClick = { onPeerClick(peer) }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPeersState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Radar,
                contentDescription = null,
                tint = MeshBlue.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Scanning for peers...",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Make sure other AlertNet devices are nearby\nwith Bluetooth and WiFi turned on",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

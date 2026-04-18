package com.alertnet.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import com.alertnet.app.ui.theme.*

/**
 * Card component displaying a discovered mesh peer with:
 * - Signal strength indicator (for BLE peers)
 * - Transport type badge (BLE / WiFi / Both)
 * - Connection status dot
 * - Last-seen time
 */
@Composable
fun PeerCard(
    peer: MeshPeer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with connection status
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(MeshBlue, MeshBlueBright)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer.displayName.take(2).uppercase(),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Connection status dot
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (peer.isConnected) MeshGreen else StatusPending)
                        .align(Alignment.BottomEnd)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Peer info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = peer.deviceId.take(12) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Transport badge
                    TransportBadge(peer.transportType)

                    // Signal strength for BLE
                    peer.rssi?.let { rssi ->
                        Spacer(modifier = Modifier.width(8.dp))
                        SignalIndicator(rssi)
                    }
                }
            }

            // Last seen
            Column(horizontalAlignment = Alignment.End) {
                val elapsed = System.currentTimeMillis() - peer.lastSeen
                val timeText = when {
                    elapsed < 60_000 -> "Now"
                    elapsed < 3_600_000 -> "${elapsed / 60_000}m ago"
                    else -> "${elapsed / 3_600_000}h ago"
                }

                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(4.dp))

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open chat",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TransportBadge(type: TransportType) {
    val (color, text) = when (type) {
        TransportType.BLE -> BleBadge to "BLE"
        TransportType.WIFI_DIRECT -> WiFiBadge to "WiFi"
        TransportType.BOTH -> BothBadge to "Both"
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SignalIndicator(rssi: Int) {
    val bars = when {
        rssi > -50 -> 4
        rssi > -65 -> 3
        rssi > -80 -> 2
        else -> 1
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (i <= bars) MeshGreen else TextMuted.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

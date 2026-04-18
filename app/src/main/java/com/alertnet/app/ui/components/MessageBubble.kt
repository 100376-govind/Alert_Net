package com.alertnet.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alertnet.app.model.DeliveryStatus
import com.alertnet.app.model.MeshMessage
import com.alertnet.app.model.MessageType
import com.alertnet.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Chat bubble component with:
 * - Sent/received alignment and coloring
 * - Delivery status icon (⏳ pending, ✓ sent, ✓✓ delivered, ✕ failed)
 * - Hop count badge for relayed messages
 * - Timestamp
 * - File/image indicator for non-text messages
 */
@Composable
fun MessageBubble(
    message: MeshMessage,
    decryptedText: String,
    isSentByMe: Boolean,
    modifier: Modifier = Modifier
) {
    val alignment = if (isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (isSentByMe) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(
                    if (isSentByMe) {
                        Brush.linearGradient(listOf(BubbleSent, BubbleSentLight))
                    } else {
                        Brush.linearGradient(listOf(BubbleReceived, BubbleReceivedLight))
                    }
                )
                .padding(12.dp)
                .animateContentSize()
        ) {
            // File/image indicator
            if (message.type == MessageType.IMAGE || message.type == MessageType.FILE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (message.type == MessageType.IMAGE) {
                            Icons.Default.Image
                        } else {
                            Icons.Default.AttachFile
                        },
                        contentDescription = null,
                        tint = if (isSentByMe) MeshBlueGlow else MeshBlueBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = message.fileName ?: "Attachment",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSentByMe) MeshBlueGlow else MeshBlueBright,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Message text
            Text(
                text = decryptedText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Bottom row: timestamp + hop count + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isSentByMe) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timestamp
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp
                )

                // Hop count badge (only for relayed messages)
                if (message.hopCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MeshBlue.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "${message.hopCount} hop${if (message.hopCount > 1) "s" else ""}",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshBlueBright,
                            fontSize = 9.sp
                        )
                    }
                }

                // Delivery status (sent messages only)
                if (isSentByMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatusIcon(message.status)
                }
            }
        }
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val (icon, tint) = when (status) {
        DeliveryStatus.QUEUED -> Icons.Default.Schedule to StatusPending
        DeliveryStatus.SENDING -> Icons.Default.Sync to StatusSending
        DeliveryStatus.SENT -> Icons.Default.Check to StatusSent
        DeliveryStatus.DELIVERED -> Icons.Default.DoneAll to StatusDelivered
        DeliveryStatus.FAILED -> Icons.Default.ErrorOutline to StatusFailed
        DeliveryStatus.EXPIRED -> Icons.Default.TimerOff to StatusPending
    }

    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint,
        modifier = Modifier.size(14.dp)
    )
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

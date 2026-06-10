package org.openmmo.bytedex.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openmmo.bytedex.app.capture.CaptureStats
import org.openmmo.bytedex.proxy.netty.PacketSink

@Composable
fun CaptureScreen(
    pid: String,
    statsFlow: StateFlow<CaptureStats>,
    onDetach: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stats by statsFlow.collectAsState()
    var detaching by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CaptureHeader(pid = pid, stats = stats)
            BigCounters(stats = stats)
            ProtocolBreakdown(stats = stats)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionFooter(stats = stats)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (detaching) return@Button
                        detaching = true
                        scope.launch {
                            runCatching { onDetach() }
                            detaching = false
                        }
                    },
                    enabled = !detaching,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    if (detaching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Detaching...", fontSize = 13.sp)
                    } else {
                        Text("Detach", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureHeader(pid: String, stats: CaptureStats) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Capturing",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val revision = if (stats.gameVersion > 0) "revision ${stats.gameVersion}" else "revision unknown"
        Text(
            "PID $pid · $revision",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BigCounters(stats: CaptureStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard(
            label = "packets",
            value = formatCount(stats.totalPackets),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = "packets / sec",
            value = stats.packetsPerSecond.toString(),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = "bytes uploaded",
            value = formatBytes(stats.totalBytes),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ProtocolBreakdown(stats: CaptureStats) {
    val total = stats.totalPackets.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "BY PROTOCOL",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
        for (protocol in PacketSink.Protocol.entries) {
            val count = stats.perProtocol[protocol] ?: 0L
            val pct = (count.toFloat() / total.toFloat() * 100f)
            ProtocolRow(name = protocol.name, count = count, percent = pct)
        }
    }
}

@Composable
private fun ProtocolRow(name: String, count: Long, percent: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (percent > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            formatCount(count),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(60.dp),
        )
    }
}

@Composable
private fun SessionFooter(stats: CaptureStats, modifier: Modifier = Modifier) {
    val id = stats.sessionId ?: "(no session)"
    val ageSec = if (stats.startedAtEpochMs > 0) {
        ((System.currentTimeMillis() - stats.startedAtEpochMs) / 1_000L).coerceAtLeast(0L)
    } else 0L
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "session " + id.take(8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "up " + formatDuration(ageSec),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> "%.1fk".format(n / 1_000.0)
    else -> "%.1fM".format(n / 1_000_000.0)
}

private fun formatBytes(n: Long): String = when {
    n < 1_024 -> "$n B"
    n < 1_048_576 -> "%.1f kB".format(n / 1_024.0)
    n < 1_073_741_824 -> "%.1f MB".format(n / 1_048_576.0)
    else -> "%.2f GB".format(n / 1_073_741_824.0)
}

private fun formatDuration(seconds: Long): String {
    val s = seconds % 60
    val m = (seconds / 60) % 60
    val h = seconds / 3_600
    return when {
        h > 0 -> "%dh %02dm %02ds".format(h, m, s)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
    }
}

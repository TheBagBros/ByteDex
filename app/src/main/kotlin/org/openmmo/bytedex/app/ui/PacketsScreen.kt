package org.openmmo.bytedex.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import org.openmmo.bytedex.app.capture.RecordedPacket
import org.openmmo.bytedex.proxy.netty.PacketSink
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@Composable
fun PacketsScreen(packetsFlow: StateFlow<List<RecordedPacket>>) {
    val packets by packetsFlow.collectAsState()
    var protocolFilter: PacketSink.Protocol? by remember { mutableStateOf(null) }
    var directionFilter: PacketSink.Direction? by remember { mutableStateOf(null) }
    var idQuery by remember { mutableStateOf(TextFieldValue("")) }
    var selected: RecordedPacket? by remember { mutableStateOf(null) }

    val idFilter = idQuery.text.trim().toIntOrNull()
    val filtered = remember(packets, protocolFilter, directionFilter, idFilter) {
        packets.filter { p ->
            (protocolFilter == null || p.protocol == protocolFilter) &&
                (directionFilter == null || p.direction == directionFilter) &&
                (idFilter == null || p.packetId == idFilter)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recorded packets",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${filtered.size} / ${packets.size}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }

        val current = selected
        if (current == null) {
            Filters(
                protocolFilter = protocolFilter,
                onProtocol = { protocolFilter = it },
                directionFilter = directionFilter,
                onDirection = { directionFilter = it },
                idQuery = idQuery,
                onIdQuery = { idQuery = it },
            )

            PacketTable(
                packets = filtered,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxSize().weight(1f),
            )
        } else {
            PacketDetail(
                packet = current,
                onBack = { selected = null },
                modifier = Modifier.fillMaxSize().weight(1f),
            )
        }
    }
}

@Composable
private fun Filters(
    protocolFilter: PacketSink.Protocol?,
    onProtocol: (PacketSink.Protocol?) -> Unit,
    directionFilter: PacketSink.Direction?,
    onDirection: (PacketSink.Direction?) -> Unit,
    idQuery: TextFieldValue,
    onIdQuery: (TextFieldValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterRow(label = "PROTO") {
            FilterChip("All", protocolFilter == null) { onProtocol(null) }
            for (p in PacketSink.Protocol.entries) {
                FilterChip(p.name, protocolFilter == p) { onProtocol(p) }
            }
        }
        FilterRow(label = "DIR") {
            FilterChip("All", directionFilter == null) { onDirection(null) }
            for (d in PacketSink.Direction.entries) {
                FilterChip(d.name, directionFilter == d) { onDirection(d) }
            }
            Spacer(Modifier.weight(1f))
            IdFilterField(value = idQuery, onValueChange = onIdQuery)
        }
    }
}

@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        content()
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = fg, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun IdFilterField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) {
    Box(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(
                        "filter id…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun PacketTable(
    packets: List<RecordedPacket>,
    onSelect: (RecordedPacket) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        TableHeader()
        if (packets.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No packets captured yet - attach to a game on the Home tab.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(packets, key = { it.seq }) { p -> PacketRow(p, onClick = { onSelect(p) }) }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        HeaderCell("TIME", TIME_WEIGHT)
        HeaderCell("DIR", DIR_WEIGHT)
        HeaderCell("PROTO", PROTO_WEIGHT)
        HeaderCell("ID", ID_WEIGHT)
        HeaderCell("SIZE", SIZE_WEIGHT)
    }
}

@Composable
private fun PacketRow(p: RecordedPacket, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(TIME_FORMAT.format(Instant.ofEpochMilli(p.capturedAtEpochMs)), TIME_WEIGHT)
        Cell(p.direction.name, DIR_WEIGHT, color = directionColor(p.direction))
        Cell(p.protocol.name, PROTO_WEIGHT)
        Cell(p.packetId.toString(), ID_WEIGHT)
        Cell("${p.size} B", SIZE_WEIGHT)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun directionColor(direction: PacketSink.Direction): Color = when (direction) {
    PacketSink.Direction.C2S -> MaterialTheme.colorScheme.primary
    PacketSink.Direction.S2C -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun PacketDetail(packet: RecordedPacket, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lines = remember(packet.seq) { hexDump(packet.payload) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailHeader(packet = packet, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Empty payload",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(lines) { line -> HexLineRow(line) }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(packet: RecordedPacket, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilterChip("<- Back", selected = false, onClick = onBack)
        MetaText(TIME_FORMAT.format(Instant.ofEpochMilli(packet.capturedAtEpochMs)))
        MetaText(packet.direction.name, color = directionColor(packet.direction))
        MetaText(packet.protocol.name)
        MetaText("ID ${packet.packetId}")
        MetaText("${packet.size} B")
    }
}

@Composable
private fun MetaText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace)
}

@Composable
private fun HexLineRow(line: HexLine) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(line.offset, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line.hex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
        Text(line.ascii, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class HexLine(val offset: String, val hex: String, val ascii: String)

private const val HEX_BYTES_PER_LINE = 16

private fun hexDump(payload: ByteArray): List<HexLine> {
    if (payload.isEmpty()) return emptyList()
    val lines = ArrayList<HexLine>((payload.size + HEX_BYTES_PER_LINE - 1) / HEX_BYTES_PER_LINE)
    var offset = 0
    while (offset < payload.size) {
        val end = minOf(offset + HEX_BYTES_PER_LINE, payload.size)
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in offset until end) {
            val v = payload[i].toInt() and 0xFF
            if (i > offset) hex.append(' ')
            hex.append(v.toString(16).padStart(2, '0').uppercase())
            ascii.append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
        lines.add(
            HexLine(
                offset = offset.toString(16).padStart(4, '0').uppercase(),
                hex = hex.toString().padEnd(HEX_BYTES_PER_LINE * 3 - 1),
                ascii = ascii.toString(),
            ),
        )
        offset = end
    }
    return lines
}

private const val TIME_WEIGHT = 2.2f
private const val DIR_WEIGHT = 1f
private const val PROTO_WEIGHT = 1.4f
private const val ID_WEIGHT = 1f
private const val SIZE_WEIGHT = 1f

@file:OptIn(ExperimentalComposeUiApi::class)

package org.openmmo.bytedex.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.tools.attach.VirtualMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.openmmo.bytedex.app.agent.AgentAttacher
import org.openmmo.bytedex.app.auth.CurrentUser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val POLL_INTERVAL_MS = 2_000L

data class JvmEntry(
    val pid: String,
    val displayName: String,
)

sealed interface AttachStatus {
    data object Idle : AttachStatus
    data object Busy : AttachStatus
    data class Attached(val pid: String, val gameVersion: Long) : AttachStatus
    data class Error(val message: String) : AttachStatus
}

@Composable
fun AttachScreen(
    attach: suspend (pid: String) -> Result<AgentAttacher.Result>,
) {
    val scope = rememberCoroutineScope()
    var jvms: List<JvmEntry> by remember { mutableStateOf(emptyList()) }
    var selectedPid: String? by remember { mutableStateOf(null) }
    var refreshing by remember { mutableStateOf(true) }
    var status: AttachStatus by remember { mutableStateOf(AttachStatus.Idle) }

    LaunchedEffect(Unit) {
        while (true) {
            refreshing = true
            val list = withContext(Dispatchers.IO) { listAttachableJvms() }
            jvms = list
            if (selectedPid != null && list.none { it.pid == selectedPid }) {
                selectedPid = null
            }
            (status as? AttachStatus.Attached)?.let {
                if (list.none { row -> row.pid == it.pid }) status = AttachStatus.Idle
            }
            refreshing = false
            delay(POLL_INTERVAL_MS)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    "Attach to game",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Pick the Java process running PokeMMO.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                JvmList(
                    jvms = jvms,
                    selectedPid = selectedPid,
                    onSelect = { selectedPid = it },
                    modifier = Modifier.heightIn(min = 200.dp).fillMaxWidth().weight(1f, fill = false),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AttachStatusLine(
                        status = status,
                        refreshing = refreshing,
                        count = jvms.size,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val pid = selectedPid ?: return@Button
                            status = AttachStatus.Busy
                            scope.launch {
                                status = attach(pid).fold(
                                    onSuccess = { AttachStatus.Attached(pid, it.gameVersion) },
                                    onFailure = { AttachStatus.Error(it.message ?: "Attach failed") },
                                )
                            }
                        },
                        enabled = selectedPid != null && status !is AttachStatus.Busy &&
                            (status as? AttachStatus.Attached)?.pid != selectedPid,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        if (status is AttachStatus.Busy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Attaching...", fontSize = 13.sp)
                        } else {
                            Text("Attach", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachStatusLine(
    status: AttachStatus,
    refreshing: Boolean,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when (status) {
        is AttachStatus.Attached -> {
            val v = if (status.gameVersion > 0) " · revision ${status.gameVersion}" else ""
            "Attached to PID ${status.pid}$v" to MaterialTheme.colorScheme.primary
        }
        is AttachStatus.Error -> "Attach failed: ${status.message}" to MaterialTheme.colorScheme.error
        AttachStatus.Busy -> "Attaching..." to MaterialTheme.colorScheme.onSurfaceVariant
        AttachStatus.Idle -> {
            val label = if (refreshing && count == 0) "Scanning..."
                else "$count PokeMMO process${if (count == 1) "" else "es"}"
            label to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(text, fontSize = 11.sp, color = color, modifier = modifier)
}

@Composable
private fun JvmList(
    jvms: List<JvmEntry>,
    selectedPid: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (jvms.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No PokeMMO process detected - launch the game and wait.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(jvms, key = { it.pid }) { jvm ->
                    JvmRow(
                        jvm = jvm,
                        selected = jvm.pid == selectedPid,
                        onClick = { onSelect(jvm.pid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JvmRow(jvm: JvmEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "PID ${jvm.pid}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                jvm.displayName.ifBlank { "(no name)" },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Drops dead PIDs because hsperfdata can lag behind process exit.
private fun listAttachableJvms(): List<JvmEntry> {
    val selfPid = ProcessHandle.current().pid().toString()
    return VirtualMachine.list()
        .filter { it.id() != selfPid }
        .filter { isPokemmo(it.displayName().orEmpty()) }
        .filter { runCatching { ProcessHandle.of(it.id().toLong()).isPresent }.getOrDefault(true) }
        .map { JvmEntry(pid = it.id(), displayName = it.displayName().orEmpty()) }
        .sortedBy { it.pid }
}

private fun isPokemmo(name: String): Boolean {
    val n = name.lowercase()
    return "pokeemu" in n || "pokemmo" in n
}

private val avatarCache = mutableMapOf<String, ImageBitmap>()
private val httpClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
}

@Composable
private fun Avatar(user: CurrentUser, size: Dp) {
    val url = user.avatarUrl?.takeIf { it.isNotBlank() }
        ?: "https://github.com/${user.githubLogin}.png"
    var image: ImageBitmap? by remember(url) { mutableStateOf(avatarCache[url]) }

    LaunchedEffect(url) {
        if (image != null) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            runCatching {
                val res = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                if (res.statusCode() !in 200..299) return@runCatching null
                SkiaImage.makeFromEncoded(res.body()).toComposeImageBitmap()
            }.getOrNull()?.also { avatarCache[url] = it }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                user.githubLogin.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun IdentityHeader(user: CurrentUser, onSignOut: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(user = user, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "@${user.githubLogin}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onSignOut,
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("Sign out", fontSize = 12.sp)
        }
    }
}

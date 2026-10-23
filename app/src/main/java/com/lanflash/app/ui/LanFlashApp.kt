package com.lanflash.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lanflash.app.license.LicenseInfo
import com.lanflash.app.net.PairInfo
import com.lanflash.app.net.Proto
import com.lanflash.app.net.RecvState
import com.lanflash.app.net.SendProgress
import java.io.File

private val Bg = Color(0xFFF6F7FB)
private val Ink = Color(0xFF12141A)
private val Sub = Color(0xFF6B7280)
private val Primary = Color(0xFF2F6BFF)
private val Gradient = listOf(Color(0xFF2F6BFF), Color(0xFF7A5CFF))

private val LanFlashColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

@Composable
fun LanFlashTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LanFlashColors, content = content)
}

@Composable
fun LanFlashRoot(vm: AppViewModel) {
    LanFlashTheme {
        var screen by rememberSaveable { mutableStateOf("home") }
        val needActivation = vm.license.configured && vm.licenseInfo == null

        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                when {
                    needActivation -> ActivationScreen(vm, onBack = null)
                    screen == "send" -> SendScreen(vm, onBack = { screen = "home" })
                    screen == "receive" -> ReceiveScreen(vm, onBack = { screen = "home" })
                    screen == "activate" -> ActivationScreen(vm, onBack = { screen = "home" })
                    else -> HomeScreen(
                        vm = vm,
                        onSend = { screen = "send" },
                        onReceive = { screen = "receive" },
                        onActivate = { screen = "activate" },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    vm: AppViewModel,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onActivate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(Gradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("闪电传", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("局域网直连 · 多通道极速传输", fontSize = 12.sp, color = Sub)
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(Gradient))
                .padding(20.dp),
        ) {
            Column {
                Text("跑满你的局域网", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    "4 路并发 TCP + 块级校验，手机与电脑之间不经过云端。",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniChip("多线程")
                    MiniChip("CRC 校验")
                    MiniChip("扫码配对")
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        ActionCard(
            icon = Icons.Rounded.Send,
            title = "发送文件",
            desc = "扫描接收端二维码，选择文件后立即开始传输",
            onClick = onSend,
        )
        Spacer(Modifier.height(14.dp))
        ActionCard(
            icon = Icons.Rounded.Download,
            title = "接收文件",
            desc = "展示本机配对二维码，等待对方连接并接收",
            onClick = onReceive,
        )

        Spacer(Modifier.height(26.dp))

        LicenseChip(vm.licenseInfo, vm.license.configured, onActivate)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun MiniChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFEDF2FF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Primary)
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(3.dp))
                Text(desc, fontSize = 12.sp, color = Sub, lineHeight = 17.sp)
            }
            Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color(0xFFB9C0CC), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LicenseChip(info: LicenseInfo?, configured: Boolean, onActivate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (info != null) Icons.Rounded.VerifiedUser else Icons.Rounded.VpnKey,
            contentDescription = null,
            tint = if (info != null) Color(0xFF16A34A) else Sub,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            when {
                !configured -> "开发模式：未配置授权服务"
                info != null -> "已激活 · ${info.plan.uppercase()}"
                else -> "未激活"
            },
            fontSize = 12.sp,
            color = Sub,
            modifier = Modifier.weight(1f),
        )
        if (configured) {
            TextButton(onClick = onActivate) {
                Text(if (info != null) "更换激活码" else "去激活", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
        }
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
private fun SendScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var pair by remember { mutableStateOf<PairInfo?>(null) }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            val decoded = PairInfo.decode(contents)
            if (decoded == null) {
                scanError = "二维码不是有效的闪电传配对码"
            } else {
                pair = decoded
                scanError = null
                vm.resetSend()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            uris = picked
            vm.resetSend()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("发送文件", onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("接收端", fontSize = 13.sp, color = Sub)
                    Spacer(Modifier.height(8.dp))
                    if (pair == null) {
                        Text("还没有配对", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("扫描接收端的二维码")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(true)
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("扫描二维码")
                        }
                    } else {
                        Text(pair!!.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(Modifier.height(4.dp))
                        Text("${pair!!.ips.firstOrNull() ?: "-"} : ${pair!!.port}", fontSize = 12.sp, color = Sub)
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描接收端的二维码")) }) {
                            Text("重新扫描", fontSize = 12.sp)
                        }
                    }
                }
            }

            scanError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = Color(0xFFDC2626))
            }

            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("文件", fontSize = 13.sp, color = Sub, modifier = Modifier.weight(1f))
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text(if (uris.isEmpty()) "选择文件" else "重新选择", fontSize = 12.sp)
                        }
                    }
                    if (uris.isEmpty()) {
                        Text("尚未选择文件", fontSize = 14.sp, color = Sub)
                    } else {
                        Text("已选择 ${uris.size} 个文件", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        uris.take(8).forEach { uri ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, tint = Color(0xFF9AA3B2), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    QrUtil.displayName(ctx, uri),
                                    fontSize = 12.sp,
                                    color = Sub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (uris.size > 8) {
                            Text("… 还有 ${uris.size - 8} 个", fontSize = 12.sp, color = Sub)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            SendStatus(vm, pair, uris)

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SendStatus(vm: AppViewModel, pair: PairInfo?, uris: List<Uri>) {
    val progress: SendProgress = vm.sendProgress
    val canSend = pair != null && uris.isNotEmpty() && !vm.sending

    Button(
        onClick = { if (pair != null) vm.send(pair, uris) },
        enabled = canSend,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
    ) {
        if (vm.sending) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.size(10.dp))
            Text("正在发送 · ${Proto.formatSpeed(progress.speed)}")
        } else {
            Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("开始发送", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (vm.sending || (progress.totalBytes > 0 && !vm.sendDone)) {
        Spacer(Modifier.height(14.dp))
        val frac = if (progress.totalBytes > 0) {
            (progress.sentBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
        } else 0f
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Primary,
            trackColor = Color(0xFFE6EAF4),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                "${Proto.formatBytes(progress.sentBytes)} / ${Proto.formatBytes(progress.totalBytes)}",
                fontSize = 12.sp,
                color = Sub,
                modifier = Modifier.weight(1f),
            )
            Text(Proto.formatSpeed(progress.speed), fontSize = 12.sp, color = Sub)
        }
    }

    if (vm.sendDone) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A))
            Spacer(Modifier.size(8.dp))
            Text("发送完成，接收端已校验全部数据", fontSize = 13.sp, color = Color(0xFF16A34A))
        }
    }

    vm.sendError?.let { err ->
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(err, fontSize = 12.sp, color = Color(0xFFDC2626), lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ReceiveScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.receiver.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startReceive() }
    DisposableEffect(Unit) {
        onDispose { vm.stopReceive() }
    }

    val qr = remember(state.pairText) {
        if (state.pairText.isEmpty()) null else QrUtil.encode(state.pairText, 720)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("接收文件", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (state.waiting && !state.finished) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("用另一台设备扫码", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(Modifier.height(4.dp))
                        Text("发送端打开「发送文件」并扫描此码", fontSize = 12.sp, color = Sub)
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(10.dp),
                        ) {
                            if (qr != null) {
                                Image(
                                    bitmap = qr.asImageBitmap(),
                                    contentDescription = "配对二维码",
                                    modifier = Modifier.size(230.dp),
                                )
                            } else {
                                Box(modifier = Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "${state.ips.firstOrNull() ?: "未连接 Wi-Fi"} : ${state.port}",
                            fontSize = 12.sp,
                            color = Sub,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("请保持此页面打开 · 两台设备需在同一 Wi-Fi", fontSize = 11.sp, color = Color(0xFF9AA3B2))
                    }
                }
            } else {
                ReceiveProgress(state)
            }

            if (state.files.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            if (state.finished) "已保存到 Download/LanFlash" else "正在接收",
                            fontSize = 13.sp,
                            color = Sub,
                        )
                        Spacer(Modifier.height(8.dp))
                        state.files.forEach { f ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, tint = Color(0xFF9AA3B2), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(Proto.formatBytes(f.size), fontSize = 11.sp, color = Sub)
                                }
                                if (state.finished && f.path.isNotEmpty()) {
                                    IconButton(onClick = { shareFile(ctx, f.path) }) {
                                        Icon(Icons.Rounded.Share, contentDescription = "分享", tint = Primary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ReceiveProgress(state: RecvState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.finished) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                    contentDescription = null,
                    tint = if (state.finished) Color(0xFF16A34A) else Primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (state.finished) "传输完成" else "正在接收文件",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
            }
            Spacer(Modifier.height(14.dp))
            val frac = if (state.total > 0) (state.received.toFloat() / state.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { if (state.finished) 1f else frac },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = if (state.finished) Color(0xFF16A34A) else Primary,
                trackColor = Color(0xFFE6EAF4),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    "${Proto.formatBytes(state.received)} / ${Proto.formatBytes(state.total)}",
                    fontSize = 12.sp,
                    color = Sub,
                    modifier = Modifier.weight(1f),
                )
                if (!state.finished) {
                    Text(Proto.formatSpeed(state.speed), fontSize = 12.sp, color = Sub)
                }
            }
        }
    }
}

@Composable
private fun ActivationScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    var code by rememberSaveable { mutableStateOf("") }
    val configured = vm.license.configured

    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) {
            TopBar("激活", onBack)
        } else {
            Spacer(Modifier.height(60.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEDF2FF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.VpnKey, contentDescription = null, tint = Primary, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("激活闪电传", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "输入激活码后将绑定当前设备，激活后可长期使用",
                fontSize = 13.sp,
                color = Sub,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(26.dp))

            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(40) },
                label = { Text("激活码") },
                placeholder = { Text("LF-XXXX-XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.activate(code) },
                enabled = configured && !vm.activating && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                if (vm.activating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.size(10.dp))
                    Text("正在激活")
                } else {
                    Text("立即激活", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            vm.licenseError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 12.sp, color = Color(0xFFDC2626), lineHeight = 17.sp)
            }

            if (!configured) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "当前版本未配置授权服务地址，请联系开发者。",
                    fontSize = 12.sp,
                    color = Color(0xFFB45309),
                )
            }

            Spacer(Modifier.height(26.dp))
            Text("设备标识", fontSize = 11.sp, color = Color(0xFF9AA3B2))
            Text(vm.license.deviceId, fontSize = 11.sp, color = Color(0xFF9AA3B2))
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun shareFile(ctx: Context, path: String) {
    try {
        val file = File(path)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "打开或分享"))
    } catch (_: Exception) {
    }
}

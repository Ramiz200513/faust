package com.jarvis.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Экран «Устройства»: список привязанных ПК со статусом онлайн/офлайн,
 * выбор активного (на него уходят команды из чата) и привязка нового
 * по одноразовому коду или QR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit, vm: DevicesViewModel = viewModel()) {
    val devices by vm.devices.collectAsState()
    val pairing by vm.pairing.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Успешная привязка — закрываем диалог и готовим состояние для следующего раза.
    LaunchedEffect(pairing) {
        if (pairing is PairingUiState.Done) {
            showAddDialog = false
            vm.resetPairingState()
        }
    }

    if (showAddDialog) {
        AddDeviceDialog(
            pairing = pairing,
            onPairByCode = vm::pairByCode,
            onQrScanned = vm::pairByQr,
            onDismiss = { showAddDialog = false; vm.resetPairingState() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Устройства") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = { vm.refreshStatuses() }) { Text("⟳") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Text("+") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (devices.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Пока нет привязанных ПК.\nНажмите «+», чтобы привязать по коду или QR.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.device.id }) { ui ->
                    DeviceRow(
                        ui = ui,
                        onSelect = { vm.selectActive(ui.device.id) },
                        onRemove = { vm.remove(ui.device.id) },
                        onWake = {
                            ui.device.mac?.let { mac ->
                                scope.launch(Dispatchers.IO) { WakeOnLan.sendMagicPacket(mac) }
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Отправлено. Если телефон в той же Wi-Fi, а ПК подключён кабелем — проснётся за ~20 секунд"
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(ui: DeviceUi, onSelect: () -> Unit, onRemove: () -> Unit, onWake: () -> Unit) {
    // Карточка в стиле плашки «ПК: …» из чата; активный ПК — с розовой рамкой.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = if (ui.isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = ui.isActive, onClick = onSelect)

            Column(Modifier.weight(1f)) {
                Text(ui.device.name, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(ui.status)
                    Text(
                        text = if (ui.checking) "проверка…" else ui.status.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = ui.status.color(),
                    )
                }
                if (ui.device.mac == null) {
                    Text(
                        "привяжите заново по QR, чтобы включить пробуждение",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (ui.device.mac != null) {
                IconButton(onClick = onWake) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Разбудить по сети")
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Отвязать ПК")
            }
        }
    }
}

@Composable
private fun StatusDot(status: DeviceOnlineStatus) {
    Box(
        Modifier
            .size(10.dp)
            .background(status.color(), CircleShape)
    )
}

private fun DeviceOnlineStatus.label(): String = when (this) {
    DeviceOnlineStatus.ONLINE -> "в сети"
    DeviceOnlineStatus.OFFLINE -> "офлайн"
    DeviceOnlineStatus.UNAUTHORIZED -> "отвязано на релее"
    DeviceOnlineStatus.UNKNOWN -> "неизвестно"
}

@Composable
private fun DeviceOnlineStatus.color(): Color = when (this) {
    DeviceOnlineStatus.ONLINE -> onlineColor()
    DeviceOnlineStatus.OFFLINE -> MaterialTheme.colorScheme.error
    DeviceOnlineStatus.UNAUTHORIZED -> MaterialTheme.colorScheme.error
    DeviceOnlineStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Диалог привязки нового ПК: либо ввести одноразовый код руками, либо
 * отсканировать QR камерой. Разрешение на камеру запрашивается прямо тут —
 * при отказе просто остаётся рабочим ручной ввод, без краша.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceDialog(
    pairing: PairingUiState,
    onPairByCode: (String) -> Unit,
    onQrScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var deniedMessage by remember { mutableStateOf<String?>(null) }

    val launchScanner = rememberQrScanLauncher(
        onScanned = onQrScanned,
        onDenied = { deniedMessage = it },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Привязать ПК") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("одноразовый код") },
                    singleLine = true,
                    enabled = pairing != PairingUiState.InProgress,
                )
                OutlinedButton(
                    onClick = { onPairByCode(code) },
                    enabled = pairing != PairingUiState.InProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Привязать по коду") }

                Text("или", style = MaterialTheme.typography.labelSmall)

                OutlinedButton(
                    onClick = { launchScanner() },
                    enabled = pairing != PairingUiState.InProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📷 Сканировать QR") }

                when (pairing) {
                    is PairingUiState.InProgress -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("привязываем…", style = MaterialTheme.typography.bodySmall)
                    }
                    is PairingUiState.Error -> Text(
                        pairing.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {}
                }

                deniedMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

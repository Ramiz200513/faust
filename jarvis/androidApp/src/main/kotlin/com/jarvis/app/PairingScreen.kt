package com.jarvis.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Экран первого запуска (нет ни одного привязанного ПК — см. [MainActivity]).
 * Основной путь — скан QR, показанного в окне агента. Ручной ввод кода и
 * адреса релея скрыт под ссылкой на случай, если камера недоступна.
 *
 * Это первое, что человек видит в приложении, поэтому здесь не «форма», а
 * короткое приветствие: знак «Фауста», три шага и одна главная кнопка.
 */
@Composable
fun PairingScreen(vm: PairingViewModel = viewModel()) {
    val pairing by vm.pairing.collectAsState()
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val defaultRelayUrl by settings.relayUrl.collectAsState(initial = SettingsRepository.DEFAULT_URL)

    var showManual by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var relayUrl by remember(defaultRelayUrl) { mutableStateOf(defaultRelayUrl) }
    var deniedMessage by remember { mutableStateOf<String?>(null) }

    val launchScanner = rememberQrScanLauncher(
        onScanned = { raw -> vm.pairByQr(raw, defaultRelayUrl) },
        onDenied = { deniedMessage = it },
    )

    val inProgress = pairing is PairingUiState.InProgress

    // Успешно привязали — запоминаем адрес релея как "последний использованный",
    // чтобы при следующей привязке (другого ПК) он был подсказкой по умолчанию.
    LaunchedEffect(pairing) {
        if (pairing is PairingUiState.Done) settings.saveRelayUrl(relayUrl)
    }

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(150.dp).background(
                        Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent)),
                        CircleShape,
                    )
                )
                FaustLogo(88.dp, withTile = true, breathing = true)
            }
            Spacer(Modifier.height(16.dp))
            Text("Фауст", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Управляйте компьютером голосом — с телефона",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            // --- три шага ---
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Step(1, "Запустите «Фауст» на компьютере", "значок появится в трее у часов")
                    Step(2, "В меню значка выберите «Привязать телефон»", "откроется окно с QR-кодом")
                    Step(3, "Отсканируйте код этим телефоном", "привязка займёт пару секунд")
                }
            }
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { launchScanner() },
                enabled = !inProgress,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (inProgress) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Сканировать QR-код", style = MaterialTheme.typography.titleSmall)
                }
            }
            TextButton(onClick = { showManual = !showManual }, enabled = !inProgress) {
                Text(if (showManual) "Скрыть ручной ввод" else "Нет камеры? Ввести код вручную")
            }

            AnimatedVisibility(showManual) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.trim() },
                            label = { Text("Код привязки из окна агента") },
                            singleLine = true,
                            enabled = !inProgress,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = relayUrl,
                            onValueChange = { relayUrl = it },
                            label = { Text("Адрес сервера (ws://…)") },
                            singleLine = true,
                            enabled = !inProgress,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(
                            onClick = { vm.pairByCode(code, relayUrl) },
                            enabled = !inProgress && code.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("Привязать") }
                    }
                }
            }

            val error = pairing as? PairingUiState.Error
            val problem = error?.message ?: deniedMessage
            if (problem != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(problem, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

/** Шаг инструкции: номер в кружке, что сделать и что при этом увидите. */
@Composable
private fun Step(number: Int, title: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.jarvis.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Общая логика "нажали кнопку -> запросили камеру при необходимости ->
 * открыли сканер zxing -> распознали QR". Используется и на экране первого
 * запуска, и на экране "Устройства" — код сканирования не должен
 * дублироваться в двух местах.
 *
 * Возвращает функцию: вызвать её в onClick кнопки сканирования.
 */
@Composable
fun rememberQrScanLauncher(
    onScanned: (String) -> Unit,
    onDenied: (String) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) onScanned(contents)
        // contents == null — пользователь просто закрыл сканер, это не ошибка.
    }

    fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Наведите камеру на QR-код на экране ПК")
            setBeepEnabled(false)
        }
        scanLauncher.launch(options)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner() else onDenied("нет доступа к камере — введите код вручную")
    }

    return {
        val granted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}

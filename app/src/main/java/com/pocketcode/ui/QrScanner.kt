package com.pocketcode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/** Сканирует QR камерой через системное приложение и ML Kit (без CameraX). */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) {
            val image = InputImage.fromBitmap(bmp, 0)
            BarcodeScanning.getClient().process(image)
                .addOnSuccessListener { bars -> bars.firstOrNull()?.rawValue?.let(onResult) }
        }
    }
    return { take.launch(null) }
}

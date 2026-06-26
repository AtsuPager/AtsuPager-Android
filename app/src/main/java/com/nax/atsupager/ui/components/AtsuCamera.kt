package com.nax.atsupager.ui.components

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun AtsuCamera(
    onImageCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    
    val preview = Preview(context)
    val imageCapture = remember { ImageCapture.Builder().setFlashMode(flashMode).build() }
    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    
    LaunchedEffect(lensFacing, flashMode) {
        val cameraProvider = cameraProviderFuture.get()
        imageCapture.flashMode = flashMode
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview.preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e("AtsuCamera", "Binding failed", e)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { preview.previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Toolbar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            
            IconButton(onClick = {
                flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) 
                    ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            }) {
                Icon(
                    if (flashMode == ImageCapture.FLASH_MODE_ON) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    tint = if (flashMode == ImageCapture.FLASH_MODE_ON) Color.Yellow else Color.White,
                    contentDescription = "Flash"
                )
            }
        }

        // Bottom Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                },
                modifier = Modifier.size(48.dp).background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White)
            }

            // Кнопка затвора
            Button(
                onClick = {
                    takePhoto(imageCapture, ContextCompat.getMainExecutor(context), onImageCaptured)
                },
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) { }
            
            // Заглушка для симметрии
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

private fun takePhoto(
    imageCapture: ImageCapture,
    executor: Executor,
    onImageCaptured: (ByteArray) -> Unit
) {
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            onImageCaptured(bytes)
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e("AtsuCamera", "Capture failed", exception)
        }
    })
}

private class Preview(context: Context) {
    val previewView: PreviewView = PreviewView(context)
    val preview: androidx.camera.core.Preview = androidx.camera.core.Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
}

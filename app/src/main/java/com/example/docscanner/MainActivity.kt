package com.example.docscanner

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.docscanner.ui.theme.DocscannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.LinkedHashMap

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "Permission granted")
            // Permission granted, proceed with your task
        } else {
            Log.e(TAG, "Permission denied")
            Toast.makeText(this, "Storage permission is required to save images", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestManageExternalStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Log.i(TAG, "Manage external storage permission granted")
            // Permission granted, proceed with your task
        } else {
            Log.e(TAG, "Manage external storage permission denied")
            Toast.makeText(this, "Manage external storage permission is required to save images", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission is already granted
                Log.i(TAG, "Manage external storage permission already granted")
            } else {
                // Request permission
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                requestManageExternalStorageLauncher.launch(intent)
            }
        } else {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is already granted
                    Log.i(TAG, "Storage permission already granted")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                    // Provide an additional rationale to the user
                    Toast.makeText(this, "Storage permission is required to save images", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Directly request for required permissions, without explanation.
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(18)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)

        fun saveImage(uri: Uri, folder: File, order: Int) {
            val contentResolver: ContentResolver = contentResolver
            val fileName = "image_${order + 1}.jpg"
            val file = File(folder, fileName)

            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val outputStream = FileOutputStream(file)

                inputStream?.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(4 * 1024) // 4KB buffer
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }

                Log.i(TAG, "Image saved: ${file.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Error saving image: ${e.message}", e)
            }
        }

        fun saveImagesToFolder(imageUris: LinkedHashMap<Uri, Int>, folderName: String) {
            val folder = File(Environment.getExternalStorageDirectory(), folderName)
            if (!folder.exists()) {
                val folderCreated = folder.mkdir()
                if (folderCreated) {
                    Log.i(TAG, "Folder created: ${folder.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create folder: ${folder.absolutePath}")
                    return
                }
            } else {
                Log.i(TAG, "Folder already exists: ${folder.absolutePath}")
            }

            for ((uri, order) in imageUris) {
                saveImage(uri, folder, order)
            }
        }

        setContent {
            DocscannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var imageUris by remember {
                        mutableStateOf<LinkedHashMap<Uri, Int>>(LinkedHashMap())
                    }

                    val scannerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartIntentSenderForResult(),
                        onResult = { iit ->
                            if (iit.resultCode == RESULT_OK) {
                                val result = GmsDocumentScanningResult.fromActivityResultIntent(iit.data)

                                val scannedUris = result?.pages?.mapIndexed { index, page ->
                                    page.imageUri to index
                                }?.toMap(LinkedHashMap()) ?: LinkedHashMap()

                                result?.pdf?.let { pdf ->
                                    val fos = FileOutputStream(File(filesDir, "scan.pdf"))
                                    contentResolver.openInputStream(pdf.uri)?.use {
                                        it.copyTo(fos)
                                    }
                                }

                                imageUris = scannedUris
                                saveImagesToFolder(imageUris, "mlkit_images")
                            }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        for ((uri, _) in imageUris) {
                            AsyncImage(
                                model = uri, contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(onClick = {
                            checkAndRequestPermissions()
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                                Environment.isExternalStorageManager()) {
                                scanner.getStartScanIntent(this@MainActivity)
                                    .addOnSuccessListener {
                                        scannerLauncher.launch(
                                            IntentSenderRequest.Builder(it).build()
                                        )
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(
                                            applicationContext,
                                            it.message,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        }) {
                            Text(text = "Scan Images")
                        }
                    }
                }
            }
        }
    }
}


package com.example.docscanner

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.docscanner.ui.theme.DocscannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.LinkedHashMap

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "Permission granted")
            // Permission granted, proceed with your task
        } else {
            Log.e(TAG, "Permission denied")
            Toast.makeText(this, "Storage permission is required to save images", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestManageExternalStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Log.i(TAG, "Manage external storage permission granted")
            // Permission granted, proceed with your task
        } else {
            Log.e(TAG, "Manage external storage permission denied")
            Toast.makeText(this, "Manage external storage permission is required to save images", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission is already granted
                Log.i(TAG, "Manage external storage permission already granted")
            } else {
                // Request permission
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                requestManageExternalStorageLauncher.launch(intent)
            }
        } else {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is already granted
                    Log.i(TAG, "Storage permission already granted")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                    // Provide an additional rationale to the user
                    Toast.makeText(this, "Storage permission is required to save images", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Directly request for required permissions, without explanation.
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(18)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)

        fun getFileName(uri: Uri): String {
            var name = "image.jpg"
            val contentResolver: ContentResolver = contentResolver
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    }
                }
            }
            return name
        }

        fun saveImage(uri: Uri, folder: File, originalFileName: String) {
            val contentResolver: ContentResolver = contentResolver
            val file = File(folder, originalFileName)

            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val outputStream = FileOutputStream(file)

                inputStream?.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(4 * 1024) // 4KB buffer
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }

                Log.i(TAG, "Image saved: ${file.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Error saving image: ${e.message}", e)
            }
        }

        fun saveImagesToFolder(imageUris: LinkedHashMap<Uri, Int>, folderName: String) {
            val folder = File(Environment.getExternalStorageDirectory(), folderName)
            if (!folder.exists()) {
                val folderCreated = folder.mkdir()
                if (folderCreated) {
                    Log.i(TAG, "Folder created: ${folder.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create folder: ${folder.absolutePath}")
                    return
                }
            } else {
                Log.i(TAG, "Folder already exists: ${folder.absolutePath}")
            }

            for ((uri, _) in imageUris) {
                val originalFileName = getFileName(uri)
                saveImage(uri, folder, originalFileName)
            }
        }

        setContent {
            DocscannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var imageUris by remember {
                        mutableStateOf<LinkedHashMap<Uri, Int>>(LinkedHashMap())
                    }

                    val scannerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartIntentSenderForResult(),
                        onResult = { iit ->
                            if (iit.resultCode == RESULT_OK) {
                                val result = GmsDocumentScanningResult.fromActivityResultIntent(iit.data)

                                val scannedUris = result?.pages?.mapIndexed { index, page ->
                                    page.imageUri to index
                                }?.toMap(LinkedHashMap()) ?: LinkedHashMap()

                                result?.pdf?.let { pdf ->
                                    val fos = FileOutputStream(File(filesDir, "scan.pdf"))
                                    contentResolver.openInputStream(pdf.uri)?.use {
                                        it.copyTo(fos)
                                    }
                                }

                                imageUris = scannedUris
                                saveImagesToFolder(imageUris, "mlkit_images")
                            }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        for ((uri, _) in imageUris) {
                            AsyncImage(
                                model = uri, contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(onClick = {
                            checkAndRequestPermissions()
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                                Environment.isExternalStorageManager()) {
                                scanner.getStartScanIntent(this@MainActivity)
                                    .addOnSuccessListener {
                                        scannerLauncher.launch(
                                            IntentSenderRequest.Builder(it).build()
                                        )
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(
                                            applicationContext,
                                            it.message,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        }) {
                            Text(text = "Scan Images")
                        }
                    }
                }
            }
        }
    }
}


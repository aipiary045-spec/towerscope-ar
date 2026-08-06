package com.towerscope.ar

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.towerscope.ar.ui.ArScreen
import com.towerscope.ar.ui.theme.AccentYellow
import com.towerscope.ar.ui.theme.HudNavySolid
import com.towerscope.ar.ui.theme.TextPrimary
import com.towerscope.ar.ui.theme.TowerScopeTheme
import com.towerscope.ar.viewmodel.TowerScopeViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TowerScopeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TowerScopeTheme {
                TowerScopeApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun TowerScopeApp(viewModel: TowerScopeViewModel) {
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true
        val fineOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionsGranted = cameraOk && (fineOk || coarseOk)
        if (permissionsGranted) {
            viewModel.startLocationUpdates()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::loadTowersFromUri)
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && (fineGranted || coarseGranted)) {
            permissionsGranted = true
            viewModel.startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (!permissionsGranted) {
        PermissionGate(
            onRequest = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )
    } else {
        ArScreen(
            viewModel = viewModel,
            onOpenFilePicker = {
                filePickerLauncher.launch(
                    arrayOf(
                        "application/vnd.google-earth.kml+xml",
                        "application/vnd.google-earth.kmz",
                        "application/xml",
                        "text/xml",
                        "application/zip",
                        "*/*"
                    )
                )
            }
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudNavySolid)
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "TowerScope AR",
                color = AccentYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera and high-accuracy location are required to place tower markers outdoors.",
                color = TextPrimary,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color(0xFF0B1C2C)
                ),
                modifier = Modifier.height(56.dp)
            ) {
                Text("Grant permissions", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

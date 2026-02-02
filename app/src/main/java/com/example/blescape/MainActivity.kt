package com.example.blescape

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

// Data classes
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int
)

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

data class Orientation(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
)

// Dark theme colors
private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkCard = Color(0xFF2D2D2D)
private val PrimaryBlue = Color(0xFF90CAF9)
private val SecondaryTeal = Color(0xFF80CBC4)
private val AccentOrange = Color(0xFFFFB74D)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var wifiManager: WifiManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scanInterval = 10000L // 10 seconds

    // State holders
    private var wifiNetworks = mutableStateOf<List<WifiNetwork>>(emptyList())
    private var bleDevices = mutableStateOf<List<BleDevice>>(emptyList())
    private var orientation = mutableStateOf(Orientation(0f, 0f, 0f))
    private var permissionsGranted = mutableStateOf(false)
    private var scanCountdown = mutableStateOf(10)
    private var isScanning = mutableStateOf(false)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted.value = permissions.values.all { it }
        if (permissionsGranted.value) {
            startScanning()
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val results = wifiManager.scanResults
                wifiNetworks.value = results.map { result ->
                    WifiNetwork(
                        ssid = result.SSID.ifEmpty { "<Hidden>" },
                        bssid = result.BSSID,
                        rssi = result.level,
                        frequency = result.frequency
                    )
                }.sortedByDescending { it.rssi }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = BleDevice(
                name = result.device.name,
                address = result.device.address,
                rssi = result.rssi
            )
            val currentList = bleDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                currentList[existingIndex] = device
            } else {
                currentList.add(device)
            }
            bleDevices.value = currentList.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            // Handle scan failure silently
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize managers
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        setContent {
            BleScapeApp()
        }

        // Check permissions
        checkAndRequestPermissions()
    }

    @Composable
    fun BleScapeApp() {
        val wifi by wifiNetworks
        val ble by bleDevices
        val orient by orientation
        val hasPermissions by permissionsGranted
        val countdown by scanCountdown
        val scanning by isScanning

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = DarkBackground,
                surface = DarkSurface,
                primary = PrimaryBlue,
                secondary = SecondaryTeal,
                tertiary = AccentOrange
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = DarkBackground
            ) {
                if (!hasPermissions) {
                    PermissionRequest()
                } else {
                    MainContent(
                        wifiNetworks = wifi,
                        bleDevices = ble,
                        orientation = orient,
                        countdown = countdown,
                        isScanning = scanning
                    )
                }
            }
        }
    }

    @Composable
    fun PermissionRequest() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Permissions Required",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This app needs location, WiFi, and Bluetooth permissions to scan nearby networks and devices.",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text("Grant Permissions", color = Color.Black)
            }
        }
    }

    @Composable
    fun MainContent(
        wifiNetworks: List<WifiNetwork>,
        bleDevices: List<BleDevice>,
        orientation: Orientation,
        countdown: Int,
        isScanning: Boolean
    ) {
        var wifiExpanded by remember { mutableStateOf(true) }
        var bleExpanded by remember { mutableStateOf(true) }
        var orientationExpanded by remember { mutableStateOf(true) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Blescape",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Scan status indicator
            item {
                ScanStatusBar(countdown = countdown, isScanning = isScanning)
            }

            // Orientation Panel
            item {
                ExpandablePanel(
                    title = "Orientation",
                    subtitle = "Real-time sensor data",
                    expanded = orientationExpanded,
                    onToggle = { orientationExpanded = !orientationExpanded },
                    accentColor = AccentOrange
                ) {
                    OrientationContent(orientation)
                }
            }

            // WiFi Panel
            item {
                ExpandablePanel(
                    title = "WiFi Networks",
                    subtitle = "${wifiNetworks.size} found",
                    expanded = wifiExpanded,
                    onToggle = { wifiExpanded = !wifiExpanded },
                    accentColor = PrimaryBlue
                ) {
                    WifiContent(wifiNetworks)
                }
            }

            // BLE Panel
            item {
                ExpandablePanel(
                    title = "Bluetooth LE Devices",
                    subtitle = "${bleDevices.size} found",
                    expanded = bleExpanded,
                    onToggle = { bleExpanded = !bleExpanded },
                    accentColor = SecondaryTeal
                ) {
                    BleContent(bleDevices)
                }
            }
        }
    }

    @Composable
    fun ScanStatusBar(countdown: Int, isScanning: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkCard)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isScanning) "Scanning..." else "Next scan in ${countdown}s",
                fontSize = 14.sp,
                color = TextSecondary
            )
            LinearProgressIndicator(
                progress = if (isScanning) 1f else (10 - countdown) / 10f,
                modifier = Modifier
                    .width(100.dp)
                    .height(4.dp),
                color = if (isScanning) AccentOrange else PrimaryBlue,
                trackColor = DarkSurface
            )
        }
    }

    @Composable
    fun ExpandablePanel(
        title: String,
        subtitle: String,
        expanded: Boolean,
        onToggle: () -> Unit,
        accentColor: Color,
        content: @Composable () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Text(
                    text = if (expanded) "▼" else "▶",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    fun OrientationContent(orientation: Orientation) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrientationRow("Azimuth (Compass)", orientation.azimuth, "°", 0f, 360f)
            OrientationRow("Pitch (Front/Back)", orientation.pitch, "°", -180f, 180f)
            OrientationRow("Roll (Left/Right)", orientation.roll, "°", -90f, 90f)
        }
    }

    @Composable
    fun OrientationRow(label: String, value: Float, unit: String, min: Float, max: Float) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, fontSize = 14.sp, color = TextSecondary)
                Text(
                    text = "${value.roundToInt()}$unit",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
            LinearProgressIndicator(
                progress = ((value - min) / (max - min)).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(top = 4.dp),
                color = AccentOrange,
                trackColor = DarkSurface
            )
        }
    }

    @Composable
    fun WifiContent(networks: List<WifiNetwork>) {
        if (networks.isEmpty()) {
            Text(
                text = "No WiFi networks found. Scanning...",
                fontSize = 14.sp,
                color = TextSecondary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                networks.take(10).forEach { network ->
                    WifiNetworkRow(network)
                }
                if (networks.size > 10) {
                    Text(
                        text = "...and ${networks.size - 10} more",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    @Composable
    fun WifiNetworkRow(network: WifiNetwork) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurface)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = network.ssid,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = "${if (network.frequency < 3000) "2.4" else "5"} GHz • ${network.bssid}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                SignalStrengthBars(rssi = network.rssi)
                Text(
                    text = "${network.rssi} dBm",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }

    @Composable
    fun BleContent(devices: List<BleDevice>) {
        if (devices.isEmpty()) {
            Text(
                text = "No Bluetooth LE devices found. Scanning...",
                fontSize = 14.sp,
                color = TextSecondary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.take(10).forEach { device ->
                    BleDeviceRow(device)
                }
                if (devices.size > 10) {
                    Text(
                        text = "...and ${devices.size - 10} more",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    @Composable
    fun BleDeviceRow(device: BleDevice) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurface)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "<Unknown>",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = device.address,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                SignalStrengthBars(rssi = device.rssi)
                Text(
                    text = "${device.rssi} dBm",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }

    @Composable
    fun SignalStrengthBars(rssi: Int) {
        val bars = when {
            rssi >= -50 -> 4
            rssi >= -60 -> 3
            rssi >= -70 -> 2
            rssi >= -80 -> 1
            else -> 0
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (i in 1..4) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((4 + i * 3).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (i <= bars) PrimaryBlue else DarkSurface
                        )
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            permissionsGranted.value = true
            startScanning()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        // Register WiFi receiver
        registerReceiver(
            wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        // Start orientation sensing
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Start periodic scanning
        startPeriodicScan()
    }

    @SuppressLint("MissingPermission")
    private fun startPeriodicScan() {
        val scanRunnable = object : Runnable {
            override fun run() {
                performScan()
                handler.postDelayed(this, scanInterval)
            }
        }

        // Start countdown timer
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (scanCountdown.value > 0) {
                    scanCountdown.value--
                }
                handler.postDelayed(this, 1000)
            }
        }

        handler.post(scanRunnable)
        handler.post(countdownRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun performScan() {
        isScanning.value = true
        scanCountdown.value = 10

        // WiFi scan
        try {
            wifiManager.startScan()
        } catch (e: Exception) {
            // Handle scan failure
        }

        // BLE scan - clear old results and start fresh
        bleDevices.value = emptyList()
        try {
            bleScanner?.stopScan(bleScanCallback)
            bleScanner?.startScan(bleScanCallback)

            // Stop BLE scan after 5 seconds
            handler.postDelayed({
                try {
                    bleScanner?.stopScan(bleScanCallback)
                } catch (e: Exception) {
                    // Ignore
                }
                isScanning.value = false
            }, 5000)
        } catch (e: Exception) {
            isScanning.value = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            orientation.value = Orientation(
                azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat().let {
                    if (it < 0) it + 360 else it
                },
                pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
                roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted.value) {
            rotationSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(wifiScanReceiver)
            bleScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            // Ignore
        }
    }
}

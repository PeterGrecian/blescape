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
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
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

// OUI prefix → manufacturer (partial; covers common consumer devices).
// Many BLE devices (especially iOS) use random/private addresses that rotate,
// so lookup will only match public addresses.
private val OUI_MANUFACTURERS = mapOf(
    // Apple
    "00:1A:80" to "Apple", "28:6C:07" to "Apple", "3C:15:C2" to "Apple",
    "44:D9:E7" to "Apple", "48:D7:05" to "Apple", "58:E2:88" to "Apple",
    "6C:19:C0" to "Apple", "70:3A:CB" to "Apple", "78:E7:D1" to "Apple",
    "A4:58:40" to "Apple", "B8:78:8C" to "Apple", "D0:A8:39" to "Apple",
    "F8:E4:FB" to "Apple",
    // Samsung
    "08:FE:81" to "Samsung", "18:47:2B" to "Samsung", "20:64:32" to "Samsung",
    "28:5F:96" to "Samsung", "44:24:EC" to "Samsung", "68:27:37" to "Samsung",
    "84:16:69" to "Samsung", "B4:EF:26" to "Samsung",
    // Google
    "58:CB:31" to "Google", "60:F8:1D" to "Google",
    // Xiaomi
    "18:FE:88" to "Xiaomi", "34:86:5A" to "Xiaomi",
    // Huawei
    "4C:11:CF" to "Huawei", "D8:E8:44" to "Huawei",
    // Cisco
    "00:00:0C" to "Cisco", "00:13:07" to "Cisco",
    // TP-Link
    "B0:95:74" to "TP-Link", "AC:84:31" to "TP-Link", "CC:30:60" to "TP-Link",
    // ASUS
    "CC:06:E2" to "ASUS", "D8:F3:B1" to "ASUS",
    // D-Link
    "44:E9:43" to "D-Link",
    // Linksys
    "00:23:68" to "Linksys",
    // Intel
    "8C:EC:4B" to "Intel",
    // Qualcomm
    "AC:81:DA" to "Qualcomm",
    // Sony
    "00:1A:7D" to "Sony",
    // LG
    "B8:18:8D" to "LG"
)

private fun manufacturerFromMac(mac: String): String? =
    OUI_MANUFACTURERS[mac.take(8).uppercase()]

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
                isScanning.value = false
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Blescape",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 2.dp)
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
                .clip(RoundedCornerShape(6.dp))
                .background(DarkCard)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isScanning) "WiFi scanning..." else "WiFi scan in ${countdown}s",
                fontSize = 12.sp,
                color = TextSecondary
            )
            LinearProgressIndicator(
                progress = if (isScanning) 1f else (10 - countdown) / 10f,
                modifier = Modifier
                    .width(80.dp)
                    .height(3.dp),
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                Text(
                    text = if (expanded) "▼" else "▶",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    fun OrientationContent(orientation: Orientation) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OrientationValue("Az", orientation.azimuth)
            OrientationValue("Pitch", orientation.pitch)
            OrientationValue("Roll", orientation.roll)
        }
    }

    @Composable
    fun OrientationValue(label: String, value: Float) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 11.sp, color = TextSecondary)
            Text(
                text = "${value.roundToInt()}°",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = AccentOrange
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                networks.take(4).forEach { network ->
                    WifiNetworkRow(network)
                }
                if (networks.size > 4) {
                    Text(
                        text = "...and ${networks.size - 4} more",
                        fontSize = 11.sp,
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
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = network.ssid,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = buildString {
                        manufacturerFromMac(network.bssid)?.let { append("$it • ") }
                        append(if (network.frequency < 3000) "2.4" else "5")
                        append(" GHz")
                    },
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "${network.rssi}", fontSize = 10.sp, color = TextSecondary)
                SignalStrengthBars(rssi = network.rssi)
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                devices.take(4).forEach { device ->
                    BleDeviceRow(device)
                }
                if (devices.size > 4) {
                    Text(
                        text = "...and ${devices.size - 4} more",
                        fontSize = 11.sp,
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
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "<Unknown>",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = manufacturerFromMac(device.address) ?: device.address,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "${device.rssi}", fontSize = 10.sp, color = TextSecondary)
                SignalStrengthBars(rssi = device.rssi)
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

        // Start continuous BLE scan (updates fire every 1-3s per device)
        try {
            bleScanner?.startScan(bleScanCallback)
        } catch (e: Exception) { }

        // Start periodic WiFi scan + countdown
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

        // WiFi scan (results arrive via wifiScanReceiver which clears isScanning)
        try {
            wifiManager.startScan()
        } catch (e: Exception) {
            isScanning.value = false
        }

        // Prune BLE devices not seen in the last 20 seconds
        val cutoff = System.currentTimeMillis() - 20_000L
        bleDevices.value = bleDevices.value.filter { it.lastSeen > cutoff }
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
            try { bleScanner?.startScan(bleScanCallback) } catch (e: Exception) { }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        try { bleScanner?.stopScan(bleScanCallback) } catch (e: Exception) { }
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

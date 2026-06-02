package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.fragments.MainSettingsFragment
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import android.graphics.drawable.ColorDrawable
import android.graphics.Color

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var trafficJob: kotlinx.coroutines.Job? = null
    private var glowAnimator: ObjectAnimator? = null
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var initialRxBytes = 0L
    private var initialTxBytes = 0L
    private var connectionStartTime: Long = 0L

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST -> {
                    if (connectionStartTime == 0L) connectionStartTime = System.currentTimeMillis()
                    updateStatus()
                }
                STOPPED_BROADCAST -> {
                    connectionStartTime = 0L
                    updateStatus()
                }

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize OSMDroid configuration
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup MapView
        binding.mapBackground.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapBackground.setMultiTouchControls(false) // Naka-lock
        binding.mapBackground.controller.setZoom(4.0)
        binding.mapBackground.controller.setCenter(GeoPoint(0.0, 0.0))
        binding.mapBackground.setOnTouchListener { _, _ -> true } // Disable touch events to lock the view

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Set default Smart Unli Data Bypass command permanently
        sharedPrefs.edit().putString("byedpi_cmd", "-n opensignal.com -f -1 -t 4").apply()

        // Handle Bottom Navigation
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> {
                    // Already here
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    // Ensure the Connect tab is selected when returning
                    binding.bottomNavigation.selectedItemId = R.id.nav_connect
                    false
                }
                else -> false
            }
        }

        // UI logic for switches has been moved to SettingsActivity

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        binding.powerButton.setOnClickListener {
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> {
                    stop()
                    try {
                        val shopeeIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://s.shopee.ph/9KcHLNKOwn"))
                        startActivity(shopeeIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch Shopee link", e)
                    }
                }
            }
        }

        val theme = getPreferences()
            .getString("app_theme", null)
        MainSettingsFragment.setTheme(theme ?: "system")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_settings -> {
                if (status == AppStatus.Halted) {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT)
                        .show()
                }
                true
            }

            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "byedpi.log")
                    }

                logsRegister.launch(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = getString(R.string.proxy_address, proxyIp, proxyPort)

        when (status) {
            AppStatus.Halted -> {
                binding.powerGlow.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.gray_glow))
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.gray_accent))
                binding.connectionTimeText.visibility = android.view.View.GONE
                binding.currentIpText.visibility = android.view.View.GONE
                binding.pingText.visibility = android.view.View.GONE
                binding.tetherInfoText.visibility = android.view.View.GONE
                
                when (preferences.mode()) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                    }
                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                    }
                }
                binding.powerButton.isEnabled = true
                stopTrafficMonitor()
            }

            AppStatus.Running -> {
                binding.powerGlow.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.teal_glow))
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.teal_accent))
                binding.connectionTimeText.visibility = android.view.View.VISIBLE

                if (binding.currentIpText.visibility == android.view.View.GONE) {
                    binding.currentIpText.visibility = android.view.View.VISIBLE
                    binding.currentIpText.text = "IP: Fetching..."
                    fetchPublicIp()
                }
                
                if (preferences.getBoolean("enable_hotspot_share", false)) {
                    binding.tetherInfoText.visibility = android.view.View.VISIBLE
                    // Set default hotspot IP, usually 192.168.43.1
                    binding.tetherInfoText.text = "Tethering Active\nProxy: 192.168.43.1 Port: 1080"
                } else {
                    binding.tetherInfoText.visibility = android.view.View.GONE
                }
                
                binding.pingText.visibility = android.view.View.VISIBLE

                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                    }
                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                    }
                }
                binding.powerButton.isEnabled = true
                startTrafficMonitor()
            }
        }
    }

    private fun startTrafficMonitor() {
        if (trafficJob?.isActive == true) return
        
        lastRxBytes = android.net.TrafficStats.getTotalRxBytes()
        lastTxBytes = android.net.TrafficStats.getTotalTxBytes()
        initialRxBytes = lastRxBytes
        initialTxBytes = lastTxBytes
        binding.trafficGraph.clear()

        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.15f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.15f)
        val alpha = PropertyValuesHolder.ofFloat("alpha", 0.6f, 1.0f)
        glowAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.powerGlow, scaleX, scaleY, alpha).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        trafficJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                val rx = android.net.TrafficStats.getTotalRxBytes()
                val tx = android.net.TrafficStats.getTotalTxBytes()
                
                val rxDiff = rx - lastRxBytes
                val txDiff = tx - lastTxBytes
                
                val totalUsed = (rx - initialRxBytes) + (tx - initialTxBytes)
                val totalMb = totalUsed / (1024f * 1024f)
                binding.totalDataText.text = String.format("This Session: %.2f MB", totalMb)
                
                lastRxBytes = rx
                lastTxBytes = tx
                
                val downloadSpeedMbps = (rxDiff / 1024f / 1024f).takeIf { it > 0.01 } ?: 0f
                val uploadSpeedMbps = (txDiff / 1024f / 1024f).takeIf { it > 0.01 } ?: 0f
                
                binding.trafficDownloadText.text = if (rxDiff > 1024 * 1024) String.format("↓ DOWNLOAD: %.1f Mbps", downloadSpeedMbps) else String.format("↓ DOWNLOAD: %.1f Kbps", rxDiff / 1024f)
                binding.trafficUploadText.text = if (txDiff > 1024 * 1024) String.format("↑ UPLOAD: %.1f Mbps", uploadSpeedMbps) else String.format("↑ UPLOAD: %.1f Kbps", txDiff / 1024f)
                binding.trafficGraph.addDataPoint((rxDiff + txDiff) / 1024f)

                if (connectionStartTime > 0L) {
                    val elapsedSecs = (System.currentTimeMillis() - connectionStartTime) / 1000
                    val h = elapsedSecs / 3600
                    val m = (elapsedSecs % 3600) / 60
                    val s = elapsedSecs % 60
                    binding.connectionTimeText.text = String.format("%02d:%02d:%02d", h, m, s)
                }

                // Ping check every ~2 seconds (we are in a 1-second loop, so we can just do it on even seconds)
                if ((System.currentTimeMillis() / 1000) % 2 == 0L) {
                    launch(Dispatchers.IO) {
                        try {
                            val startPing = System.currentTimeMillis()
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress("1.1.1.1", 53), 2000)
                            socket.close()
                            val endPing = System.currentTimeMillis()
                            val pingMs = endPing - startPing
                            withContext(Dispatchers.Main) {
                                binding.pingText.text = "Ping: ${pingMs}ms"
                                binding.pingText.setTextColor(if (pingMs < 100) ContextCompat.getColor(this@MainActivity, R.color.teal_accent) else ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light))
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                binding.pingText.text = "Ping: Timeout"
                                binding.pingText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = null
        
        glowAnimator?.cancel()
        glowAnimator = null
        binding.powerGlow.scaleX = 1.0f
        binding.powerGlow.scaleY = 1.0f
        binding.powerGlow.alpha = 1.0f
        
        // Hide and stop the map dot pulse when disconnected
        binding.dotContainer.visibility = android.view.View.GONE
        binding.dotGlow.clearAnimation()
        
        binding.trafficDownloadText.text = "↓ DOWNLOAD: 0.0 Kbps"
        binding.trafficUploadText.text = "↑ UPLOAD: 0.0 Kbps"
        binding.totalDataText.text = "This Session: 0.00 MB"
        binding.trafficGraph.clear()
    }

    private fun fetchPublicIp() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Fetch IP and Geo info using HTTPS and ipwhois
                val response = URL("https://ipwho.is/").readText()
                val json = org.json.JSONObject(response)
                
                val ip = json.optString("ip", "Unknown IP")
                val country = json.optString("country", "Unknown")
                val lat = json.optDouble("latitude", 0.0)
                val lon = json.optDouble("longitude", 0.0)
                
                withContext(Dispatchers.Main) {
                    binding.currentIpText.text = ip
                    binding.currentLocationText.text = country
                    
                    // Update OSMDroid map center and show centered pulsing dot
                    if (lat != 0.0 || lon != 0.0) {
                        val geoPoint = GeoPoint(lat, lon)
                        binding.mapBackground.controller.animateTo(geoPoint, 4.0, 1500L)
                        
                        // We use a fixed pulsing dot overlaid on the map's center
                        binding.dotContainer.visibility = android.view.View.VISIBLE
                        ObjectAnimator.ofPropertyValuesHolder(
                            binding.dotGlow,
                            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 2.5f),
                            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 2.5f),
                            PropertyValuesHolder.ofFloat("alpha", 0.5f, 0.0f)
                        ).apply {
                            duration = 2000
                            repeatCount = ValueAnimator.INFINITE
                            start()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.currentIpText.text = "Unavailable"
                    binding.currentLocationText.text = "Hidden"
                }
            }
        }
    }
}
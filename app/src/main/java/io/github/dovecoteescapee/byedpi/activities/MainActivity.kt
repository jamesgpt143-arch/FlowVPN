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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Setup Payload Dropdown
        val payloadOptions = arrayOf("Smart Unli Data Bypass", "Tiktok Bypass")
        val payloadCommands = arrayOf("-n opensignal.com -f -1 -t 4", "-n m.tiktok.com -f -1 -t 4")

        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item, payloadOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.payloadSpinner.adapter = adapter

        // Set initial selection
        val currentCmd = sharedPrefs.getString("byedpi_cmd", payloadCommands[0])
        val selectedIndex = payloadCommands.indexOf(currentCmd).takeIf { it >= 0 } ?: 0
        binding.payloadSpinner.setSelection(selectedIndex)

        // Listen for selection changes
        binding.payloadSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedCmd = payloadCommands[position]
                if (sharedPrefs.getString("byedpi_cmd", "") != selectedCmd) {
                    sharedPrefs.edit().putString("byedpi_cmd", selectedCmd).apply()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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
                AppStatus.Running -> stop()
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
                binding.totalDataText.text = String.format("Total: %.2f MB", totalMb)
                
                lastRxBytes = rx
                lastTxBytes = tx
                
                val speedBytes = rxDiff + txDiff
                val speedKbps = speedBytes / 1024f
                
                binding.trafficSpeedText.text = String.format("%.2f Kbps", speedKbps)
                binding.trafficGraph.addDataPoint(speedKbps)

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
        
        binding.trafficSpeedText.text = "0.00 Kbps"
        binding.totalDataText.text = "Total: 0.00 MB"
        binding.trafficGraph.clear()
    }

    private fun fetchPublicIp() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ip = URL("https://api.ipify.org").readText()
                withContext(Dispatchers.Main) {
                    binding.currentIpText.text = "IP: $ip"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.currentIpText.text = "IP: Unavailable"
                }
            }
        }
    }
}
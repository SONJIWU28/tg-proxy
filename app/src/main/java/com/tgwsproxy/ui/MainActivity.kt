package com.tgwsproxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.tgwsproxy.R
import com.tgwsproxy.databinding.ActivityMainBinding
import com.tgwsproxy.vpn.TgVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private val handler = Handler(Looper.getMainLooper())

    // VPN permission request
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            TgVpnService.start(this)
            startStatePoller()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Notification permission request (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this,
                "Уведомления отключены. Прокси будет работать, но без статуса в шторке.",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupUI()
        observeViewModel()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRunningState()
        startStatePoller()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener {
            if (TgVpnService.isRunning) {
                TgVpnService.stop(this)
                handler.postDelayed({ viewModel.refreshRunningState() }, 500)
            } else {
                startVpnWithPermission()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.isRunning.observe(this) { running ->
            binding.btnToggle.text = if (running) "ОСТАНОВИТЬ" else "ЗАПУСТИТЬ"
            binding.btnToggle.setBackgroundColor(
                ContextCompat.getColor(this,
                    if (running) R.color.stop_red else R.color.tg_blue)
            )
            binding.tvStatus.text = if (running) "Активен" else "Отключён"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this,
                    if (running) R.color.status_active else R.color.status_inactive)
            )
            binding.ivStatusDot.setColorFilter(
                ContextCompat.getColor(this,
                    if (running) R.color.status_active else R.color.status_inactive)
            )
        }

        viewModel.trafficUp.observe(this) { binding.tvTrafficUp.text = it }
        viewModel.trafficDown.observe(this) { binding.tvTrafficDown.text = it }
        viewModel.trafficTotal.observe(this) { binding.tvTrafficTotal.text = it }
        viewModel.uptimeText.observe(this) { binding.tvUptime.text = it }
        viewModel.connectionsActive.observe(this) { binding.tvConnectionsActive.text = it }
        viewModel.connectionsTotal.observe(this) { binding.tvConnectionsTotal.text = it }
        viewModel.connectionsWs.observe(this) { binding.tvConnectionsWs.text = it }
        viewModel.connectionsTcp.observe(this) { binding.tvConnectionsTcp.text = it }
    }

    private fun startVpnWithPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            TgVpnService.start(this)
            startStatePoller()
        }
    }

    private fun startStatePoller() {
        handler.removeCallbacksAndMessages(null)
        val runnable = object : Runnable {
            override fun run() {
                viewModel.refreshRunningState()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

package com.syncbeat.offline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var syncManager: AudioSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI created in code for testing
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val btnHost = Button(this).apply { text = "Host Room" }
        val btnJoin = Button(this).apply { text = "Join Room" }

        layout.addView(btnHost)
        layout.addView(btnJoin)
        setContentView(layout)

        syncManager = AudioSyncManager(this)

        btnHost.setOnClickListener {
            if (checkPermissions()) {
                syncManager.startHosting("HostDevice")
                Toast.makeText(this, "Hosting started...", Toast.LENGTH_SHORT).show()
            }
        }

        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                syncManager.startDiscovering()
                Toast.makeText(this, "Searching for host...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
            return false
        }
        return true
    }
}


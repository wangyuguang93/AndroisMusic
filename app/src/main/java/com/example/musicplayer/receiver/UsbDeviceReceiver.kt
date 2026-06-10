package com.example.musicplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.musicplayer.service.MusicPlayerService
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

class UsbDeviceReceiver : BroadcastReceiver() {
    private val TAG = "UsbDeviceReceiver"
    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicPlayerService.LocalBinder
            musicService = binder.service
            isServiceBound = true
            Log.d(TAG, "Service bound successfully")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
            musicService = null
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received USB action: $action")

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    Log.d(TAG, "USB设备已连接: ${device.deviceName}")
                    if (isAudioDevice(device)) {
                        bindToServiceAndConnect(context, device)
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    Log.d(TAG, "USB设备已断开: ${device.deviceName}")
                    if (isAudioDevice(device)) {
                        disconnectAudioDevice(context)
                    }
                }
            }

            "com.example.musicplayer.USB_PERMISSION" -> {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (granted && device != null) {
                        Log.d(TAG, "已获得USB设备权限: ${device.deviceName}")
                        if (isAudioDevice(device)) {
                            musicService?.connectUsbDac(device)
                        } else {
                            // 非音频设备，不执行操作
                        }
                    } else {
                        Log.d(TAG, "未获得USB设备权限: ${device?.deviceName ?: "未知设备"}")
                    }
                }
            }
        }
    }

    private fun bindToServiceAndConnect(context: Context, device: UsbDevice) {
        val serviceIntent = Intent(context, MusicPlayerService::class.java)
        context.startService(serviceIntent)
        
        object : Thread() {
            override fun run() {
                var attempts = 0
                val maxAttempts = 10
                
                while (!isServiceBound && attempts < maxAttempts) {
                    try {
                        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                        Thread.sleep(200)
                        attempts++
                    } catch (e: Exception) {
                        Log.e(TAG, "Service binding failed: ${e.message}")
                        break
                    }
                }
                
                if (isServiceBound) {
                    musicService?.connectUsbDac(device)
                } else {
                    Log.e(TAG, "无法绑定到MusicPlayerService")
                }
            }
        }.start()
    }

    private fun disconnectAudioDevice(context: Context) {
        if (isServiceBound) {
            musicService?.disconnectUsbDac()
            try {
                context.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service not bound")
            }
            isServiceBound = false
            musicService = null
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == 0x01) {
                return true
            }
        }
        return false
    }
}
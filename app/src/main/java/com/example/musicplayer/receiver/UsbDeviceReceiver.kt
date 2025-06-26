package com.example.musicplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.example.musicplayer.service.MusicPlayerService
class UsbDeviceReceiver : BroadcastReceiver() {
    private val TAG = "UsbDeviceReceiver"
    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicPlayerService.LocalBinder
            musicService = binder.service
            isServiceBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received USB action: \$action")

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    Log.d(TAG, "USB设备已连接: ${device.deviceName}")
                    if (isAudioDevice(device)) {
                        // 等待服务绑定完成后再连接USB DAC
                        Thread {
                            while (!isServiceBound) {
                                Thread.sleep(100)
                            }
                            musicService?.connectUsbDac(device)
                        }.start()
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    Log.d(TAG, "USB设备已断开: ${device.deviceName}")
                    if (isAudioDevice(device)) {
                        musicService?.disconnectUsbDac()
                    }
                }
            }

            "android.hardware.usb.action.USB_PERMISSION" -> {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (granted && device != null) {
                        Log.d(TAG, "已获得USB设备权限: ${device.deviceName}")
                        if (isAudioDevice(device)) {
                            musicService?.connectUsbDac(device)
                        } else {
                            // 非音频设备不执行操作
                        }
                    } else {
                        Log.d(TAG, "未获得USB设备权限: ${device?.deviceName ?: "未知设备"}")
                    }
                }
            }
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == 0x01) { // USB_CLASS_AUDIO
                return true
            }
        }
        return false
    }
}
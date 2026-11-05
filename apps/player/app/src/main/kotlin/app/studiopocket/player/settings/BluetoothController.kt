// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The Bluetooth page's work: radio on/off, a 30 s scan for audio devices, pairing (answering PIN or
 * confirmation requests itself), A2DP connect/disconnect and forget. Ported from the hardware probe
 * (spike/settings-probes ProbeActivity/ProbeMonitor, H3/H4). Runs between [start] and [stop], which
 * the settings controller calls on entering and leaving the Bluetooth pages; [onChanged] fires on
 * every Bluetooth event so the page re-renders. It never turns the radio on or off by itself, and
 * hidden-API failures set [BtState.message] instead of throwing.
 */
// BLUETOOTH_CONNECT/SCAN are API 31 permissions; on API 19 BLUETOOTH and BLUETOOTH_ADMIN cover these calls.
// The deprecated calls (getDefaultAdapter, enable/disable, getParcelableExtra) have no API 19 replacement.
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BluetoothController(private val context: Context, private val onChanged: () -> Unit) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var a2dp: BluetoothA2dp? = null
    /** Audio devices found by the current scan, by address, with the name they advertised. */
    private val found = LinkedHashMap<String, String>()
    private var scanning = false
    private var message: String? = null
    /** The device we called createBond on; the pairing auto-answer is registered only while set. */
    private var pairing: String? = null
    /** The device we asked A2DP to connect, so a drop straight back to disconnected reads as a failure. */
    private var connecting: String? = null

    private val stopScan = Runnable { adapter?.cancelDiscovery() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (device == null) return
                    val major = intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)?.majorDeviceClass
                        ?: device.bluetoothClass?.majorDeviceClass ?: return
                    if (!isAudioClass(major) || device.bondState != BluetoothDevice.BOND_NONE) return
                    found[device.address] = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name ?: device.address
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanning = false; handler.removeCallbacks(stopScan) }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) { scanning = false; found.clear() }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null && device.address == pairing) bondChanged(device, intent)
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> if (device != null && device.address == connecting) {
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> connecting = null
                        BluetoothProfile.STATE_DISCONNECTED -> { connecting = null; message = FAILED }
                    }
                }
            }
            onChanged()
        }
    }

    /** Answers the pairing request for the device we are pairing, before the system shows a dialog. */
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.address != pairing) return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
            val answered = try {
                when (variant) {
                    BluetoothDevice.PAIRING_VARIANT_PIN -> device.setPin("0000".toByteArray())
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> device.setPairingConfirmation(true)
                    else -> false
                }
            } catch (e: Exception) {
                Log.w(TAG, "pairing answer failed: $e")
                false
            }
            Log.i(TAG, "pairing request variant=$variant answered=$answered")
            if (answered && isOrderedBroadcast) abortBroadcast()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            // The proxy can arrive after we stopped; give it straight back.
            if (!started) { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy); return }
            // A fast leave and re-enter asks twice; keep the first proxy, give the extra one back.
            if (a2dp != null && a2dp !== proxy) { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy); return }
            a2dp = proxy as BluetoothA2dp
            onChanged()
        }
        override fun onServiceDisconnected(profile: Int) { a2dp = null; onChanged() }
    }

    val state: BtState
        get() {
            val bt = adapter
            val radio = bt?.state ?: BluetoothAdapter.STATE_OFF
            val on = radio == BluetoothAdapter.STATE_ON
            val paired = if (!on) emptyList() else bt?.bondedDevices.orEmpty().map { d ->
                BtDevice(d.address, d.name ?: d.address, a2dp?.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED)
            }
            return BtState(
                enabled = on || radio == BluetoothAdapter.STATE_TURNING_OFF,
                switching = radio == BluetoothAdapter.STATE_TURNING_ON || radio == BluetoothAdapter.STATE_TURNING_OFF,
                scanning = scanning,
                paired = paired,
                found = found.map { (address, name) -> BtDevice(address, name, false) },
                message = message,
            )
        }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(receiver, IntentFilter().apply {
            listOf(
                BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.ACTION_DISCOVERY_FINISHED,
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            ).forEach(::addAction)
        })
        adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(stopScan)
        if (scanning) adapter?.cancelDiscovery()
        context.unregisterReceiver(receiver)
        endPairing()
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
        scanning = false
        found.clear()
        message = null
        connecting = null
    }

    /** Only ever called from the power row: the player never changes the radio by itself. */
    fun setEnabled(on: Boolean) {
        val bt = adapter ?: return
        message = null
        if (on) bt.enable() else bt.disable()
    }

    /** Discovery for 30 s (or until the page is left); only audio devices are listed. */
    fun scan() {
        val bt = adapter ?: return
        message = null
        found.clear()
        bt.cancelDiscovery()
        scanning = bt.startDiscovery()
        handler.removeCallbacks(stopScan)
        if (scanning) handler.postDelayed(stopScan, SCAN_MS)
    }

    fun pair(address: String) {
        val bt = adapter ?: return
        bt.cancelDiscovery()
        endPairing()
        pairing = address
        context.registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        })
        message = "Pairing…"
        if (!bt.getRemoteDevice(address).createBond()) { endPairing(); message = FAILED }
    }

    /** setPriority(PRIORITY_ON) first marks the device for the system's own auto-reconnect (H4). */
    fun connect(address: String) {
        message = null
        connecting = address
        if (!a2dpCall("setPriority", address, 100) || !a2dpCall("connect", address)) connecting = null
    }

    fun disconnect(address: String) {
        message = null
        a2dpCall("disconnect", address)
    }

    /** removeBond is hidden on API 19. Returns whether the device is being forgotten. */
    fun forget(address: String): Boolean {
        message = null
        val device = adapter?.getRemoteDevice(address) ?: return false
        return hidden(address) { BluetoothDevice::class.java.getMethod("removeBond").invoke(device) }
    }

    private fun bondChanged(device: BluetoothDevice, intent: Intent) {
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
            BluetoothDevice.BOND_BONDED -> {
                endPairing()
                message = null
                found.remove(device.address)
                connect(device.address)
            }
            BluetoothDevice.BOND_NONE -> { endPairing(); message = FAILED }
        }
    }

    private fun endPairing() {
        if (pairing == null) return
        pairing = null
        context.unregisterReceiver(pairingReceiver)
    }

    /** A2DP's connect, disconnect and setPriority are hidden on API 19. */
    private fun a2dpCall(name: String, address: String, arg: Int? = null): Boolean {
        val proxy = a2dp ?: run { message = FAILED; return false }
        val device = adapter?.getRemoteDevice(address) ?: return false
        return hidden(address) {
            if (arg == null) BluetoothA2dp::class.java.getMethod(name, BluetoothDevice::class.java).invoke(proxy, device)
            else BluetoothA2dp::class.java.getMethod(name, BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(proxy, device, arg)
        }
    }

    /** Runs a reflection call; an exception or a false result sets the failure message. */
    private fun hidden(address: String, call: () -> Any?): Boolean {
        val ok = try {
            call() != false
        } catch (e: Exception) {
            Log.w(TAG, "bluetooth call failed for $address: $e")
            false
        }
        if (!ok) message = FAILED
        return ok
    }

    companion object {
        private const val TAG = "StudioPocket"
        private const val SCAN_MS = 30_000L
        private const val FAILED = "Couldn't connect"
    }
}

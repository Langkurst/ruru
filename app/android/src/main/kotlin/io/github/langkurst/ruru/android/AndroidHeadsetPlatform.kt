package io.github.langkurst.ruru.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.protocol.bose.BoseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Android: bonded Bose headsets, opened on the Serial Port service like the Bose app does, with
 * the model's known RFCOMM channel as a fallback (Earshot's findings). Needs BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class AndroidHeadsetPlatform(private val context: Context) : HeadsetPlatform {
    override val name: String = "Android"
    override val supported: Boolean = adapter() != null

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun allowed() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext emptyList()
        val bonded = adapter()?.bondedDevices.orEmpty()
        bonded.filter { (it.name ?: "").contains("Bose", ignoreCase = true) && !(it.name ?: "").startsWith("LE-") }
            .map { HeadsetCandidate(key = it.address, name = it.name ?: "Bose headphones", connected = it.isConnectedCompat()) }
            .sortedByDescending { it.connected }
    }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport = withContext(Dispatchers.IO) {
        val device = adapter()?.getRemoteDevice(candidate.key) ?: throw IOException("Bluetooth is off")
        adapter()?.cancelDiscovery()
        val tries = buildList<() -> BluetoothSocket> {
            add { device.createRfcommSocketToServiceRecord(SPP) }
            val channel = BoseModel.fromName(candidate.name)?.rfcommChannel ?: 2
            for (ch in listOf(channel, 2, 8, 9).distinct()) add { device.createRfcommSocketByChannel(ch) }
        }
        var last: Exception? = null
        for (make in tries) {
            val socket = try { make() } catch (e: Exception) { last = e; continue }
            try {
                socket.connect()
                return@withContext AndroidRfcommTransport(socket)
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw IOException("Could not open the control channel", last)
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        /** BluetoothDevice.isConnected() is hidden but on the SDK's allowed list; assume yes if it is missing. */
        fun BluetoothDevice.isConnectedCompat(): Boolean =
            runCatching { javaClass.getMethod("isConnected").invoke(this) as Boolean }.getOrDefault(true)

        fun BluetoothDevice.createRfcommSocketByChannel(channel: Int): BluetoothSocket =
            javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(this, channel) as BluetoothSocket
    }
}

private class AndroidRfcommTransport(private val socket: BluetoothSocket) : ByteTransport {
    @Volatile private var open = true
    override val isConnected: Boolean get() = open && socket.isConnected

    override val incoming: Flow<ByteArray> = flow {
        val input = socket.inputStream
        val buf = ByteArray(1024)
        while (open) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (!open) break
                open = false
                throw e
            }
            if (n < 0) {
                open = false
                throw IOException("the headset closed the control channel")
            }
            if (n > 0) emit(buf.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!open) throw IOException("control channel closed")
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    override fun close() {
        open = false
        runCatching { socket.close() }
    }
}

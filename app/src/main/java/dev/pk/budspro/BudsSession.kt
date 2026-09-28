package dev.pk.budspro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import dev.pk.budspro.protocol.Codec
import dev.pk.budspro.protocol.Frame
import dev.pk.budspro.protocol.Msg
import java.io.IOException

/** One RFCOMM connection to the earbuds. All methods block. */
@SuppressLint("MissingPermission")
class BudsSession private constructor(private val socket: BluetoothSocket) {
    private val output = socket.outputStream
    private val writeLock = Any()

    fun send(id: Int, payload: ByteArray = ByteArray(0)) {
        val frame = Codec.encode(id, payload)
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    /** Reads frames until the connection drops. */
    fun readLoop(onFrame: (Frame) -> Unit) {
        val parser = Codec.Parser()
        val input = socket.inputStream
        val buf = ByteArray(2048)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                parser.feed(buf, n).forEach(onFrame)
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        fun open(device: BluetoothDevice): BudsSession = try {
            connect(device.createRfcommSocketToServiceRecord(Msg.SPP_UUID))
        } catch (_: IOException) {
            connect(device.createInsecureRfcommSocketToServiceRecord(Msg.SPP_UUID))
        }

        private fun connect(socket: BluetoothSocket): BudsSession {
            try {
                socket.connect()
            } catch (e: IOException) {
                try { socket.close() } catch (_: IOException) {}
                throw e
            }
            return BudsSession(socket)
        }
    }
}

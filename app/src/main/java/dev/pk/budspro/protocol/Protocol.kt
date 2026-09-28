package dev.pk.budspro.protocol

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Galaxy Buds Pro (SM-R190) SPP protocol. Verified on firmware R190XXU0AVF1. */
object Msg {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val BUILD_INFO = 0x28
    const val TOUCH_ON_BUDS = 0x2D
    const val ACK = 0x42
    const val STATUS_UPDATED = 0x60
    const val EXTENDED_STATUS = 0x61
    const val NOISE_CONTROLS_UPDATE = 0x77
    const val NOISE_CONTROLS = 0x78
    const val SET_DETECT_CONVERSATIONS = 0x7A
    const val SET_DETECT_CONVERSATIONS_DURATION = 0x7B
    const val NOISE_REDUCTION_LEVEL = 0x83
    const val AMBIENT_VOLUME = 0x84
    const val GAMING_MODE = 0x85
    const val EQUALIZER = 0x86
    const val LOCK_TOUCHPAD = 0x90
    const val TOUCH_UPDATED = 0x91
    const val SET_TOUCHPAD_OPTION = 0x92
    const val OUTSIDE_DOUBLE_TAP = 0x95
    const val FIND_MY_EARBUDS_START = 0xA0
    const val FIND_MY_EARBUDS_STOP = 0xA1
}

class Frame(val id: Int, val payload: ByteArray)

object Codec {
    private const val SOM = 0xFD
    private const val EOM = 0xDD

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /** FD | size (LE, 10 bits) | id | payload | crc16 (LE) | DD, where size = payload + 3. */
    fun encode(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = payload.size + 3
        val body = byteArrayOf(id.toByte()) + payload
        val crc = crc16(body)
        return ByteArrayOutputStream().apply {
            write(SOM)
            write(size and 0xFF)
            write((size shr 8) and 0x03)
            write(body)
            write(crc and 0xFF)
            write(crc shr 8)
            write(EOM)
        }.toByteArray()
    }

    /** Streaming parser: buffers bytes and emits complete frames with a valid CRC. */
    class Parser {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray, len: Int = data.size): List<Frame> {
            buf += data.copyOf(len)
            val out = ArrayList<Frame>()
            var pos = 0
            while (true) {
                val start = indexOfSom(pos)
                if (start < 0) { pos = buf.size; break }
                when (val total = check(start)) {
                    BAD -> pos = start + 1
                    INCOMPLETE -> {
                        // A stray 0xFD may claim a long frame, so look further for a complete valid one.
                        val next = generateSequence(indexOfSom(start + 1)) { indexOfSom(it + 1) }
                            .takeWhile { it >= 0 }
                            .firstOrNull { check(it) > 0 }
                        if (next == null) { pos = start; break }
                        pos = next
                    }
                    else -> {
                        out += Frame(buf[start + 3].toInt() and 0xFF, buf.copyOfRange(start + 4, start + total - 3))
                        pos = start + total
                    }
                }
            }
            buf = buf.copyOfRange(pos, buf.size)
            if (buf.size > MAX_BUFFER) buf = ByteArray(0)
            return out
        }

        private fun indexOfSom(from: Int): Int {
            for (i in from until buf.size) if ((buf[i].toInt() and 0xFF) == SOM) return i
            return -1
        }

        /** Length of a valid frame at [start], or [BAD] / [INCOMPLETE]. */
        private fun check(start: Int): Int {
            if (buf.size - start < 3) return INCOMPLETE
            val size = ((buf[start + 1].toInt() and 0xFF) or ((buf[start + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (size < 3 || size > MAX_SIZE) return BAD
            val total = size + 4
            if (buf.size - start < total) return INCOMPLETE
            val end = start + total
            val crcGot = (buf[end - 3].toInt() and 0xFF) or ((buf[end - 2].toInt() and 0xFF) shl 8)
            val ok = (buf[end - 1].toInt() and 0xFF) == EOM && crc16(buf, start + 3, end - 3) == crcGot
            return if (ok) total else BAD
        }

        private companion object {
            const val BAD = -1
            const val INCOMPLETE = 0
            const val MAX_SIZE = 1000
            const val MAX_BUFFER = 16 * 1024
        }
    }
}

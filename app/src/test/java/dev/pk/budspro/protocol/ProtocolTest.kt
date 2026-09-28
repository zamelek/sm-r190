package dev.pk.budspro.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun lockFrameMatchesCapturedBytes() {
        assertArrayEquals(hex("fd 04 00 90 01 ca 08 dd"), Codec.encode(Msg.LOCK_TOUCHPAD, byteArrayOf(1)))
        assertArrayEquals(hex("fd 05 00 92 02 02 79 50 dd"), Codec.encode(Msg.SET_TOUCHPAD_OPTION, byteArrayOf(2, 2)))
        assertArrayEquals(hex("fd 04 00 85 00 6d e4 dd"), Codec.encode(Msg.GAMING_MODE, byteArrayOf(0)))
    }

    @Test fun parserHandlesSplitFramesGarbageAndSequenceBits() {
        val ack = Codec.encode(Msg.ACK, byteArrayOf(0x90.toByte(), 1))
        ack[2] = (ack[2].toInt() or 0xC0).toByte() // upper header bits are a sequence counter, not length
        val crcFix = Codec.encode(Msg.ACK, byteArrayOf(0x90.toByte(), 1))
        val stream = hex("00 dd fd") + crcFix + ack
        val p = Codec.Parser()
        val a = p.feed(stream.copyOfRange(0, 6))
        val b = p.feed(stream.copyOfRange(6, stream.size))
        val frames = a + b
        assertEquals(2, frames.size)
        frames.forEach {
            assertEquals(Msg.ACK, it.id)
            assertArrayEquals(byteArrayOf(0x90.toByte(), 1), it.payload)
        }
    }

    @Test fun extendedStatusFromRealBuds() {
        val p = hex("0a 02 4c 4b 01 00 11 65 00 01 01 22 00 00 2c 01 2c 01 03 00 03 55 00 01 00 00 00 01 00 10 00 00 00 00 11 02 01 00")
        val s = BudsState().withExtended(p)
        assertEquals(10, s.extRevision)
        assertEquals(76, s.batteryL)
        assertEquals(75, s.batteryR)
        assertEquals(null, s.batteryCase)
        assertEquals(Placement.WEARING, s.placementL)
        assertEquals(true, s.touchLocked)
        assertEquals(NoiseMode.OFF, s.noiseMode)
        assertEquals(TouchOption.NOISE_CONTROL, s.touchOptionL)
        assertEquals(1, s.equalizer)
        assertEquals(1, s.ambientVolume)
        assertEquals(1, s.detectConversationsDuration)
        assertEquals(false, s.outsideDoubleTap)
    }
}

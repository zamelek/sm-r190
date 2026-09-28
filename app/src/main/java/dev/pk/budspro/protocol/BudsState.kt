package dev.pk.budspro.protocol

enum class Placement { DISCONNECTED, WEARING, IDLE, IN_CASE, UNKNOWN;
    companion object {
        fun of(v: Int) = when (v) { 0 -> DISCONNECTED; 1 -> WEARING; 2 -> IDLE; 3 -> IN_CASE; else -> UNKNOWN }
    }
}

object NoiseMode { const val OFF = 0; const val ANC = 1; const val AMBIENT = 2 }

object TouchOption { const val VOICE_ASSISTANT = 1; const val NOISE_CONTROL = 2; const val VOLUME = 3; const val SPOTIFY = 4 }

data class BudsState(
    val batteryL: Int? = null,
    val batteryR: Int? = null,
    val batteryCase: Int? = null,
    val placementL: Placement = Placement.UNKNOWN,
    val placementR: Placement = Placement.UNKNOWN,
    val coupled: Boolean = false,
    val touchLocked: Boolean? = null,
    val noiseMode: Int? = null,
    val ancLevel: Int = 0,
    val ambientVolume: Int = 0,
    val equalizer: Int = 0,
    val gamingMode: Boolean = false,
    val touchOptionL: Int = TouchOption.NOISE_CONTROL,
    val touchOptionR: Int = TouchOption.NOISE_CONTROL,
    val detectConversations: Boolean = false,
    val detectConversationsDuration: Int = 1,
    val outsideDoubleTap: Boolean = false,
    val extRevision: Int? = null,
    val build: String? = null,
    val findingEarbuds: Boolean = false,
) {
    val hasStatus get() = extRevision != null

    /** 0x60 STATUS_UPDATED: [rev, batL, batR, coupled, mainConn, placement, case]. */
    fun withStatus(p: ByteArray): BudsState {
        if (p.size < 7) return this
        return copy(
            batteryL = battery(p[1]), batteryR = battery(p[2]),
            coupled = p[3].u == 1,
            placementL = Placement.of(p[5].u shr 4), placementR = Placement.of(p[5].u and 0x0F),
            batteryCase = battery(p[6]),
        )
    }

    /** 0x61 EXTENDED_STATUS_UPDATED, Buds Pro layout (revisions 3..10). */
    fun withExtended(p: ByteArray): BudsState {
        if (p.size < 28) return this
        val rev = p[0].u
        return copy(
            extRevision = rev,
            batteryL = battery(p[2]), batteryR = battery(p[3]),
            coupled = p[4].u == 1,
            placementL = Placement.of(p[6].u shr 4), placementR = Placement.of(p[6].u and 0x0F),
            batteryCase = battery(p[7]),
            gamingMode = p[8].u == 1,
            equalizer = p[9].u,
            touchLocked = p[10].u == 1,
            touchOptionL = p[11].u shr 4, touchOptionR = p[11].u and 0x0F,
            noiseMode = p[12].u,
            ambientVolume = p[23].u,
            ancLevel = p[24].u,
            detectConversations = p[26].u == 1,
            detectConversationsDuration = p[27].u.coerceIn(0, 2),
            outsideDoubleTap = rev >= 7 && p.size > 31 && p[31].u == 1,
        )
    }

    private fun battery(b: Byte) = b.u.takeIf { it in 1..100 }
}

val Byte.u get() = toInt() and 0xFF

package io.github.langkurst.ruru.vendors.soundcore

/**
 * The single write path's allow-list for Soundcore, like `SonyWriteGuard`: refuse by default.
 * Only commands Ruru has a reason to send leave the phone; everything else, in particular the ones
 * in [NEVER], is refused before it reaches the socket: `0b 83` forget a host (unpair), `0b 85`
 * pairing mode, `0b 81` / `0b 82` host connections, `01 85` / `01 89` power off (ids differ by
 * model), `10 89` find device (a loud tone in sleep earbuds), `04 82` reset buttons, `14 82`
 * delete alarm, `09 01` fit test, and any unknown write. Factory reset and firmware update have no
 * known id here, so they are unknown writes; the firmware-update service (BES `66666666-...`) is a
 * separate RFCOMM service that Ruru never opens.
 *
 * Ids are reused across models, so the list is per model ([isAllowed] with a model), and until the
 * state read has told the session which model it is talking to, only the state request leaves.
 */
object SoundcoreWriteGuard {
    /** The Sleep A30's list (the one model run on real earbuds): reads, and writes of one setting each. */
    val ALLOWED: Set<Int> = setOf(
        // Reads.
        SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_INFO, SoundcoreCommands.SLEEP_SETTINGS_GET, SoundcoreCommands.ALARMS_GET,
        // Writes the driver uses (Sleep A30 ids, each a single setting).
        SoundcoreCommands.SET_ANC_D1301, SoundcoreCommands.SET_TAP_DISABLED, SoundcoreCommands.SET_LISTENING_MODE,
        SoundcoreCommands.SET_DEFAULT_LISTENING_MODE, SoundcoreCommands.SET_LOW_BATTERY_PROMPT,
        SoundcoreCommands.SET_AUTO_OFF_PROMPT, SoundcoreCommands.SET_LISTENING_MODE_PROMPT, SoundcoreCommands.SET_ANC_PROMPT,
        SoundcoreCommands.SET_INCOMING_CALLS, SoundcoreCommands.SET_AUTO_SWITCH_ASLEEP, SoundcoreCommands.SET_SLEEP_TIMER,
        // Session-init sequence some models need before writes take effect (soundcorebridge, MIT).
        SoundcoreCommands.SESSION_INFO, SoundcoreCommands.SESSION_INIT,
    )

    /**
     * Refused for every model, whatever its table says: the ids that unpair, enter pairing mode,
     * connect or disconnect a host, power off, play the find tone, reset the buttons, delete an
     * alarm, or start the fit test (loud tones). `0b 84` is not in this set: it is the multipoint
     * switch on the earbuds that list it (and refused on every other model).
     */
    val NEVER: Set<Int> = setOf(
        0x0B81, 0x0B82, 0x0B83, 0x0B85, 0x0185, 0x0189, 0x1089, 0x0482, 0x1482, 0x0901,
    )

    /** Reads every driven model may make. `05 01` joins them only for models with a session-init. */
    private val READS: Set<Int> = setOf(SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_INFO)

    /**
     * The commands that only read: the state, the device info, the capability table, the alarms and
     * the sleep settings. Matched by id and not by the `0x80` code bit, because the Sleep A30 has
     * writes below it (`10 75`, `10 77`).
     */
    private val READ_COMMANDS: Set<Int> = setOf(
        SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_INFO, SoundcoreCommands.SESSION_INFO,
        SoundcoreCommands.ALARMS_GET, SoundcoreCommands.SLEEP_SETTINGS_GET,
    )

    /** True for a command that changes nothing on the earbuds. Everything else is a write and needs the unlock. */
    fun isRead(packet: SoundcorePacket): Boolean = packet.command in READ_COMMANDS

    private fun bit(body: ByteArray): Boolean = body.size == 1 && (body[0].toInt() and 0xFF) <= 1

    /** The Sleep A30's own list, with the body shapes its driver builds. */
    private fun allowedOnSleepA30(packet: SoundcorePacket): Boolean {
        if (packet.command !in ALLOWED) return false
        if (isRead(packet)) return packet.body.isEmpty()
        return when (packet.command) {
            SoundcoreCommands.SESSION_INIT -> packet.body.isEmpty()
            // `FF FF FF action`: leave the timer fields alone, set only the post-sleep action.
            SoundcoreCommands.SET_SLEEP_TIMER -> packet.body.size == 4 &&
                packet.body.take(3).all { it == 0xFF.toByte() } && (packet.body[3].toInt() and 0xFF) <= 1
            else -> bit(packet.body)
        }
    }

    /** The Sleep A30's list on its own (a connection that knows it is talking to one). */
    fun isAllowed(packet: SoundcorePacket): Boolean = allowedOnSleepA30(packet)

    /**
     * Whether [packet] may go to [model]. `null` is a connection that has not identified the
     * earbuds yet: the state request only. The Sleep A30 keeps its own list; any other model gets
     * the state reads, and for a model whose layout is writable the mode block (`06 81`, exact
     * codec size), its documented settings (`[0|1]`, auto-off `[enabled, index]`) and, where the
     * model needs it, the session-init `05 01` / `05 81`. Anything else is refused.
     */
    fun isAllowed(model: SoundcoreModel?, packet: SoundcorePacket): Boolean {
        if (packet.command in NEVER) return false
        if (model == null) return packet.command == SoundcoreCommands.REQUEST_STATE && packet.body.isEmpty()
        if (model == SoundcoreModel.SLEEP_A30) return allowedOnSleepA30(packet)
        val layout = model.layout
        if (!packet.isWrite) {
            if (packet.command in READS) return packet.body.isEmpty()
            return packet.command == SoundcoreCommands.SESSION_INFO && layout?.sessionInit == true && layout.writable && packet.body.isEmpty()
        }
        return layout?.allows(packet.command, packet.body) == true
    }

    fun check(model: SoundcoreModel?, packet: SoundcorePacket) {
        require(isAllowed(model, packet)) { "Soundcore command ${SoundcorePacket.commandName(packet.command)} is not on Ruru's allow-list" }
    }
}

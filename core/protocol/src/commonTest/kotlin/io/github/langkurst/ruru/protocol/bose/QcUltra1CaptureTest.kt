package io.github.langkurst.ruru.protocol.bose

import io.github.langkurst.ruru.protocol.hexToBytes
import io.github.langkurst.ruru.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Frames from a real QC Ultra Headphones (1st Gen), firmware 1.6.7+g6ebabd2, exchanged over
 * Bluetooth by Ruru's probe on Windows on 11 November 2026: the read-only pass and the write test
 * (each change checked and put back). No Bluetooth address or serial number is part of this data.
 */
class QcUltra1CaptureTest {

    @Test
    fun `identity picks the 1st Gen profile`() {
        val id = assertNotNull(ProductInfo.parseProductIdVariant("406601".hexToBytes()))
        assertEquals(0x4066, id.productId)
        assertEquals(1, id.variant)
        assertEquals(BoseModel.QC_ULTRA_1, id.model)
        assertEquals("1.2.0", ProductInfo.parseBmapVersion("312e322e30".hexToBytes()))
        assertEquals("1.6.7+g6ebabd2", ProductInfo.parseFirmwareVersion("312e362e372b6736656261626432".hexToBytes()))
        val name = ProductInfo.parseString(NAME.hexToBytes())
        assertEquals("Bose QC Ultra Headphones", name)
        assertEquals(BoseModel.QC_ULTRA_1, BoseModel.fromName(name))
        assertEquals(
            setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 18, 19, 22, 23, 24, 25, 26, 31),
            ProductInfo.parseAllFunctionBlocks("87cc23ff".hexToBytes()),
        )
    }

    @Test
    fun `profile matches what the unit reports`() {
        val caps = assertNotNull(ModesCapabilities.parse("03070000001f02".hexToBytes()))
        val model = BoseModel.QC_ULTRA_1
        assertEquals(3, caps.boseModes)
        assertEquals(7, caps.userModes)
        assertTrue(caps.cnc && caps.autoCnc && caps.spatial && caps.windBlock && caps.favorites)
        assertFalse(caps.ancToggle, "no ANC on/off switch on the 1st Gen")
        assertEquals(2, caps.minFavorites)
        assertEquals(caps.presetSlots, model.presetSlots)
        assertEquals(caps.customSlots, model.editableSlots)
        assertEquals(caps.totalModes, model.slotCount)
        assertEquals(listOf("Quiet", "Aware", "Immersion"), model.presetNames)
        assertEquals(ModeLayout.ofStatus(SLOTS.first().length / 2), model.modeLayout)
        assertEquals(ModeLayout.COMPACT, model.modeLayout)
        assertFalse(model.hasLiveAudioSettings, "[31.10] answers FuncNotSupp")
        assertTrue(model.verified)
        assertTrue(model.tested)
    }

    @Test
    fun `all ten mode slots are 47-byte frames`() {
        val slots = SLOTS.map { assertNotNull(ModeConfigs.parse(it.hexToBytes())) }
        assertEquals((0..9).toList(), slots.map { it.index })
        assertTrue(SLOTS.all { it.length / 2 == ModeConfigs.COMPACT_STATUS_SIZE })

        val (quiet, aware, immersion) = slots
        assertEquals("Quiet", quiet.name)
        assertEquals(0, quiet.cncWire)
        assertEquals("Aware", aware.name)
        assertEquals(10, aware.cncWire)
        assertEquals(0x02, aware.changeableFlags)
        assertEquals("Immersion", immersion.name)
        assertEquals(2, immersion.spatialCode)
        for (preset in listOf(quiet, aware, immersion)) {
            assertEquals(false, preset.editable)
            assertTrue(preset.ancEnabled, "no toggle byte: ANC is always on")
        }

        for (empty in slots.drop(3)) {
            assertEquals("None", empty.name)
            assertEquals(true, empty.editable)
            assertEquals(false, empty.configured)
            assertEquals(10, empty.cncWire)
            assertEquals(0x0d, empty.changeableFlags, "cnc, spatial and wind can change; autoCnc and ANC cannot")
            assertFalse(empty.windBlock)
        }
    }

    @Test
    fun `current mode and favourites`() {
        assertEquals(0, CurrentMode.parse("00".hexToBytes()))
        val fav = assertNotNull(FavoritesCodec.parse("0a0007".hexToBytes()))
        assertEquals(10, fav.totalModes)
        assertEquals(setOf(0, 1, 2), fav.indices)
    }

    @Test
    fun `settings replies`() {
        val battery = Battery.parseReadings("5affff00".hexToBytes())
        assertEquals(90, Battery.aggregate(battery))

        val cnc = assertNotNull(Cnc.parse("0b0003".hexToBytes()))
        assertEquals(11, cnc.steps)
        assertEquals(10, cnc.cancellingLevel)
        assertFalse(cnc.userCanToggle)

        val eq = Eq.parse("f60a0000f60a0001f60a0002".hexToBytes())
        assertEquals(listOf(0, 0, 0), eq.map { it.value })
        assertTrue(eq.all { it.min == -10 && it.max == 10 })

        val button = assertNotNull(Buttons.parse("80090e0009400a".hexToBytes()))
        assertEquals(ButtonId.SHORTCUT.code, button.buttonCode)
        assertEquals(ButtonEvent.LONG_PRESS.code, button.eventCode)
        assertEquals(setOf(1, 3, 14, 16, 19), button.supportedActionCodes)

        assertEquals(true, Multipoint.parse("07".hexToBytes()))
        assertNotNull(Sidetone.parse("01020f".hexToBytes()))
        val prompts = assertNotNull(VoicePrompts.parse("e10001815e0101".hexToBytes()))
        assertTrue(prompts.enabled)
        assertEquals(1, prompts.languageCode)
        assertEquals(1440, StandbyTimer.parse("a00005".hexToBytes()), "auto-off answers on the 1st Gen: 24 h")
        assertEquals(true, Toggle.parse("01".hexToBytes()))
    }

    @Test
    fun `writes match the frames the headset accepted`() {
        // EQ: bass +1, the reply carries all three bands.
        assertEquals("0100", Eq.buildBand(1, EqBandId.BASS).toHex())
        assertEquals(1, Eq.valueOf(Eq.parse("f60a0100f60a0001f60a0002".hexToBytes()), EqBandId.BASS))

        // Auto-off: one byte up to 255 minutes, two bytes little-endian above; the STATUS is [lo, 0, hi].
        assertEquals("b4", StandbyTimer.build(180).toHex())
        assertEquals(180, StandbyTimer.parse("b40000".hexToBytes()))
        assertEquals("a005", StandbyTimer.build(1440).toHex())
        assertEquals(1440, StandbyTimer.parse("a00005".hexToBytes()))

        // Mode switch: START [index, announce] answered RESULT [index].
        assertEquals("0100", CurrentMode.startPayload(1).toHex())
        assertEquals("0000", CurrentMode.startPayload(0).toHex())

        // Mode save: the 39-byte COMPACT write, and the 47-byte STATUS that came back.
        val write = ModeConfigWrite(index = 9, name = "Ruru test", cncWire = 5, spatialCode = 0,
            windBlock = false, ancEnabled = true, promptBytes = 0 to 0)
        assertEquals(COMPACT_WRITE, write.build(BoseModel.QC_ULTRA_1.modeLayout, BoseModel.QC_ULTRA_1.editableSlots).toHex())
        val saved = assertNotNull(ModeConfigs.parse(SAVED_SLOT.hexToBytes()))
        assertEquals("Ruru test", saved.name)
        assertEquals(5, saved.cncWire)
        assertEquals(true, saved.configured)
        assertFalse(saved.windBlock)

        // Reset: START [09] → RESULT; the slot reads exactly as before the test.
        assertEquals("09", ModeReset.startPayload(9).toHex())
    }

    private companion object {
        const val COMPACT_WRITE = "090000527572752074657374000000000000000000000000000000000000000000000005000000"
        const val SAVED_SLOT = "09000001010052757275207465737400000000000000000000000000000000000000000000000000000d0500000100"

        const val NAME = "426f736520514320556c747261204865616470686f6e6573"

        val SLOTS = listOf(
            "0000010000015175696574000000000000000000000000000000000000000000000000000000000000000000000000",
            "0100020000014177617265000000000000000000000000000000000000000000000000000000000000020a00000000",
            "020022000001496d6d657273696f6e0000000000000000000000000000000000000000000000000000000000020000",
            "0300000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
            "0400000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
            "0500000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
            "0600000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
            "0700000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
            "0800000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
            "0900000100004e6f6e65000000000000000000000000000000000000000000000000000000000000000d0a00000100",
        )
    }
}

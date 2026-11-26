package io.github.langkurst.ruru.app.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Ruru reads from the PipeWire tools, with samples written by hand in the shape those tools print. No personal data. */
class PipeWireTextTest {
    @Test
    fun `the version comes from the library pipewire is linked with`() {
        val out = "pipewire\nCompiled with libpipewire 1.0.5\nLinked with libpipewire 1.0.7\n"
        assertEquals(PipeWireVersion(1, 0, 7), PipeWireText.parseVersion(out))
        assertEquals(PipeWireVersion(0, 3, 65), PipeWireText.parseVersion("pipewire\nCompiled with libpipewire 0.3.65\n"))
        assertEquals(PipeWireVersion(0, 3, 48), PipeWireText.parseVersion("pipewire 0.3.48"))
        assertNull(PipeWireText.parseVersion("command not found"))
        assertNull(PipeWireText.parseVersion(""))
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(PipeWireVersion(0, 3, 9) < PipeWireVersion(0, 3, 64))
        assertTrue(PipeWireVersion(0, 3, 63) < PipeWireText.MINIMUM)
        assertTrue(PipeWireVersion(0, 3, 64) >= PipeWireText.MINIMUM)
        assertTrue(PipeWireVersion(1, 0, 0) > PipeWireVersion(0, 3, 100))
    }

    @Test
    fun `sinks are the Audio-Sink nodes of pw-dump and nothing else`() {
        val sinks = PipeWireText.parseSinks(DUMP)
        assertEquals(listOf(47, 53, 61), sinks.map { it.id })
        val bluez = sinks.first { it.id == 53 }
        assertEquals("bluez_output.AA_BB_CC_DD_EE_01.1", bluez.nodeName)
        assertEquals("Sample Headphones", bluez.description)
    }

    @Test
    fun `text that is not a dump gives no sinks`() {
        assertEquals(emptyList(), PipeWireText.parseSinks(""))
        assertEquals(emptyList(), PipeWireText.parseSinks("Can't connect to PipeWire"))
        assertEquals(emptyList(), PipeWireText.parseSinks("{\"id\": 1}"))
        assertEquals(emptyList(), PipeWireText.parseSinks("[1, \"two\", null, {\"id\": 3}]"))
    }

    @Test
    fun `the headphones' sink is found by name, Bluetooth first, and Ruru's own sink never`() {
        val sinks = PipeWireText.parseSinks(DUMP)
        assertEquals(53, PipeWireText.findHeadphoneSink(sinks, "Sample Headphones")?.id)
        assertEquals(53, PipeWireText.findHeadphoneSink(sinks, "sample  headphones")?.id)
        // The name the headphones report can be longer than the output's description.
        assertEquals(53, PipeWireText.findHeadphoneSink(sinks, "Sample Headphones (1st Gen)")?.id)
        assertNull(PipeWireText.findHeadphoneSink(sinks, "Other Buds"))
        assertNull(PipeWireText.findHeadphoneSink(sinks, null))
        assertNull(PipeWireText.findHeadphoneSink(sinks, "  "))
        val twin = sinks + PipeWireSink(70, "alsa_output.usb-sample.analog-stereo", "Sample Headphones", null)
        assertEquals(53, PipeWireText.findHeadphoneSink(twin, "Sample Headphones")?.id)
        assertNull(PipeWireText.findHeadphoneSink(sinks.filter { it.nodeName == "ruru_eq" }, "Ruru EQ"))
    }

    @Test
    fun `the default output's name is read from wpctl inspect`() {
        assertEquals("alsa_output.pci-0000_00_1f.3.analog-stereo", PipeWireText.parseInspectNodeName(INSPECT))
        assertNull(PipeWireText.parseInspectNodeName("Object '@DEFAULT_AUDIO_SINK@' not found"))
        assertNotNull(PipeWireText.sinkByName(PipeWireText.parseSinks(DUMP), "ruru_eq"))
    }

    companion object {
        /** `pw-dump` of a desktop with built-in speakers (47), a Bluetooth headset (53), Ruru's own sink (61), plus things that are not sinks. */
        val DUMP = """
            [
              { "id": 0, "type": "PipeWire:Interface:Core", "version": 4, "info": { "props": { "core.name": "pipewire-0" } } },
              { "id": 31, "type": "PipeWire:Interface:Device", "version": 3,
                "info": { "props": { "device.name": "bluez_card.AA_BB_CC_DD_EE_01", "device.alias": "Sample Headphones", "media.class": "Audio/Device" } } },
              { "id": 47, "type": "PipeWire:Interface:Node", "version": 3,
                "info": { "state": "suspended", "props": {
                  "media.class": "Audio/Sink",
                  "node.name": "alsa_output.pci-0000_00_1f.3.analog-stereo",
                  "node.description": "Built-in Audio Analog Stereo",
                  "node.nick": "HDA Intel PCH" } } },
              { "id": 48, "type": "PipeWire:Interface:Node", "version": 3,
                "info": { "props": { "media.class": "Audio/Source", "node.name": "alsa_input.pci-0000_00_1f.3.analog-stereo", "node.description": "Built-in Audio Analog Stereo" } } },
              { "id": 53, "type": "PipeWire:Interface:Node", "version": 3,
                "info": { "state": "running", "props": {
                  "device.id": 31,
                  "media.class": "Audio/Sink",
                  "node.name": "bluez_output.AA_BB_CC_DD_EE_01.1",
                  "node.description": "Sample Headphones",
                  "api.bluez5.profile": "a2dp-sink" } } },
              { "id": 61, "type": "PipeWire:Interface:Node", "version": 3,
                "info": { "props": { "media.class": "Audio/Sink", "node.name": "ruru_eq", "node.description": "Ruru EQ" } } },
              { "id": 62, "type": "PipeWire:Interface:Node", "version": 3,
                "info": { "props": { "media.class": "Stream/Output/Audio", "node.name": "ruru_eq_output" } } },
              { "id": 70, "type": "PipeWire:Interface:Port", "version": 3, "info": { "props": { "port.name": "playback_FL" } } }
            ]
        """.trimIndent()

        /** `wpctl inspect @DEFAULT_AUDIO_SINK@` for the built-in speakers. */
        val INSPECT = """
            id 47, type PipeWire:Interface:Node
                alsa.card = "0"
                alsa.card_name = "HDA Intel PCH"
                audio.channels = "2"
                device.id = "45"
                factory.name = "api.alsa.pcm.sink"
                media.class = "Audio/Sink"
              * node.description = "Built-in Audio Analog Stereo"
              * node.name = "alsa_output.pci-0000_00_1f.3.analog-stereo"
                node.nick = "HDA Intel PCH"
        """.trimIndent()
    }
}

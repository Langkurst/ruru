package io.github.langkurst.ruru.dsp

/**
 * Golden output of the two writers, reviewed by hand once. The PipeWire files target the
 * sink "bluez_output.XX.1". Change a writer on purpose and these change with it.
 */
// A Windows checkout turns the line ends of this file into CRLF; the writers always print LF.
private fun String.lf() = replace("\r\n", "\n")

internal object EqGolden {
    val apoFlat = """# Written by Ruru. Changes are overwritten.
Preamp: 0.0 dB
""".lf()

    val pwFlat = """# Written by Ruru. Changes are overwritten.
context.modules = [
    { name = libpipewire-module-filter-chain
        args = {
            node.description = "Ruru EQ"
            media.name = "Ruru EQ"
            filter.graph = {
                nodes = [
                    { type = builtin name = eq_pre label = linear control = { "Mult" = 1.000000 "Add" = 0.0 } }
                ]
            }
            audio.channels = 2
            audio.position = [ FL FR ]
            capture.props = {
                node.name = "ruru_eq"
                media.class = Audio/Sink
            }
            playback.props = {
                node.name = "ruru_eq_output"
                node.passive = true
                target.object = "bluez_output.XX.1"
            }
        }
    }
]
""".lf()

    val apoTuned = """# Written by Ruru. Changes are overwritten.
Preamp: -2.9 dB
Filter 1: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
Filter 2: ON PK Fc 5032 Hz Gain 2.8 dB Q 3.69
Filter 3: ON PK Fc 156 Hz Gain 1.5 dB Q 1.61
Filter 4: ON PK Fc 94 Hz Gain -2.4 dB Q 2.32
Filter 5: ON PK Fc 2373 Hz Gain -2.8 dB Q 5.58
Filter 6: ON HSC Fc 10000 Hz Gain 2.7 dB Q 0.70
Filter 7: ON PK Fc 1633 Hz Gain 2.6 dB Q 3.51
Filter 8: ON PK Fc 491 Hz Gain -1.1 dB Q 2.50
Filter 9: ON PK Fc 2194 Hz Gain -1.0 dB Q 3.76
Filter 10: ON PK Fc 36 Hz Gain -0.5 dB Q 2.83
""".lf()

    val pwTuned = """# Written by Ruru. Changes are overwritten.
context.modules = [
    { name = libpipewire-module-filter-chain
        args = {
            node.description = "Ruru EQ"
            media.name = "Ruru EQ"
            filter.graph = {
                nodes = [
                    { type = builtin name = eq_pre label = linear control = { "Mult" = 0.716143 "Add" = 0.0 } }
                    { type = builtin name = eq_band_1 label = bq_lowshelf control = { "Freq" = 105.0 "Q" = 0.70 "Gain" = -3.0 } }
                    { type = builtin name = eq_band_2 label = bq_peaking control = { "Freq" = 5032.0 "Q" = 3.69 "Gain" = 2.8 } }
                    { type = builtin name = eq_band_3 label = bq_peaking control = { "Freq" = 156.0 "Q" = 1.61 "Gain" = 1.5 } }
                    { type = builtin name = eq_band_4 label = bq_peaking control = { "Freq" = 94.0 "Q" = 2.32 "Gain" = -2.4 } }
                    { type = builtin name = eq_band_5 label = bq_peaking control = { "Freq" = 2373.0 "Q" = 5.58 "Gain" = -2.8 } }
                    { type = builtin name = eq_band_6 label = bq_highshelf control = { "Freq" = 10000.0 "Q" = 0.70 "Gain" = 2.7 } }
                    { type = builtin name = eq_band_7 label = bq_peaking control = { "Freq" = 1633.0 "Q" = 3.51 "Gain" = 2.6 } }
                    { type = builtin name = eq_band_8 label = bq_peaking control = { "Freq" = 491.0 "Q" = 2.50 "Gain" = -1.1 } }
                    { type = builtin name = eq_band_9 label = bq_peaking control = { "Freq" = 2194.0 "Q" = 3.76 "Gain" = -1.0 } }
                    { type = builtin name = eq_band_10 label = bq_peaking control = { "Freq" = 36.0 "Q" = 2.83 "Gain" = -0.5 } }
                ]
                links = [
                    { output = "eq_pre:Out" input = "eq_band_1:In" }
                    { output = "eq_band_1:Out" input = "eq_band_2:In" }
                    { output = "eq_band_2:Out" input = "eq_band_3:In" }
                    { output = "eq_band_3:Out" input = "eq_band_4:In" }
                    { output = "eq_band_4:Out" input = "eq_band_5:In" }
                    { output = "eq_band_5:Out" input = "eq_band_6:In" }
                    { output = "eq_band_6:Out" input = "eq_band_7:In" }
                    { output = "eq_band_7:Out" input = "eq_band_8:In" }
                    { output = "eq_band_8:Out" input = "eq_band_9:In" }
                    { output = "eq_band_9:Out" input = "eq_band_10:In" }
                ]
            }
            audio.channels = 2
            audio.position = [ FL FR ]
            capture.props = {
                node.name = "ruru_eq"
                media.class = Audio/Sink
            }
            playback.props = {
                node.name = "ruru_eq_output"
                node.passive = true
                target.object = "bluez_output.XX.1"
            }
        }
    }
]
""".lf()

    val apoCustom = """# Written by Ruru. Changes are overwritten.
Preamp: -4.5 dB
Filter 1: ON LSC Fc 80 Hz Gain 4.5 dB Q 0.71
Filter 2: ON PK Fc 250 Hz Gain -2.0 dB Q 1.00
Filter 3: ON PK Fc 3000 Hz Gain 3.0 dB Q 2.50
Filter 4: ON HSC Fc 9000 Hz Gain 2.0 dB Q 0.71
""".lf()

    val pwCustom = """# Written by Ruru. Changes are overwritten.
context.modules = [
    { name = libpipewire-module-filter-chain
        args = {
            node.description = "Ruru EQ"
            media.name = "Ruru EQ"
            filter.graph = {
                nodes = [
                    { type = builtin name = eq_pre label = linear control = { "Mult" = 0.595662 "Add" = 0.0 } }
                    { type = builtin name = eq_band_1 label = bq_lowshelf control = { "Freq" = 80.0 "Q" = 0.71 "Gain" = 4.5 } }
                    { type = builtin name = eq_band_2 label = bq_peaking control = { "Freq" = 250.0 "Q" = 1.00 "Gain" = -2.0 } }
                    { type = builtin name = eq_band_3 label = bq_peaking control = { "Freq" = 3000.0 "Q" = 2.50 "Gain" = 3.0 } }
                    { type = builtin name = eq_band_4 label = bq_highshelf control = { "Freq" = 9000.0 "Q" = 0.71 "Gain" = 2.0 } }
                ]
                links = [
                    { output = "eq_pre:Out" input = "eq_band_1:In" }
                    { output = "eq_band_1:Out" input = "eq_band_2:In" }
                    { output = "eq_band_2:Out" input = "eq_band_3:In" }
                    { output = "eq_band_3:Out" input = "eq_band_4:In" }
                ]
            }
            audio.channels = 2
            audio.position = [ FL FR ]
            capture.props = {
                node.name = "ruru_eq"
                media.class = Audio/Sink
            }
            playback.props = {
                node.name = "ruru_eq_output"
                node.passive = true
                target.object = "bluez_output.XX.1"
            }
        }
    }
]
""".lf()

}

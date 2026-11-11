package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.bose.CurrentMode
import io.github.langkurst.ruru.protocol.bose.Eq
import io.github.langkurst.ruru.protocol.bose.EqBandId
import io.github.langkurst.ruru.protocol.bose.ModeConfig
import io.github.langkurst.ruru.protocol.bose.ModeConfigWrite
import io.github.langkurst.ruru.protocol.bose.ModeConfigs
import io.github.langkurst.ruru.protocol.bose.ModeLayout
import io.github.langkurst.ruru.protocol.bose.ModeReset
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.protocol.bose.StandbyTimer
import io.github.langkurst.ruru.protocol.toHex

/**
 * Careful write test on a real headset. Each step reads the current value, makes one change,
 * checks it, puts the original back and checks again. The run stops at the first step whose
 * original value could not be put back, and says what is left changed.
 */
class WriteTest(private val c: ProbeClient) {

    private class Stop(message: String) : Exception(message)

    fun run() {
        println("Write test: one change at a time, each checked and then put back.\n")
        val results = LinkedHashMap<String, String>()
        val steps = listOf<Pair<String, () -> String>>(
            "Headset EQ (bass +1)" to ::eqStep,
            "Auto-off timer" to ::autoOffStep,
            "Switch listening mode" to ::modeSwitchStep,
            "Save a custom mode" to ::customModeStep,
        )
        for ((name, step) in steps) {
            println("== $name")
            try {
                results[name] = step()
                println("   OK: ${results[name]}\n")
            } catch (e: Stop) {
                results[name] = "STOPPED: ${e.message}"
                println("   STOPPED: ${e.message}\n")
                break
            }
        }
        println("== Summary")
        results.forEach { (k, v) -> println("   $k: $v") }
        println("RESULT: ${results.size} of ${steps.size} steps run, ${c.framingErrors} framing errors")
    }

    private fun ok(reply: BmapPacket?, what: String): ByteArray {
        if (reply == null) throw Stop("$what: no reply")
        if (reply.operator == BmapOperator.ERROR) throw Stop("$what: error ${reply.errorCode} (${reply.error?.description})")
        return reply.payload
    }

    // ---- 1. Headset EQ: bass one step up, then back ----
    private fun eqStep(): String {
        val before = Eq.parse(ok(c.get(QcUltra2.EQ), "read EQ"))
        val bass = Eq.valueOf(before, EqBandId.BASS) ?: throw Stop("no bass band in the EQ reply")
        val target = if (bass < QcUltra2.EQ_MAX) bass + 1 else bass - 1
        println("   bass is $bass, setting $target")
        val changed = Eq.parse(ok(c.setGet(QcUltra2.EQ, Eq.buildBand(target, EqBandId.BASS)), "set bass"))
        if (Eq.valueOf(changed, EqBandId.BASS) != target) throw Stop("headset answered bass=${Eq.valueOf(changed, EqBandId.BASS)}, expected $target (bass may be left changed)")
        val reread = Eq.parse(ok(c.get(QcUltra2.EQ), "re-read EQ"))
        if (Eq.valueOf(reread, EqBandId.BASS) != target) throw Stop("re-read shows bass=${Eq.valueOf(reread, EqBandId.BASS)}")
        println("   changed and confirmed; putting $bass back")
        val restored = Eq.parse(ok(c.setGet(QcUltra2.EQ, Eq.buildBand(bass, EqBandId.BASS)), "restore bass"))
        val final = Eq.parse(ok(c.get(QcUltra2.EQ), "final EQ read"))
        if (final.map { it.value } != before.map { it.value }) throw Stop("EQ now ${final.map { it.value }}, was ${before.map { it.value }}")
        return "SET_GET [value, band] works; reply carries all three bands (${restored.size}); restored to ${final.map { it.value }}"
    }

    // ---- 2. Auto-off timer: 3 h, then back (exercises the two-byte format) ----
    private fun autoOffStep(): String {
        val beforePayload = ok(c.get(QcUltra2.STANDBY_TIMER), "read auto-off")
        val before = StandbyTimer.parse(beforePayload) ?: throw Stop("unreadable auto-off ${beforePayload.toHex()}")
        val target = if (before != 180) 180 else 60
        println("   auto-off is $before min (raw ${beforePayload.toHex()}), setting $target")
        val r = ok(c.setGet(QcUltra2.STANDBY_TIMER, StandbyTimer.build(target)), "set auto-off")
        val now = StandbyTimer.parse(ok(c.get(QcUltra2.STANDBY_TIMER), "re-read auto-off"))
        if (now != target) throw Stop("auto-off reads $now after setting $target (reply ${r.toHex()}); was $before")
        println("   changed and confirmed; putting $before back with ${StandbyTimer.build(before).toHex()}")
        val back = c.setGet(QcUltra2.STANDBY_TIMER, StandbyTimer.build(before))
        var final = StandbyTimer.parse(ok(c.get(QcUltra2.STANDBY_TIMER), "final auto-off read"))
        var note = "two-byte write [lo, hi] works"
        if (final != before) {
            // Fallback: the three-byte form the headset itself reports, [lo, 0, hi].
            val alt = byteArrayOf((before and 0xFF).toByte(), 0, (before shr 8).toByte())
            println("   the [lo, hi] write gave $final (reply ${back?.toString()}); trying ${alt.toHex()}")
            c.setGet(QcUltra2.STANDBY_TIMER, alt)
            final = StandbyTimer.parse(ok(c.get(QcUltra2.STANDBY_TIMER), "auto-off after fallback"))
            if (final != before) throw Stop("auto-off left at $final min, was $before. Set it back in the Bose app or with the next Ruru build")
            note = "two-byte write needs the 3-byte form [lo, 0, hi]"
        }
        return "$note; restored to $final min"
    }

    // ---- 3. Listening mode: switch to another preset, then back ----
    private fun modeSwitchStep(): String {
        val before = CurrentMode.parse(ok(c.get(QcUltra2.CURRENT_MODE), "read current mode"))
            ?: throw Stop("unreadable current mode")
        if (before !in 0..9) return "skipped: current mode is $before (not a saved slot), nothing changed"
        val target = if (before == 1) 0 else 1
        println("   mode is $before, switching to $target")
        val r = c.start(QcUltra2.CURRENT_MODE, CurrentMode.startPayload(target))
        if (r == null || r.operator == BmapOperator.ERROR) throw Stop("switch refused: $r")
        c.drain(1_200).forEach { println("      <- $it   (after switch)") }
        val now = CurrentMode.parse(ok(c.get(QcUltra2.CURRENT_MODE), "re-read mode"))
        if (now != target) throw Stop("mode reads $now after switching to $target; was $before")
        println("   switched and confirmed; switching back to $before")
        val back = c.start(QcUltra2.CURRENT_MODE, CurrentMode.startPayload(before))
        c.drain(1_200).forEach { println("      <- $it   (after switch back)") }
        val final = CurrentMode.parse(ok(c.get(QcUltra2.CURRENT_MODE), "final mode read"))
        if (final != before) throw Stop("mode left at $final, was $before. Press the headset's mode button or pick it in the Bose app")
        return "START [index, 0] answered ${r.operator}, back answered ${back?.operator}; restored to mode $final"
    }

    // ---- 4. Custom mode: earshot's 40-byte write, then the 47-byte unit's 39-byte write, then reset ----
    private fun customModeStep(): String {
        val slot = 9
        val origPayload = ok(c.get(QcUltra2.MODE_CONFIG, ModeConfigs.getPayload(slot)), "read slot $slot")
        val orig = ModeConfigs.parse(origPayload) ?: throw Stop("unreadable slot $slot")
        if (orig.editable != true || orig.name != QcUltra2.EMPTY_SLOT_NAME) {
            return "skipped: slot $slot holds '${orig.name}', so it is yours and was not touched"
        }
        val layout = ModeLayout.ofStatus(origPayload.size) ?: throw Stop("unknown slot layout (${origPayload.size} bytes)")
        val favBefore = ok(c.get(QcUltra2.FAVORITES), "read favourites")
        val editable = 3..9
        val test = ModeConfigWrite(index = slot, name = "Ruru test", cncWire = 5, spatialCode = 0,
            windBlock = false, ancEnabled = true, promptBytes = 0 to 0)
        val findings = ArrayList<String>()

        // a) The write earshot sends today (STANDARD, 40 bytes). Expected to be refused on a 47-byte unit.
        val wrong = test.build(ModeLayout.STANDARD, editable)
        println("   a) earshot's ${wrong.size}-byte write")
        val ra = c.setGet(QcUltra2.MODE_CONFIG, wrong)
        findings += "40-byte write: ${describe(ra)}"
        if (ra != null && ra.operator != BmapOperator.ERROR) println("      accepted, so the reset below also cleans this up")

        // b) The write for this layout (COMPACT, 39 bytes).
        val right = test.build(layout, editable)
        println("   b) ${layout.name} ${right.size}-byte write")
        val rb = c.setGet(QcUltra2.MODE_CONFIG, right)
        findings += "${right.size}-byte write: ${describe(rb)}"
        val written = ModeConfigs.parse(ok(c.get(QcUltra2.MODE_CONFIG, ModeConfigs.getPayload(slot)), "re-read slot $slot"))
        val saved = written?.name == "Ruru test" && written.cncWire == 5
        findings += "slot after write: ${written?.let { "'${it.name}' cnc=${it.cncWire} configured=${it.configured}" }}"

        // c) Put the slot back: the Bose app's reset first, then the original values if needed.
        println("   c) reset slot $slot")
        val rc = c.start(QcUltra2.MODES_RESET, ModeReset.startPayload(slot))
        findings += "reset: ${describe(rc)}"
        var after = ModeConfigs.parse(ok(c.get(QcUltra2.MODE_CONFIG, ModeConfigs.getPayload(slot)), "read slot after reset"))
        if (!matches(after, orig)) {
            println("      reset left ${after?.name}/${after?.cncWire}; writing the original values back")
            c.setGet(QcUltra2.MODE_CONFIG, ModeConfigWrite.from(orig).build(layout, editable))
            after = ModeConfigs.parse(ok(c.get(QcUltra2.MODE_CONFIG, ModeConfigs.getPayload(slot)), "read slot after rewrite"))
            findings += "restored by rewriting the original values"
        }
        val favAfter = ok(c.get(QcUltra2.FAVORITES), "re-read favourites")
        if (!favAfter.contentEquals(favBefore)) {
            println("      favourites changed (${favBefore.toHex()} -> ${favAfter.toHex()}); putting them back")
            c.setGet(QcUltra2.FAVORITES, favBefore)
            findings += "favourites restored"
        }
        if (!matches(after, orig)) throw Stop("slot $slot left as '${after?.name}'. Delete the 'Ruru test' mode in the Bose app. Findings: $findings")
        if (!saved) throw Stop("the custom mode did not save. Slot is back to empty. Findings: $findings")
        return findings.joinToString("; ")
    }

    private fun matches(now: ModeConfig?, orig: ModeConfig) = now != null && now.name == orig.name &&
        now.cncWire == orig.cncWire && now.windBlock == orig.windBlock && now.spatialCode == orig.spatialCode &&
        now.promptBytes == orig.promptBytes && now.editable == orig.editable

    private fun describe(p: BmapPacket?): String = when {
        p == null -> "no reply"
        p.operator == BmapOperator.ERROR -> "refused, error ${p.errorCode} (${p.error?.description})"
        else -> "accepted (${p.operator}, ${p.payload.size} bytes)"
    }
}

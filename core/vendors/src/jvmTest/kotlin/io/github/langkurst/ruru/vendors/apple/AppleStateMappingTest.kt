package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.SupportTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleStateMappingTest {
    @Test fun `the capabilities say battery only and offer no control`() {
        val caps = AppleStateMapping.capabilities(headphones = false)
        assertTrue(caps.batteryOnly)
        assertFalse(caps.ownSettings)
        assertFalse(caps.listeningModes || caps.noiseCancelling || caps.editableModes || caps.favoriteModes || caps.modesOnHome)
        assertEquals(FormFactor.EARBUDS, caps.formFactor)
        assertEquals(FormFactor.HEADPHONES, AppleStateMapping.capabilities(headphones = true).formFactor)
        assertEquals(AppleStateMapping.BATTERY_ONLY_NOTE, caps.supportNote)
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(caps))
    }

    @Test fun `a connected state with an advert is Battery only with the batteries and the model name`() {
        val connected = HeadphoneState(connection = ConnectionState(status = ConnectionStatus.CONNECTED, deviceName = "AirPods Pro"))
        val s = AppleStateMapping.apply(connected, ContinuityAdvert.parse(AppleFixtures.PRO2_NOT_FLIPPED), "AirPods Pro", now = 5)
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(s))
        assertEquals("AirPods Pro (2nd generation)", s.info?.modelName)
        assertEquals(80, s.batteryPercent)
        assertTrue(s.modes.isEmpty() && s.vendorControls.isEmpty())
        assertEquals(5L, s.lastUpdatedMillis)
    }

    @Test fun `no advert yet still shows the Bluetooth name and no battery`() {
        val s = AppleStateMapping.apply(HeadphoneState(), null, "Powerbeats Pro", now = 1)
        assertEquals("Powerbeats Pro", s.info?.productName)
        assertNull(s.batteryPercent)
        assertTrue(s.capabilities.batteryOnly)
    }
}

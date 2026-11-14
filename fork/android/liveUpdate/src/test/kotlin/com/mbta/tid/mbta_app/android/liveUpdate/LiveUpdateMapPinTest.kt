package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.map.ColorPalette
import com.mbta.tid.mbta_app.map.StopFeaturesBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateMapPinTest {
    @Test
    fun `pin layer copies upstream's selected pin under the fork's ID`() = runBlocking {
        val layer = LiveUpdateMapPin.pinLayer("place-alfcl", ColorPalette.light)

        assertNotNull(layer)
        layer!!
        assertEquals(LiveUpdateMapPin.LAYER_ID, layer.id)
        assertEquals(StopFeaturesBuilder.stopSourceId, layer.source)
        assertNotNull(layer.iconImage)
        assertNotNull(layer.iconOffset)
        assertNotNull(layer.textField)
        // The selected-stop expressions name the destination station.
        assertTrue(layer.iconImage!!.toJsonString().contains("place-alfcl"))
    }
}

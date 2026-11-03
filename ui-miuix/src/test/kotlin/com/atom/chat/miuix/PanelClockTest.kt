package com.atom.chat.miuix

import com.atom.chat.miuix.ui.panelClockLabel
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelClockTest {
    @Test
    fun `panel time stays in a fixed zero padded 24 hour format`() {
        assertEquals("00:00", panelClockLabel(LocalTime.MIDNIGHT))
        assertEquals("09:05", panelClockLabel(LocalTime.of(9, 5, 59)))
        assertEquals("23:59", panelClockLabel(LocalTime.of(23, 59)))
    }
}

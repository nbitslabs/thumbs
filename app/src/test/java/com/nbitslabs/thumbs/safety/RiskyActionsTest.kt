package com.nbitslabs.thumbs.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiskyActionsTest {
    @Test fun `recognises risky controls`() {
        assertEquals("send", RiskyActions.match("Send"))
        assertEquals("pay now", RiskyActions.match("Pay now"))
        assertEquals("delete", RiskyActions.match("Delete conversation"))
        assertEquals("place order", RiskyActions.match("Place order · ₹499"))
        assertEquals("发送", RiskyActions.match("发送"))
        assertEquals("send", RiskyActions.match(listOf(null, "Message", "Send")))
    }

    @Test fun `ignores harmless labels and long content`() {
        assertNull(RiskyActions.match("Settings"))
        assertNull(RiskyActions.match("Sender details"))
        assertNull(RiskyActions.match("Posts"))
        assertNull(RiskyActions.match("Recall"))
        assertNull(RiskyActions.match("Can you send me the report from yesterday's meeting please?"))
        assertNull(RiskyActions.match(""))
        assertNull(RiskyActions.match(null as String?))
    }
}

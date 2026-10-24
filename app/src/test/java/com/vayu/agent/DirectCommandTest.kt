package com.vayu.agent

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DirectCommandTest {
    private fun decode(json: String) = decodeDirectAction(JsonParser.parseString(json).asJsonObject)

    @Test fun chromePackageReachesTheLauncherField() {
        val action = decode("""{"type":"open_app","app":"com.android.chrome"}""")
        assertEquals("OPEN_APP", action.action)
        assertEquals("com.android.chrome", action.app)
        assertEquals("", action.text)
    }

    @Test fun displayNameAlsoReachesTheLauncherField() {
        assertEquals("Chrome", decode("""{"type":"open_app","app":"Chrome"}""").app)
    }

    @Test fun typingRetainsTextAndCoordinates() {
        val action = decode("""{"type":"type_text","text":"Hello world","x":123,"y":456}""")
        assertEquals("TYPE", action.action)
        assertEquals("Hello world", action.text)
        assertEquals(123, action.x)
        assertEquals(456, action.y)
        assertEquals("", action.app)
    }

    @Test fun swipeRetainsDestinationAndDuration() {
        val action = decode("""{"type":"swipe","x":10,"y":20,"x2":30,"y2":40,"duration":650}""")
        assertEquals("SWIPE", action.action)
        assertEquals(30, action.x2)
        assertEquals(40, action.y2)
        assertEquals(650L, action.duration)
    }

    @Test fun navigationDoesNotNeedTextOrAnApp() {
        assertEquals("PRESS_HOME", decode("""{"type":"press_home"}""").action)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAppIsRejectedBeforeLaunching() {
        decode("""{"type":"open_app","app":" "}""")
    }
}

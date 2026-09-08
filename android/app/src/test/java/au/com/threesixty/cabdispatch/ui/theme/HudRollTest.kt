package au.com.threesixty.cabdispatch.ui.theme

import au.com.threesixty.cabdispatch.ui.theme.RollDirection.DOWN
import au.com.threesixty.cabdispatch.ui.theme.RollDirection.NONE
import au.com.threesixty.cabdispatch.ui.theme.RollDirection.UP
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain-JVM tests for [HudRoll] — the pure digit-diff behind `RollingMoneyText` (ui/theme/Hud.kt).
 * [HudRoll] is a standalone object precisely so this can run without Compose/Android on the
 * classpath.
 */
class HudRollTest {

    @Test
    fun `digit increase rolls up, decrease rolls down, same is none`() {
        assertEquals(UP, HudRoll.direction('4', '5'))
        assertEquals(DOWN, HudRoll.direction('5', '4'))
        assertEquals(NONE, HudRoll.direction('7', '7'))
    }

    @Test
    fun `non-digit targets never roll`() {
        assertEquals(NONE, HudRoll.direction('$', '$'))
        assertEquals(NONE, HudRoll.direction('1', '.'))
        assertEquals(NONE, HudRoll.direction(null, '$'))
    }

    @Test
    fun `a digit appearing from nothing or from a non-digit rolls up`() {
        assertEquals(UP, HudRoll.direction(null, '1'))
        assertEquals(UP, HudRoll.direction('$', '1'))
    }

    @Test
    fun `plan compares slots right-aligned so cents keep their columns`() {
        // "$9.95" -> "$10.05": slots from the right are 5/0/./0/1/$ vs 5/9/./9/$
        val plan = HudRoll.plan(previous = "\$9.95", current = "\$10.05")
        assertEquals(listOf(NONE, UP, DOWN, NONE, DOWN, NONE), plan)
        //             '$'   '1'  '0'   '.'   '0'   '5'
    }

    @Test
    fun `plan with no previous rolls every digit up and leaves symbols alone`() {
        assertEquals(listOf(NONE, UP, UP, NONE, UP, UP), HudRoll.plan(null, "\$18.65"))
    }

    @Test
    fun `plan on an unchanged string is all none`() {
        assertEquals(List(6) { NONE }, HudRoll.plan("\$18.65", "\$18.65"))
    }

    @Test
    fun `plan handles a shrinking string`() {
        // "$10.05" -> "$9.95": '$' vs '1' -> NONE (non-digit target), '9' vs '0' -> UP, '.', '9' vs '0' -> UP, '5' same
        assertEquals(listOf(NONE, UP, NONE, UP, NONE), HudRoll.plan("\$10.05", "\$9.95"))
    }
}

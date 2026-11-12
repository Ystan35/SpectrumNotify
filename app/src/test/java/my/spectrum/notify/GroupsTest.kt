package my.spectrum.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which attendance pages belong to another Occ (ticket SN-J6ACW). Real names from the tickets. */
class GroupsTest {
    private val occ6 = setOf("occ6")

    @Test fun otherOccIsForeign() = assertTrue(Groups.isForeign("Attendance OCC13, 15 - Lecture", occ6))

    @Test fun yourOccInAListIsNotForeign() =
        assertFalse(Groups.isForeign("Attendance (Lecture: OCC 4, 5, 6, 9, 11, 12, 14)", occ6))

    @Test fun unknownOccHidesNothing() = assertFalse(Groups.isForeign("Attendance OCC13, 15 - Lecture", emptySet()))

    @Test fun timeRangeIsNotAGroup() = assertFalse(Groups.isForeign("Tutorial 11-1pm", occ6))

    @Test fun occ6AIsADifferentGroupFromOcc6() = assertTrue(Groups.isForeign("Attendance OCC 6A", occ6))
}

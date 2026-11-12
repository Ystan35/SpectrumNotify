package my.spectrum.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Oli reads out of "do this for me" sentences, and what it must leave as plain questions. */
class OliActionsTest {
    @Test fun teamsLinkIsAMeetingLink() {
        val a = OliActions.linkAsk("use this link for wix1003 tutorials https://teams.microsoft.com/l/meetup-join/abc123")!!
        assertEquals(OliActions.LinkKind.MEETING, a.kind)
        assertEquals("https://teams.microsoft.com/l/meetup-join/abc123", a.url)
    }

    @Test fun attendancePageIsRecognised() =
        assertEquals(OliActions.LinkKind.ATTENDANCE,
            OliActions.linkAsk("set https://spectrum.um.edu.my/mod/attendance/view.php?id=1127746 as the attendance for cso")!!.kind)

    @Test fun otherLinkIsUnknownSoOliAsks() =
        assertEquals(OliActions.LinkKind.UNKNOWN, OliActions.linkAsk("save https://example.com/room for fop")!!.kind)

    @Test fun trailingPunctuationIsNotPartOfTheLink() =
        assertEquals("https://meet.google.com/abc-defg-hij", OliActions.linkAsk("use https://meet.google.com/abc-defg-hij.")!!.url)

    @Test fun aQuestionAboutALinkChangesNothing() = assertNull(OliActions.linkAsk("give me the maya link"))
    @Test fun aLinkWithoutAChangeWordChangesNothing() = assertNull(OliActions.linkAsk("is https://teams.microsoft.com/l/meetup-join/abc ok"))

    @Test fun occIsRead() = assertEquals("2", OliActions.occAsk("set my occ for gig1017 to occ 2"))
    @Test fun occWithoutSpaceAndLetter() = assertEquals("6a", OliActions.occAsk("I'm in occ6a for wix1003"))
    @Test fun aQuestionAboutAnOccChangesNothing() = assertNull(OliActions.occAsk("which classes are in occ 2"))
    @Test fun noOccNumberIsNull() = assertNull(OliActions.occAsk("set my occ"))
    @Test fun numberAfterToIsRead() = assertEquals("2", OliActions.occAsk("set my occ for gig1017 to 2"))
    @Test fun numberAfterToWithOccWord() = assertEquals("12a", OliActions.occAsk("change my occ for wix1003 to occ 12a."))
    @Test fun aNumberWithoutTheOccWordIsNotAnOcc() = assertNull(OliActions.occAsk("set my reminder for gig1017 to 2"))

    @Test fun plainHttpIsNotAccepted() = assertNull(OliActions.linkAsk("use http://example.com/room for fop"))
    @Test fun aQuestionWithALinkChangesNothing() = assertNull(OliActions.linkAsk("can I use https://spectrum.um.edu.my on my laptop?"))
    @Test fun aQuestionAboutAnOccChangesNothing2() = assertNull(OliActions.occAsk("should I change from occ 1 to occ 2 for gig1017"))
    @Test fun whereQuestionWithOccIsNotARequest() = assertNull(OliActions.occAsk("i'm in occ 3, where is my tutorial?"))
    @Test fun modeChangeIsNotAnOccChange() = assertNull(OliActions.occAsk("set gig1017 occ 2 tutorial to online"))
    @Test fun myOccAloneIsNotAVerb() = assertNull(OliActions.occAsk("my occ 2 tutorial is when"))
    @Test fun urlsAreRemovedBeforeReadingTheCourse() = assertEquals("use   for cso", OliActions.withoutUrls("use https://zoom.us/j/abc for cso"))
}

package my.spectrum.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The reminder and attendance-link rules that went wrong before (the 14 Nov reminder, ticket SN-UDTQV). */
class RulesTest {
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = ZonedDateTime.of(2026, 11, 11, 18, 0, 0, 0, zone)
    private val dec14 = ZonedDateTime.of(2026, 11, 14, 14, 0, 0, 0, zone)

    // ---- "remind me about X on 14 november at 2pm, 2 days before"
    @Test fun daysBeforeIsMinutes() =
        assertEquals(2 * 24 * 60, ReminderRules.offsetMinutes("remind me about dev fest on 14 november at 2pm 2 days before"))

    @Test fun hoursBeforeIsMinutes() = assertEquals(180, ReminderRules.offsetMinutes("alert 3 hours before"))
    @Test fun anHourBefore() = assertEquals(60, ReminderRules.offsetMinutes("an hour before"))
    @Test fun noOffsetIsNull() = assertNull(ReminderRules.offsetMinutes("remind me tomorrow at 9am"))

    @Test fun alertStillAheadIsKept() = assertEquals(2880, ReminderRules.usableOffset(dec14, 2880, now))

    // event tomorrow, "2 days before" would be yesterday: the scheduler never sends it, so it must not be promised
    @Test fun alertAlreadyPastIsDropped() =
        assertEquals(0, ReminderRules.usableOffset(ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, zone), 2880, now))

    @Test fun noOffsetStaysZero() = assertEquals(0, ReminderRules.usableOffset(dec14, 0, now))

    // the scheduler skips an alert at or before now, so exactly now must not be promised either
    @Test fun alertExactlyNowIsDropped() = assertEquals(0, ReminderRules.usableOffset(now.plusMinutes(60), 60, now))

    // ---- the attendance page id in a link (used to keep pre-pin picks as pins)
    @Test fun attendanceIdIsRead() = assertEquals(1127746L, Links.attendanceCmid("https://spectrum.um.edu.my/mod/attendance/view.php?id=1127746"))
    @Test fun otherLinkHasNoAttendanceId() = assertNull(Links.attendanceCmid("https://teams.microsoft.com/l/meetup-join/abc"))

    // ---- the day word, wherever it stands (the model sometimes tags no date)
    @Test fun tomorrowInTheMiddleIsFound() = assertEquals("tomorrow", ReminderRules.dayWord("remind me tomorrow in 12"))
    @Test fun dayAfterTomorrowBeatsTomorrow() = assertEquals("the day after tomorrow", ReminderRules.dayWord("remind me the day after tomorrow at 9"))
    @Test fun weekdayIsFound() = assertEquals("friday", ReminderRules.dayWord("remind me on friday 3pm to call mum"))
    @Test fun nextWeekdayIsFound() = assertEquals("next monday", ReminderRules.dayWord("remind me next monday at 8"))
    @Test fun wordsThatMerelyStartLikeADayAreNotDays() = assertNull(ReminderRules.dayWord("remind me to monitor the lab at 3pm"))
    @Test fun noDayWordIsNull() = assertNull(ReminderRules.dayWord("remind me to submit the report at 9pm"))

    // ---- "tomorrow" after midnight, and a bare "12"
    @Test fun hoursAfterMidnightAreLateNight() {
        assertEquals(true, ReminderRules.lateNight(0)); assertEquals(true, ReminderRules.lateNight(4))
        assertEquals(false, ReminderRules.lateNight(5)); assertEquals(false, ReminderRules.lateNight(23))
    }
    @Test fun bareTwelveIsAsked() = assertEquals(true, ReminderRules.bareTwelve("remind me tomorrow in 12", false))
    @Test fun twelvePmIsNotAsked() = assertEquals(false, ReminderRules.bareTwelve("remind me tomorrow at 12pm", true))
    @Test fun twelveNovemberIsADateNotATime() = assertEquals(false, ReminderRules.bareTwelve("remind me on 12 november at 9am", true))
    @Test fun twelveNovemberWithoutClockIsADate() = assertEquals(false, ReminderRules.bareTwelve("remind me 12 november", false))
    @Test fun otherHoursAreNotAsked() = assertEquals(false, ReminderRules.bareTwelve("remind me tomorrow at 9", false))
    @Test fun twelveIsWrittenOut() = assertEquals("remind me tomorrow in 12am", ReminderRules.withTwelve("remind me tomorrow in 12", "12am"))

    // ---- which saved attendance link counts
    private val auto = "https://spectrum/auto"

    @Test fun pinnedLinkBeatsAuto() = assertEquals("https://mine", Links.pickAttendance("", "https://mine", true) { auto })
    @Test fun pastedLinkLosesToAuto() = assertEquals("", Links.pickAttendance("", "https://mine", false) { auto })
    @Test fun pastedLinkUsedWhenNoAuto() = assertEquals("https://mine", Links.pickAttendance("", "https://mine", false) { "" })
    @Test fun lectureOrTutorialPickAlwaysCounts() =
        assertEquals("https://lec", Links.pickAttendance("https://lec", "https://mine", false) { auto })

    @Test fun severalPastedLinesUseTheFirst() = assertEquals("https://a", Links.pickAttendance("", "https://a\nhttps://b", true) { auto })
    @Test fun blankLinesBeforeTheLinkAreSkipped() = assertEquals("https://a", Links.pickAttendance("", "\n  https://a \nhttps://b", true) { auto })
    @Test fun nothingSavedIsEmpty() = assertEquals("", Links.pickAttendance("", "", false) { auto })
}

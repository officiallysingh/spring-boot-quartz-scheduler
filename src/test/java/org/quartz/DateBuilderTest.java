package org.quartz;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.quartz.DateBuilder.JULY;
import static org.quartz.DateBuilder.JUNE;
import static org.quartz.DateBuilder.dateOf;
import static org.quartz.DateBuilder.futureDate;
import static org.quartz.DateBuilder.newDate;
import static org.quartz.DateBuilder.todayAt;
import static org.quartz.DateBuilder.translateTime;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.DateBuilder.IntervalUnit;

/**
 * Every helper takes the clock and the zone explicitly, so none of these tests depends on the JVM
 * default zone or on the system clock.
 */
class DateBuilderTest {

  private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");

  private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata"); // +05:30: half-hour offset

  private static Clock fixed(String instant, ZoneId zone) {
    return Clock.fixed(Instant.parse(instant), zone);
  }

  private static Clock fixedUtc(String instant) {
    return fixed(instant, ZoneOffset.UTC);
  }

  @Test
  void testJustInstantiatedBuilderShouldReturnSameInstantTruncatedToSeconds() {
    var builder = newDate(fixedUtc("2007-12-03T10:15:30.999Z"), ZoneOffset.UTC);

    assertEquals(Instant.parse("2007-12-03T10:15:30.000Z"), builder.build());
  }

  @Test
  void testBuilderFillsTheMissingFieldsFromTheClockInTheGivenZone() {
    // 23:30 UTC on the 3rd is already the 4th in Kolkata
    var builder = newDate(fixedUtc("2007-12-03T23:30:15Z"), KOLKATA).atHourMinuteAndSecond(1, 2, 3);

    assertEquals(ZonedDateTime.of(2007, 12, 4, 1, 2, 3, 0, KOLKATA).toInstant(), builder.build());
  }

  @Test
  void testBuilder() {
    var clock = fixedUtc("2007-12-03T10:15:30.000Z");
    var expected = ZonedDateTime.of(2013, 07, 1, 10, 30, 0, 0, ZoneOffset.UTC).toInstant();

    var bd1 =
        newDate(clock, ZoneOffset.UTC)
            .inYear(2013)
            .inMonth(JULY)
            .onDay(1)
            .atHourOfDay(10)
            .atMinute(30)
            .atSecond(0)
            .build();
    var bd2 =
        newDate(clock, ZoneOffset.UTC)
            .inYear(2013)
            .inMonthOnDay(JULY, 1)
            .atHourMinuteAndSecond(10, 30, 0)
            .build();

    assertAll(
        "dateBuilderUtc", () -> assertEquals(expected, bd1), () -> assertEquals(expected, bd2));
  }

  @Test
  void testBuilderWithTimeZone() {
    ZoneId tz = ZoneId.of("GMT-04:00");
    var expected = Instant.parse("2013-06-01T14:33:12.000Z");

    var bd1 =
        newDate(fixedUtc("2007-12-03T10:15:30.000Z"), tz)
            .inYear(2013)
            .inMonth(JUNE)
            .onDay(1)
            .atHourOfDay(10)
            .atMinute(33)
            .atSecond(12)
            .build();

    assertEquals(expected, bd1);
  }

  @Test
  void testBuilderRejectsNulls() {
    assertThrows(NullPointerException.class, () -> newDate(null, ZoneOffset.UTC));
    assertThrows(NullPointerException.class, () -> newDate(Clock.systemUTC(), null));
  }

  /*
   * futureDate tests
   */

  private static Stream<Arguments> futureDateTestData() {
    return Stream.of(
        Arguments.of(1, IntervalUnit.MILLISECOND, "2007-12-03T10:15:30.001Z"),
        Arguments.of(1, IntervalUnit.SECOND, "2007-12-03T10:15:31.000Z"),
        Arguments.of(1, IntervalUnit.MINUTE, "2007-12-03T10:16:30.000Z"),
        Arguments.of(1, IntervalUnit.HOUR, "2007-12-03T11:15:30.000Z"),
        Arguments.of(1, IntervalUnit.DAY, "2007-12-04T10:15:30.000Z"),
        Arguments.of(1, IntervalUnit.WEEK, "2007-12-10T10:15:30.000Z"),
        Arguments.of(1, IntervalUnit.MONTH, "2008-01-03T10:15:30.000Z"),
        Arguments.of(1, IntervalUnit.YEAR, "2008-12-03T10:15:30.000Z"));
  }

  @ParameterizedTest
  @MethodSource("futureDateTestData")
  void testFutureDate(int interval, IntervalUnit unit, String expected) {
    var clock = fixedUtc("2007-12-03T10:15:30.000Z");

    assertEquals(Instant.parse(expected), futureDate(interval, unit, clock, ZoneOffset.UTC));
  }

  @ParameterizedTest
  @MethodSource("futureDateTestData")
  void testFutureDateCountsInTheGivenZoneNotTheClocksZone(
      int interval, IntervalUnit unit, String expected) {
    // outside daylight-saving changes a fixed offset gives the same instants as UTC
    var clock = fixed("2007-12-03T10:15:30.000Z", VIENNA);

    assertEquals(
        Instant.parse(expected),
        futureDate(interval, unit, clock, ZoneOffset.ofHoursMinutes(3, 15)));
  }

  @Test
  void testFutureDateOneDayAcrossADaylightSavingChangeIsTwentyThreeHours() {
    var clock = fixedUtc("2024-03-30T11:00:00Z"); // 12:00 in Vienna, the day before the change

    assertEquals(
        Instant.parse("2024-03-31T10:00:00Z"), // 12:00 Vienna summer time
        futureDate(1, IntervalUnit.DAY, clock, VIENNA));
    assertEquals(
        Instant.parse("2024-03-31T11:00:00Z"),
        futureDate(1, IntervalUnit.DAY, clock, ZoneOffset.UTC));
  }

  /*
   * tomorrowAt tests
   */

  private static Stream<Arguments> tomorrowAtTestData() {
    return Stream.of(
        Arguments.of("2024-10-25T23:59:30.999Z", ZoneOffset.UTC, "2024-10-26T02:30:20.000Z"),
        Arguments.of(
            "2024-03-30T23:59:59.999+01:00",
            VIENNA,
            "2024-03-31T03:30:20.000+02:00"), // DST change skipped one hour
        Arguments.of(
            "2024-10-26T23:59:59.999+02:00",
            VIENNA,
            "2024-10-27T02:30:20.000+01:00")); // DST change: 02:30 exists twice, the later one
  }

  @ParameterizedTest
  @MethodSource("tomorrowAtTestData")
  void testTomorrowAt(String input, ZoneId zoneId, String expected) {
    var instant = ZonedDateTime.parse(input).toInstant();
    // the clock's own zone is not used
    var clock = Clock.fixed(instant, ZoneId.of("Pacific/Auckland"));
    var tomorrow = DateBuilder.tomorrowAt(2, 30, 20, clock, zoneId);

    assertEquals(ZonedDateTime.parse(expected).toInstant(), tomorrow);
  }

  /*
   * todayAt tests
   */

  private static Stream<Arguments> todayAtInvalidValuesTestData() {
    return Stream.of(
        Arguments.of(-1, 0, 0),
        Arguments.of(24, 0, 0),
        Arguments.of(0, -1, 0),
        Arguments.of(0, 60, 0),
        Arguments.of(0, 0, -1),
        Arguments.of(0, 0, 60));
  }

  @ParameterizedTest
  @MethodSource("todayAtInvalidValuesTestData")
  void testTodayAtInvalidValues(int hour, int minute, int second) {
    assertThrows(
        DateTimeException.class,
        () -> todayAt(hour, minute, second, Clock.systemUTC(), ZoneOffset.UTC));
  }

  @Test
  void testTodayAt() {
    var clock = fixed("2007-12-03T08:13:54.341Z", ZoneId.of("Pacific/Auckland"));
    var date1 = DateBuilder.todayAt(1, 1, 1, clock, ZoneOffset.UTC);
    var date2 = DateBuilder.todayAt(1, 1, 1, clock, VIENNA);
    var dstSkippedHour =
        DateBuilder.todayAt(
            2, 30, 17, fixedUtc("2024-03-31T08:13:54.341Z"), VIENNA); // DST change => skipped hour

    assertAll(
        "todayAt",
        () -> assertNotEquals(date1, date2),
        () -> assertEquals(Instant.parse("2007-12-03T01:01:01.000Z"), date1),
        () -> assertEquals(Instant.parse("2007-12-03T00:01:01.000Z"), date2),
        () ->
            assertEquals(
                ZonedDateTime.parse("2024-03-31T03:30:17+02:00").toInstant(), dstSkippedHour));
  }

  /*
   * dateOf tests
   */

  private static Stream<Arguments> testDateOf3PWithInvalidParametersTestData() {
    return todayAtInvalidValuesTestData();
  }

  @ParameterizedTest
  @MethodSource("testDateOf3PWithInvalidParametersTestData")
  void testDateOf3PWithInvalidParameters(int hour, int minute, int second) {
    assertThrows(
        DateTimeException.class,
        () -> dateOf(hour, minute, second, Clock.systemUTC(), ZoneOffset.UTC));
  }

  @Test
  void testDateOf3P() {
    var clock = fixedUtc("2007-12-03T08:13:54.341Z");
    var date1 = DateBuilder.dateOf(1, 1, 1, clock, ZoneOffset.UTC);
    var date2 = DateBuilder.dateOf(1, 1, 1, clock, VIENNA);
    assertAll(
        "dateOf3P",
        () -> assertNotEquals(date1, date2),
        () -> assertEquals(Instant.parse("2007-12-03T01:01:01.000Z"), date1),
        () -> assertEquals(Instant.parse("2007-12-03T00:01:01.000Z"), date2));
  }

  private static Stream<Arguments> testDateOf5PWithInvalidParametersTestData() {
    return Stream.of(
        Arguments.of(-1, 0, 0, 1, 1),
        Arguments.of(24, 0, 0, 1, 1),
        Arguments.of(0, -1, 0, 1, 1),
        Arguments.of(0, 60, 0, 1, 1),
        Arguments.of(0, 0, -1, 1, 1),
        Arguments.of(0, 0, 60, 1, 1),
        Arguments.of(0, 0, 0, 0, 1),
        Arguments.of(0, 0, 0, 32, 1),
        Arguments.of(0, 0, 0, 1, 0),
        Arguments.of(0, 0, 0, 1, 13));
  }

  @ParameterizedTest
  @MethodSource("testDateOf5PWithInvalidParametersTestData")
  void testDateOf5PWithInvalidParameters(
      int hour, int minute, int second, int dayOfMonth, int month) {
    assertThrows(
        DateTimeException.class,
        () -> dateOf(hour, minute, second, dayOfMonth, month, Clock.systemUTC(), ZoneOffset.UTC));
  }

  @Test
  void testDateOf5P() {
    var clock = fixedUtc("2007-12-03T08:13:54.341Z");
    var date1 = DateBuilder.dateOf(1, 1, 1, 1, 1, clock, ZoneOffset.UTC);
    var date2 = DateBuilder.dateOf(1, 1, 1, 1, 1, clock, VIENNA);
    assertAll(
        "dateOf5P",
        () -> assertNotEquals(date1, date2),
        () -> assertEquals(Instant.parse("2007-01-01T01:01:01.000Z"), date1),
        () -> assertEquals(Instant.parse("2007-01-01T00:01:01.000Z"), date2));
  }

  @Test
  void testDateOf5PTakesTheYearOfTheClockInTheGivenZone() {
    // 23:30 UTC on 31 December 2007 is already 2008 in Kolkata
    var clock = fixedUtc("2007-12-31T23:30:00Z");

    assertEquals(
        ZonedDateTime.of(2008, 6, 1, 1, 1, 1, 0, KOLKATA).toInstant(),
        DateBuilder.dateOf(1, 1, 1, 1, 6, clock, KOLKATA));
    assertEquals(
        Instant.parse("2007-06-01T01:01:01Z"),
        DateBuilder.dateOf(1, 1, 1, 1, 6, clock, ZoneOffset.UTC));
  }

  private static Stream<Arguments> testDateOf6PWithInvalidParametersTestData() {
    return Stream.of(
        Arguments.of(-1, 0, 0, 1, 1, 2024),
        Arguments.of(24, 0, 0, 1, 1, 2024),
        Arguments.of(0, -1, 0, 1, 1, 2024),
        Arguments.of(0, 60, 0, 1, 1, 2024),
        Arguments.of(0, 0, -1, 1, 1, 2024),
        Arguments.of(0, 0, 60, 1, 1, 2024),
        Arguments.of(0, 0, 0, 0, 1, 2024),
        Arguments.of(0, 0, 0, 32, 1, 2024),
        Arguments.of(0, 0, 0, 1, 0, 2024),
        Arguments.of(0, 0, 0, 1, 13, 2024),
        Arguments.of(0, 0, 0, 1, 1, -1_000_000_000),
        Arguments.of(0, 0, 0, 1, 1, 1_000_000_000));
  }

  @ParameterizedTest
  @MethodSource("testDateOf6PWithInvalidParametersTestData")
  void testDateOf6PWithInvalidParameters(
      int hour, int minute, int second, int dayOfMonth, int month, int year) {
    assertThrows(
        DateTimeException.class,
        () -> dateOf(hour, minute, second, dayOfMonth, month, year, ZoneOffset.UTC));
  }

  @Test
  void testDateOf6P() {
    var date1 = DateBuilder.dateOf(1, 1, 1, 1, 1, 2024, ZoneOffset.UTC);
    var date2 = DateBuilder.dateOf(1, 1, 1, 1, 1, 2024, VIENNA);
    assertAll(
        "dateOf6P",
        () -> assertNotEquals(date1, date2),
        () -> assertEquals(Instant.parse("2024-01-01T01:01:01.000Z"), date1),
        () -> assertEquals(Instant.parse("2024-01-01T00:01:01.000Z"), date2));
  }

  /*
   * evenHourDate tests
   */

  @Test
  void testEvenHourDateAfterNow() {
    var clock = fixedUtc("2007-12-03T08:13:54.341Z");
    assertEquals(
        Instant.parse("2007-12-03T09:00:00.000Z"),
        DateBuilder.evenHourDateAfterNow(clock, ZoneOffset.UTC));
  }

  @Test
  void testEvenHourDate() {
    assertEquals(
        Instant.parse("2007-12-03T09:00:00.000Z"),
        DateBuilder.evenHourDate(Instant.parse("2007-12-03T08:13:54.341Z"), ZoneOffset.UTC));
  }

  @Test
  void testEvenHourDateInAHalfHourOffsetZone() {
    // 08:13 UTC is 13:43 in Kolkata: the next even hour there is 14:00, i.e. 08:30 UTC
    assertEquals(
        Instant.parse("2007-12-03T08:30:00.000Z"),
        DateBuilder.evenHourDate(Instant.parse("2007-12-03T08:13:54.341Z"), KOLKATA));
    assertEquals(
        Instant.parse("2007-12-03T07:30:00.000Z"),
        DateBuilder.evenHourDateBefore(Instant.parse("2007-12-03T08:13:54.341Z"), KOLKATA));
  }

  @Test
  void testEvenHourDateBefore() {
    assertEquals(
        Instant.parse("2007-12-03T08:00:00.000Z"),
        DateBuilder.evenHourDateBefore(Instant.parse("2007-12-03T08:13:54.341Z"), ZoneOffset.UTC));
  }

  /*
   * evenMinuteDate tests
   */

  @Test
  void testEvenMinuteDateAfterNow() {
    var clock = fixedUtc("2007-12-03T08:13:54.341Z");
    assertEquals(
        Instant.parse("2007-12-03T08:14:00.000Z"), DateBuilder.evenMinuteDateAfterNow(clock));
  }

  @Test
  void testEvenMinuteDate() {
    assertEquals(
        Instant.parse("2007-12-03T08:14:00.000Z"),
        DateBuilder.evenMinuteDate(Instant.parse("2007-12-03T08:13:54.341Z")));
  }

  @Test
  void testEvenMinuteDateBefore() {
    assertEquals(
        Instant.parse("2007-12-03T08:13:00.000Z"),
        DateBuilder.evenMinuteDateBefore(Instant.parse("2007-12-03T08:13:54.341Z")));
  }

  /*
   * evenSecondDate tests
   */

  @Test
  void testEvenSecondDateAfterNow() {
    var clock = fixedUtc("2007-12-03T08:13:54.341Z");
    assertEquals(
        Instant.parse("2007-12-03T08:13:55.000Z"), DateBuilder.evenSecondDateAfterNow(clock));
  }

  @Test
  void testEvenSecondDateAfterNowDSTForward() {
    var clock =
        Clock.fixed(ZonedDateTime.parse("2024-03-31T01:59:59.999+01:00").toInstant(), VIENNA);

    var before = clock.instant();
    var result = DateBuilder.evenSecondDateAfterNow(clock);

    assertAll(
        "evenSecondDateAfterNowDSTForward",
        () ->
            assertEquals(ZonedDateTime.parse("2024-03-31T01:59:59.999+01:00").toInstant(), before),
        () -> assertEquals(ZonedDateTime.parse("2024-03-31T01:00:00.000Z").toInstant(), result),
        () ->
            assertEquals(ZonedDateTime.parse("2024-03-31T03:00:00.000+02:00").toInstant(), result));
  }

  @Test
  void testEvenSecondDateAfterNowDSTBackward() {
    var clock =
        Clock.fixed(ZonedDateTime.parse("2024-10-27T02:59:59.999+02:00").toInstant(), VIENNA);

    var before = clock.instant();
    var result = DateBuilder.evenSecondDateAfterNow(clock);

    assertAll(
        "evenSecondDateAfterNowDSTBackward",
        () ->
            assertEquals(ZonedDateTime.parse("2024-10-27T02:59:59.999+02:00").toInstant(), before),
        () -> assertEquals(ZonedDateTime.parse("2024-10-27T01:00:00.000Z").toInstant(), result),
        () ->
            assertEquals(ZonedDateTime.parse("2024-10-27T02:00:00.000+01:00").toInstant(), result));
  }

  @Test
  void testEvenSecondDate() {
    assertEquals(
        Instant.parse("2007-12-03T08:13:55.000Z"),
        DateBuilder.evenSecondDate(Instant.parse("2007-12-03T08:13:54.341Z")));
  }

  @Test
  void testEvenSecondDateBefore() {
    assertEquals(
        Instant.parse("2007-12-03T08:13:54.000Z"),
        DateBuilder.evenSecondDateBefore(Instant.parse("2007-12-03T08:13:54.341Z")));
  }

  /*
   * nextGivenMinuteDate tests
   */

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 60, Integer.MAX_VALUE})
  void testNextGivenMinuteDateWithInvalidMinuteBaseShouldThrow(int minuteBase) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DateBuilder.nextGivenMinuteDate(Instant.EPOCH, minuteBase, ZoneOffset.UTC));
  }

  private static Stream<Arguments> nextGivenMinuteDateTestData() {
    return Stream.of(
        Arguments.of("2007-12-03T11:16:41.123Z", 20, "2007-12-03T11:20:00.000Z"),
        Arguments.of("2007-12-03T11:36:41.123Z", 20, "2007-12-03T11:40:00.000Z"),
        Arguments.of("2007-12-03T11:46:41.123Z", 20, "2007-12-03T12:00:00.000Z"),
        Arguments.of("2007-12-03T11:26:41.123Z", 30, "2007-12-03T11:30:00.000Z"),
        Arguments.of("2007-12-03T11:36:41.123Z", 30, "2007-12-03T12:00:00.000Z"),
        Arguments.of("2007-12-03T11:16:41.123Z", 17, "2007-12-03T11:17:00.000Z"),
        Arguments.of("2007-12-03T11:17:41.123Z", 17, "2007-12-03T11:34:00.000Z"),
        Arguments.of("2007-12-03T11:52:41.123Z", 17, "2007-12-03T12:00:00.000Z"),
        Arguments.of("2007-12-03T11:52:41.123Z", 5, "2007-12-03T11:55:00.000Z"),
        Arguments.of("2007-12-03T11:57:41.123Z", 5, "2007-12-03T12:00:00.000Z"),
        Arguments.of("2007-12-03T11:17:41.123Z", 0, "2007-12-03T12:00:00.000Z"),
        Arguments.of("2007-12-03T11:17:41.123Z", 1, "2007-12-03T11:18:00.000Z"));
  }

  @ParameterizedTest
  @MethodSource("nextGivenMinuteDateTestData")
  void testNextGivenMinuteDate(String input, int minuteBase, String expected) {
    assertEquals(
        Instant.parse(expected),
        DateBuilder.nextGivenMinuteDate(Instant.parse(input), minuteBase, ZoneOffset.UTC));
  }

  @Test
  void testNextGivenMinuteDateCountsFromTheHourOfTheGivenZone() {
    // 11:17 UTC is 16:47 in Kolkata: the next multiple of 20 minutes there is 17:00 = 11:30 UTC
    assertEquals(
        Instant.parse("2007-12-03T11:30:00.000Z"),
        DateBuilder.nextGivenMinuteDate(Instant.parse("2007-12-03T11:17:41.123Z"), 20, KOLKATA));
  }

  /*
   * nextGivenSecondDate tests
   */

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 60, Integer.MAX_VALUE})
  void testNextGivenSecondDateWithInvalidSecondBaseShouldThrow(int secondBase) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DateBuilder.nextGivenSecondDate(Instant.EPOCH, secondBase));
  }

  private static Stream<Arguments> nextGivenSecondDateTestData() {
    return Stream.of(
        Arguments.of("2007-12-03T10:15:30.123Z", 0, "2007-12-03T10:16:00.000Z"),
        Arguments.of("2007-12-03T10:15:30.123Z", 1, "2007-12-03T10:15:31.000Z"),
        Arguments.of("2007-12-03T10:15:54.256Z", 13, "2007-12-03T10:16:00.000Z"),
        Arguments.of("2007-12-03T10:15:30.256Z", 13, "2007-12-03T10:15:39.000Z"),
        Arguments.of("2007-12-03T10:15:30.000Z", 59, "2007-12-03T10:15:59.000Z"));
  }

  @ParameterizedTest
  @MethodSource("nextGivenSecondDateTestData")
  void testNextGivenSecondDate(String input, int secondBase, String expected) {
    assertEquals(
        Instant.parse(expected), DateBuilder.nextGivenSecondDate(Instant.parse(input), secondBase));
  }

  /*
   * translateTime tests
   */

  @Test
  void testTranslate() {

    ZoneId tz1 = ZoneId.of("GMT-02:00");
    ZoneId tz2 = ZoneId.of("GMT-04:00");

    Instant vc = ZonedDateTime.of(2013, 6, 1, 10, 33, 12, 0, tz1).toInstant();

    assertEquals(12, translateTime(vc, tz1, tz2).atZone(tz1).getHour());

    vc = ZonedDateTime.of(2013, 6, 1, 10, 33, 12, 0, tz2).toInstant();

    assertEquals(8, translateTime(vc, tz2, tz1).atZone(tz2).getHour());
  }

  private static Stream<Arguments> translateTimeTestData() {
    return Stream.of(
        Arguments.of(
            "2013-06-01T10:33:12-02:00",
            ZoneId.of("GMT-02:00"),
            ZoneId.of("GMT-04:00"),
            "2013-06-01T12:33:12-02:00"),
        Arguments.of(
            "2013-06-01T10:33:12-04:00",
            ZoneId.of("GMT-04:00"),
            ZoneId.of("GMT-02:00"),
            "2013-06-01T08:33:12-04:00"));
  }

  @ParameterizedTest
  @MethodSource("translateTimeTestData")
  void testTranslateTime(String input, ZoneId src, ZoneId dest, String expected) {
    assertEquals(
        ZonedDateTime.parse(expected).toInstant(),
        translateTime(ZonedDateTime.parse(input).toInstant(), src, dest));
  }
}

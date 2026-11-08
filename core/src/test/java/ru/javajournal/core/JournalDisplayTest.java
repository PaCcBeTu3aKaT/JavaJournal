package ru.javajournal.core;

import org.junit.jupiter.api.Test;
import ru.javajournal.core.model.Lesson;
import ru.javajournal.core.service.JournalDisplay;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalDisplayTest {
    @Test void weekendsStartWithNextWeekButWeekdaysKeepCurrentWeek() {
        assertEquals(LocalDate.of(2026, 11, 9), JournalDisplay.startingWeekDate(LocalDate.of(2026, 11, 7)));
        assertEquals(LocalDate.of(2026, 11, 9), JournalDisplay.startingWeekDate(LocalDate.of(2026, 11, 8)));
        assertEquals(LocalDate.of(2026, 11, 9), JournalDisplay.startingWeekDate(LocalDate.of(2026, 11, 9)));
        assertEquals(LocalDate.of(2026, 11, 13), JournalDisplay.startingWeekDate(LocalDate.of(2026, 11, 13)));
    }

    @Test void russianPluralForms() {
        assertEquals("0 занятий", JournalDisplay.lessonCount(0));
        assertEquals("1 занятие", JournalDisplay.lessonCount(1));
        assertEquals("2 занятия", JournalDisplay.lessonCount(2));
        assertEquals("4 занятия", JournalDisplay.lessonCount(4));
        assertEquals("5 занятий", JournalDisplay.lessonCount(5));
        assertEquals("11 занятий", JournalDisplay.lessonCount(11));
        assertEquals("21 занятие", JournalDisplay.lessonCount(21));
        assertEquals("23 занятия", JournalDisplay.lessonCount(23));
        assertEquals("115 занятий", JournalDisplay.lessonCount(115));
    }

    @Test void nearestIsTodayNotTomorrowAndPrefersCurrent() {
        var today = LocalDate.of(2026, 11, 9);
        var old = lesson("old", today, "08:30", "09:30");
        var current = lesson("current", today, "10:00", "11:30");
        var future = lesson("future", today, "13:00", "14:00");
        var tomorrow = lesson("tomorrow", today.plusDays(1), "08:30", "09:30");
        var lessons = List.of(tomorrow, future, old, current);
        assertEquals("current", JournalDisplay.nearestTodayLesson(lessons, today, LocalTime.of(10, 45)).orElseThrow().id());
        assertTrue(JournalDisplay.lessonInProgress(current, LocalTime.of(10, 45)));
        assertEquals("future", JournalDisplay.nearestTodayLesson(lessons, today, LocalTime.of(12, 0)).orElseThrow().id());
        assertTrue(JournalDisplay.nearestTodayLesson(lessons, today, LocalTime.of(17, 0)).isEmpty());
    }

    private static Lesson lesson(String id, LocalDate date, String start, String end) {
        return new Lesson(id, date, "Java", start, end, "", "");
    }
}

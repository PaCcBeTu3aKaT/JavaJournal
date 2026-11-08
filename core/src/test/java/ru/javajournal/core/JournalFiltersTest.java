package ru.javajournal.core;

import org.junit.jupiter.api.Test;
import ru.javajournal.core.model.AttendanceEntry;
import ru.javajournal.core.model.Grade;
import ru.javajournal.core.model.Homework;
import ru.javajournal.core.model.HomeworkStatus;
import ru.javajournal.core.model.Profile;
import ru.javajournal.core.service.JournalFilters;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalFiltersTest {
    @Test void monthAndSchoolYearHaveDifferentBoundaries() {
        var date = LocalDate.of(2026, 11, 8);
        assertEquals(LocalDate.of(2026, 11, 1), JournalFilters.periodStart(date, JournalFilters.Period.month));
        assertEquals(LocalDate.of(2026, 9, 1), JournalFilters.periodStart(date, JournalFilters.Period.academicYear));
        assertEquals(LocalDate.of(2027, 9, 1), JournalFilters.periodEndExclusive(date, JournalFilters.Period.academicYear));
        assertFalse(JournalFilters.inPeriod(LocalDate.of(2026, 8, 31), date, JournalFilters.Period.academicYear));
    }

    @Test void monthlyGradesExcludeAdjacentMonths() {
        var date = LocalDate.of(2026, 11, 8);
        var grades = List.of(
            new Grade("1", "Java", 12, "", "", LocalDate.of(2026, 11, 3)),
            new Grade("2", "Java", 10, "", "", LocalDate.of(2026, 10, 31)));
        assertEquals(1, JournalFilters.grades(grades, date, JournalFilters.Period.month).size());
        assertEquals(2, JournalFilters.grades(grades, date, JournalFilters.Period.academicYear).size());
    }

    @Test void attendanceUsesDatedVisitRecordsNotGlobalAverage() {
        var date = LocalDate.of(2026, 11, 8);
        var visits = List.of(
            new AttendanceEntry(LocalDate.of(2026, 11, 3), "Java", true),
            new AttendanceEntry(LocalDate.of(2026, 11, 4), "Java", false),
            new AttendanceEntry(LocalDate.of(2026, 10, 30), "Java", true));
        var month = JournalFilters.attendance(visits, date, JournalFilters.Period.month);
        assertEquals(2, month.get(0).total());
        assertEquals(1, month.get(0).visited());
        var year = JournalFilters.attendance(visits, date, JournalFilters.Period.academicYear);
        assertEquals(3, year.get(0).total());
    }

    @Test void homeworkGroupsAndDeadlinesAreSorted() {
        var today = LocalDate.of(2026, 11, 8);
        var tasks = List.of(
            task("future", today.plusDays(10), HomeworkStatus.pending),
            task("review", today.plusDays(1), HomeworkStatus.review),
            task("near", today.plusDays(1), HomeworkStatus.pending),
            task("overdue", today.minusDays(1), HomeworkStatus.overdue),
            task("unspecified", null, HomeworkStatus.pending));
        var sorted = JournalFilters.sortedHomework(tasks);
        assertEquals(List.of("overdue", "near", "future", "unspecified", "review"),
            sorted.stream().map(Homework::id).toList());
        assertEquals(3, JournalFilters.groupedHomework(tasks).get(HomeworkStatus.pending).size());
    }

    @Test void descendingDeadlinesKeepUnspecifiedLast() {
        var today = LocalDate.of(2026, 11, 8);
        var items = List.of(task("nearest", today.plusDays(1), HomeworkStatus.pending),
            task("later", today.plusDays(5), HomeworkStatus.pending),
            task("unknown", null, HomeworkStatus.pending));
        assertEquals(List.of("later", "nearest", "unknown"), JournalFilters
            .sortedHomework(items, JournalFilters.HomeworkSort.dueDescending)
            .stream().map(Homework::id).toList());
    }

    @Test void subjectSortingGroupsTasksWithinStatus() {
        var date = LocalDate.of(2026, 11, 8);
        var items = List.of(
            new Homework("rus1", "Русский язык", "А", "", date.plusDays(4), "", HomeworkStatus.pending, null, ""),
            new Homework("java1", "Java", "Б", "", date.plusDays(2), "", HomeworkStatus.pending, null, ""),
            new Homework("rus2", "Русский язык", "В", "", date.plusDays(1), "", HomeworkStatus.pending, null, ""));
        var bySubject = JournalFilters.groupedBySubject(items);
        assertEquals(List.of("Java", "Русский язык"), List.copyOf(bySubject.keySet()));
        assertEquals(List.of("rus2", "rus1"), bySubject.get("Русский язык").stream().map(Homework::id).toList());
    }

    @Test void profileGreetingTakesGivenName() {
        assertEquals("Георгий", new Profile("Китаев Георгий", "IT-101", "", "").givenName());
        assertEquals("Алексей", new Profile("Алексей Демонстрационный", "Демо", "", "", "Алексей").givenName());
    }

    private static Homework task(String id, LocalDate due, HomeworkStatus status) {
        return new Homework(id, "Java", id, "", due, "", status, null, "");
    }
}

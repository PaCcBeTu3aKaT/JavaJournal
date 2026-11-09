package ru.javajournal.core.service;

import ru.javajournal.core.model.Attendance;
import ru.javajournal.core.model.AttendanceEntry;
import ru.javajournal.core.model.Grade;
import ru.javajournal.core.model.Homework;
import ru.javajournal.core.model.HomeworkStatus;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Чистые функции фильтрации: не делают сетевых запросов и подходят для Android. */
public final class JournalFilters {
    public enum Period { month, academicYear }
    public enum HomeworkSort {
        dueAscending("По дедлайну ↑"),
        dueDescending("По дедлайну ↓"),
        subject("По предметам");

        private final String label;
        HomeworkSort(String label) { this.label = label; }
        public String label() { return label; }
    }

    private JournalFilters() { }

    public static LocalDate periodStart(LocalDate reference, Period period) {
        if (period == Period.month) return reference.withDayOfMonth(1);
        int year = reference.getMonthValue() >= Month.SEPTEMBER.getValue()
            ? reference.getYear() : reference.getYear() - 1;
        return LocalDate.of(year, Month.SEPTEMBER, 1);
    }

    public static LocalDate periodEndExclusive(LocalDate reference, Period period) {
        LocalDate first = periodStart(reference, period);
        return period == Period.month ? first.plusMonths(1) : first.plusYears(1);
    }

    public static LocalDate shift(LocalDate reference, Period period, int steps) {
        return period == Period.month ? reference.plusMonths(steps) : reference.plusYears(steps);
    }

    public static boolean inPeriod(LocalDate date, LocalDate reference, Period period) {
        return date != null && !date.isBefore(periodStart(reference, period))
            && date.isBefore(periodEndExclusive(reference, period));
    }

    public static List<Grade> grades(List<Grade> grades, LocalDate reference, Period period) {
        return grades.stream().filter(grade -> inPeriod(grade.date(), reference, period))
            .sorted(Comparator.comparing(Grade::date).reversed()).collect(java.util.stream.Collectors.toList());
    }

    /** Формирует статистику только по занятиям в запрошенном периоде. */
    public static List<Attendance> attendance(List<AttendanceEntry> entries, LocalDate reference, Period period) {
        Map<String, int[]> result = new TreeMap<>();
        for (AttendanceEntry entry : entries) {
            if (!inPeriod(entry.date(), reference, period)) continue;
            int[] counts = result.computeIfAbsent(entry.subject(), subject -> new int[2]);
            counts[1]++;
            if (entry.visited()) counts[0]++;
        }
        List<Attendance> rows = new ArrayList<>();
        for (Map.Entry<String, int[]> row : result.entrySet()) {
            rows.add(new Attendance(row.getKey(), row.getValue()[0], row.getValue()[1]));
        }
        return List.copyOf(rows);
    }

    public static int homeworkOrder(HomeworkStatus status) {
        return switch (status) {
            case overdue -> 0;
            case pending -> 1;
            case review -> 2;
            case done -> 3;
            case deleted -> 4;
        };
    }

    /** Просроченные/активные/на проверке/проверенные остаются разными группами. */
    public static List<Homework> sortedHomework(List<Homework> homework) {
        return sortedHomework(homework, HomeworkSort.dueAscending);
    }

    public static List<Homework> sortedHomework(List<Homework> homework, HomeworkSort mode) {
        Comparator<LocalDate> dueOrder = mode == HomeworkSort.dueDescending
            ? Comparator.nullsLast(Comparator.reverseOrder())
            : Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<String> names = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<Homework> order = mode == HomeworkSort.subject
            ? Comparator.comparing(Homework::subject, names)
                .thenComparing(Homework::due, dueOrder)
                .thenComparing(Homework::title, names)
            : Comparator.comparing(Homework::due, dueOrder)
                .thenComparing(Homework::subject, names)
                .thenComparing(Homework::title, names);
        return homework.stream().sorted(Comparator
            .comparingInt((Homework task) -> homeworkOrder(task.status()))
            .thenComparing(order)).collect(java.util.stream.Collectors.toList());
    }

    public static Map<HomeworkStatus, List<Homework>> groupedHomework(List<Homework> homework) {
        return groupedHomework(homework, HomeworkSort.dueAscending);
    }

    public static Map<HomeworkStatus, List<Homework>> groupedHomework(List<Homework> homework, HomeworkSort mode) {
        Map<HomeworkStatus, List<Homework>> groups = new LinkedHashMap<>();
        for (Homework task : sortedHomework(homework, mode)) {
            groups.computeIfAbsent(task.status(), status -> new ArrayList<>()).add(task);
        }
        return groups;
    }

    /** Используется только при сортировке по предметам внутри каждого статуса. */
    public static Map<String, List<Homework>> groupedBySubject(List<Homework> homework) {
        Map<String, List<Homework>> groups = new LinkedHashMap<>();
        for (Homework task : sortedHomework(homework, HomeworkSort.subject)) {
            String subject = task.subject() == null || task.subject().isBlank() ? "Без предмета" : task.subject();
            groups.computeIfAbsent(subject, name -> new ArrayList<>()).add(task);
        }
        return groups;
    }
}

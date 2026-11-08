package ru.javajournal.core.service;

import ru.javajournal.core.model.Lesson;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Логика отображения, доступная также будущему мобильному интерфейсу. */
public final class JournalDisplay {
    private JournalDisplay() { }

    /** Суббота и воскресенье открывают неделю, начинающуюся в ближайший понедельник. */
    public static LocalDate startingWeekDate(LocalDate today) {
        DayOfWeek day = today.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
            ? today.plusDays(8 - day.getValue()) : today;
    }

    public static String lessonCount(int count) {
        int hundreds = Math.abs(count) % 100;
        int units = hundreds % 10;
        String noun = hundreds >= 11 && hundreds <= 14 ? "занятий"
            : units == 1 ? "занятие"
            : units >= 2 && units <= 4 ? "занятия" : "занятий";
        return count + " " + noun;
    }

    /** Только занятия за сегодня, которые ещё идут или начнутся позже. */
    public static Optional<Lesson> nearestTodayLesson(List<Lesson> lessons, LocalDate today, LocalTime now) {
        return lessons.stream()
            .filter(lesson -> today.equals(lesson.date()))
            .filter(lesson -> {
                LocalTime end = parseTime(lesson.end());
                if (end != null) return now.isBefore(end);
                LocalTime start = parseTime(lesson.start());
                return start != null && !now.isAfter(start);
            })
            .min(Comparator.comparing(lesson -> {
                LocalTime start = parseTime(lesson.start());
                return start == null ? LocalTime.MAX : start;
            }));
    }

    public static boolean lessonInProgress(Lesson lesson, LocalTime now) {
        LocalTime start = parseTime(lesson.start());
        LocalTime end = parseTime(lesson.end());
        return start != null && end != null && !now.isBefore(start) && now.isBefore(end);
    }

    private static LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) return null;
        String raw = time.trim();
        try { return LocalTime.parse(raw); }
        catch (DateTimeParseException ignored) { }
        try { return LocalTime.parse(raw, DateTimeFormatter.ofPattern("H:mm")); }
        catch (DateTimeParseException ignored) { return null; }
    }
}

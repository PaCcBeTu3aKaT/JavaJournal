package ru.javajournal.core.model;

import java.time.LocalDate;

/** Dated visit; lessonId helps match visit/mark to the right scheduled lesson. */
public record AttendanceEntry(LocalDate date, String subject, boolean visited,
                              AttendanceState state, String sourceCode, String lessonId) {
    public AttendanceEntry(LocalDate date, String subject, boolean visited) {
        this(date, subject, visited, visited ? AttendanceState.present : AttendanceState.absent, "", "");
    }
    public AttendanceEntry(LocalDate date, String subject, boolean visited,
                           AttendanceState state, String sourceCode) {
        this(date, subject, visited, state, sourceCode, "");
    }
}

package ru.javajournal.core.model;

import java.time.LocalDate;

/** Одно датированное занятие из API. */
public record AttendanceEntry(LocalDate date, String subject, boolean visited) { }

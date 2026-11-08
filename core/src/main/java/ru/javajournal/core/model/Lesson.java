package ru.javajournal.core.model;

import java.time.LocalDate;

public record Lesson(String id, LocalDate date, String subject, String start, String end,
                     String teacher, String room) { }

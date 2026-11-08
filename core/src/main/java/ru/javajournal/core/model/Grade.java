package ru.javajournal.core.model;

import java.time.LocalDate;

public record Grade(String id, String subject, int value, String type, String theme,
                    LocalDate date) { }

package ru.javajournal.core.model;

/** Attendance distinction from verified evidence, not a guessed numeric code. */
public enum AttendanceState {
    present, late, absent, unknown;

    public String label() {
        return switch (this) {
            case present -> "Присутствовал";
            case late -> "Опоздал";
            case absent -> "Отсутствовал";
            case unknown -> "Статус не уточнён";
        };
    }
}

package ru.javajournal.core.model;

public record Attendance(String subject, int visited, int total) {
    public int absent() { return Math.max(0, total - visited); }
    public double percent() { return total == 0 ? 0 : 100.0 * visited / total; }
}

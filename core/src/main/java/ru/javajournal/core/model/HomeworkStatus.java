package ru.javajournal.core.model;

public enum HomeworkStatus {
    pending("Активно"), review("На проверке"), done("Проверено"), overdue("Просрочено"), deleted("Удалено");
    private final String label;
    HomeworkStatus(String label) { this.label = label; }
    public String label() { return label; }
}

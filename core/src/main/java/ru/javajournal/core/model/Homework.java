package ru.javajournal.core.model;

import java.time.LocalDate;

public record Homework(String id, String subject, String title, String description,
                       LocalDate due, String teacher, HomeworkStatus status,
                       Integer grade, String teacherComment,
                       String teacherFileUrl, String studentFileUrl,
                       String teacherFileName, String studentFileName, String studentAnswer, int workType) {
    public Homework(String id, String subject, String title, String description,
                    LocalDate due, String teacher, HomeworkStatus status, Integer grade,
                    String teacherComment, String teacherFileUrl, String studentFileUrl,
                    String teacherFileName, String studentFileName, String studentAnswer) {
        this(id, subject, title, description, due, teacher, status, grade, teacherComment,
            teacherFileUrl, studentFileUrl, teacherFileName, studentFileName, studentAnswer, 0);
    }
    public Homework(String id, String subject, String title, String description,
                    LocalDate due, String teacher, HomeworkStatus status,
                    Integer grade, String teacherComment, String teacherFileUrl, String studentFileUrl) {
        this(id, subject, title, description, due, teacher, status, grade, teacherComment,
            teacherFileUrl, studentFileUrl, "", "", "");
    }

    // Старые конструкторы используются тестами и другими модулями.
    public Homework(String id, String subject, String title, String description,
                    LocalDate due, String teacher, HomeworkStatus status,
                    Integer grade, String teacherComment) {
        this(id, subject, title, description, due, teacher, status, grade, teacherComment, "", "", "", "", "");
    }
}

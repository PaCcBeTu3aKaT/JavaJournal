package ru.javajournal.core.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.javajournal.core.model.Homework;
import ru.javajournal.core.model.HomeworkStatus;

import static org.junit.jupiter.api.Assertions.*;

class HomeworkParsingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void submittedWorkKeepsBothAttachmentsAndStudentAnswer() throws Exception {
        var json = mapper.readTree("""
            {
              "id": 42,
              "status": 2,
              "theme": "Урок 17 — списки",
              "file_path": "https://files.example.org/teacher.docx",
              "file_name": "Задание от преподавателя.docx",
              "homework_stud": {
                "file_path": "https://files.example.org/student/12345",
                "original_filename": "Моя работа.docx",
                "text_answer": "Выполнил задания"
              }
            }
            """);
        Homework hw = TopAcademySource.parseHomework(json, 2, "42");
        assertEquals(HomeworkStatus.review, hw.status());
        assertEquals("Задание от преподавателя.docx", hw.teacherFileName());
        assertEquals("Моя работа.docx", hw.studentFileName());
        assertEquals("https://files.example.org/student/12345", hw.studentFileUrl());
        assertEquals("Выполнил задания", hw.studentAnswer());
        assertEquals("https://files.example.org/teacher.docx", hw.teacherFileUrl());
    }

    @Test void checkedAssignmentHasGradeButNoInventedFeedback() throws Exception {
        var json = mapper.readTree("""
            {"status": 1, "theme": "Задание", "homework_stud": {"mark": 5,
             "file": {"url": "https://files.example.org/attachment", "name": "Тест.docx"}}}
            """);
        Homework hw = TopAcademySource.parseHomework(json, 1, "5");
        assertEquals(HomeworkStatus.done, hw.status());
        assertEquals(5, hw.grade());
        assertTrue(hw.teacherComment().isBlank());
        assertEquals("Тест.docx", hw.studentFileName());
        assertEquals("https://files.example.org/attachment", hw.studentFileUrl());
    }
}

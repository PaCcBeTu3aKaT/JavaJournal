package ru.javajournal.core;

import org.junit.jupiter.api.Test;
import ru.javajournal.core.service.AttachmentLinks;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentLinksTest {
    @Test void rejectsNonHttpsAndLocalTargets() {
        assertFalse(AttachmentLinks.isSafeHttpsLink("http://top-academy.ru/file.pdf"));
        assertFalse(AttachmentLinks.isSafeHttpsLink("file:///etc/passwd"));
        assertFalse(AttachmentLinks.isSafeHttpsLink("https://localhost/file"));
        assertFalse(AttachmentLinks.isSafeHttpsLink("https://127.0.0.1/x"));
        assertFalse(AttachmentLinks.isSafeHttpsLink("https://user:password@files.example.org/a"));
        assertTrue(AttachmentLinks.isSafeHttpsLink("https://files.example.org/docs/task.pdf"));
    }
    @Test void suggestsSafeDownloadName() {
        assertEquals("my task.pdf", AttachmentLinks.suggestedFilename("https://files.example.org/a/my%20task.pdf?token=123", "file"));
        assertEquals("a_b.txt", AttachmentLinks.suggestedFilename("https://files.example.org/a/a%3Ab.txt", "file"));
        assertEquals("attachment", AttachmentLinks.suggestedFilename("https://files.example.org/", "attachment"));
    }
}

package ru.javajournal.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.javajournal.core.service.WorkSubmission;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class WorkSubmissionTest {
    @TempDir Path folder;
    @Test void archivesFilesOnlyOnSubmissionAndCleansTemporaryFile() throws Exception {
        List<WorkSubmission.Attachment> files = List.of(
            new WorkSubmission.Attachment("solution.java", () -> new ByteArrayInputStream("a".getBytes())),
            new WorkSubmission.Attachment("folder/solution.java", () -> new ByteArrayInputStream("b".getBytes())));
        var form = new WorkSubmission.Form("4822", 1, files, "", 1, 0, 5, List.of(1, 4), "");
        assertEquals(0, Files.list(folder).count()); // No archive while editing the form.
        Path temp;
        try (var prepared = WorkSubmission.prepare(form, folder)) {
            temp = prepared.path();
            assertEquals("JavaJournal_LAB_4822.zip", prepared.filename());
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                assertNotNull(zip.getEntry("solution.java"));
                assertNotNull(zip.getEntry("solution (2).java"));
                assertEquals(2, zip.size());
            }
        }
        assertFalse(Files.exists(temp));
    }
    @Test void keepsSingleFileWithoutZip() throws Exception {
        var form = new WorkSubmission.Form("123", 0, List.of(
            new WorkSubmission.Attachment("solution.pdf", () -> new ByteArrayInputStream("pdf".getBytes()))),
            "", 0, 25, 4, List.of(), "");
        try (var prepared = WorkSubmission.prepare(form, folder)) {
            assertEquals("solution.pdf", prepared.filename());
            assertEquals("pdf", Files.readString(prepared.path()));
        }
    }
    @Test void requiresRatingTimeAndAnswer() {
        assertThrows(IllegalArgumentException.class, () -> new WorkSubmission.Form("77", 0,
            List.of(), "", 0, 0, 0, List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new WorkSubmission.Form("77", 0,
            List.of(), "", 0, 10, 3, List.of(), ""));
    }
}

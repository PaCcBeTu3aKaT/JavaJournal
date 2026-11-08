package ru.javajournal.core;

import org.junit.jupiter.api.Test;
import ru.javajournal.core.service.AttachmentLinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentFilenameTest {
    @Test void decodesRussianFileNamesFromHeaderAndUrl() {
        String russian = "Русский язык — работа.docx";
        String header = "attachment; filename*=UTF-8''%D0%A0%D1%83%D1%81%D1%81%D0%BA%D0%B8%D0%B9%20%D1%8F%D0%B7%D1%8B%D0%BA%20%E2%80%94%20%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D0%B0.docx";
        assertEquals(russian, AttachmentLinks.resolveFilename(
            "https://files.example.org/blob/728387", "", header, "application/octet-stream", "вложение"));
        assertEquals("Отчёт.pdf", AttachmentLinks.suggestedFilename(
            "https://files.example.org/%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf", "вложение"));
    }

    @Test void restoresExtensionWhenNameHasNoSuffix() {
        String mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        assertEquals("Моя работа.docx", AttachmentLinks.resolveFilename(
            "https://files.example.org/12345", "Моя работа", "", mime, "вложение"));
        assertEquals(Path.of("ответ.docx"), AttachmentLinks.withMissingExtension(
            Path.of("ответ"), "Моя работа.docx"));
        assertEquals(Path.of("ответ.zip"), AttachmentLinks.withMissingExtension(
            Path.of("ответ.zip"), "Моя работа.docx"));
    }

    @Test void recognizesDocxZipWhenServerSuppliesNoMetadata() throws IOException {
        Path file = Files.createTempFile("javajournal-test-", ".part");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
                output.putNextEntry(new ZipEntry("word/document.xml"));
                output.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            assertEquals(".docx", AttachmentLinks.extensionFromContent(file));
        } finally { Files.deleteIfExists(file); }
    }

    @Test void stripsDirectoryTraversalInUntrustedFilenames() {
        assertEquals("работа.docx", AttachmentLinks.filenameFromContentDisposition(
            "attachment; filename=\"../../работа.docx\""));
        assertEquals("_CON.txt", AttachmentLinks.sanitizedFilename("CON.txt"));
    }

    @org.junit.jupiter.api.Test void shouldPreferHumanNameInsteadOfOpaqueCdnId() {
        String result = ru.javajournal.core.service.AttachmentLinks.resolveFilename(
            "https://files.example.org/8a83f47dd1024b7fbe8ce76e0c0abd45",
            "Русский язык — склонение.docx", "attachment; filename=8a83f47dd1024b7fbe8ce76e0c0abd45",
            "application/octet-stream", "вложение");
        org.junit.jupiter.api.Assertions.assertEquals("Русский язык — склонение.docx", result);
    }

    @org.junit.jupiter.api.Test void shouldAvoidOverwritingDownloadedFiles() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("jj-names-");
        try {
            java.nio.file.Files.writeString(folder.resolve("Русский.docx"), "test");
            java.nio.file.Path suggested = ru.javajournal.core.service.AttachmentLinks.unusedPath(folder, "Русский.docx");
            org.junit.jupiter.api.Assertions.assertEquals("Русский (1).docx", suggested.getFileName().toString());
        } finally {
            java.nio.file.Files.deleteIfExists(folder.resolve("Русский.docx"));
            java.nio.file.Files.deleteIfExists(folder);
        }
    }
}

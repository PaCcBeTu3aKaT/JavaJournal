package ru.javajournal.core.service;

import java.time.LocalDate;
import java.nio.file.Path;
import java.io.IOException;
import ru.javajournal.core.service.AttachmentInfo;
import ru.javajournal.core.service.AttachmentLinks;
import ru.javajournal.core.model.JournalData;
import ru.javajournal.core.model.Profile;

/** Источник данных из API TOP Academy. */
public interface JournalSource {
    Profile profile();
    JournalData load(LocalDate date, boolean week) throws Exception;
    default void refresh() { }
    /** Необязательная проверка метаданных файла (например, Content-Disposition). */
    default AttachmentInfo inspectAttachment(String url, String apiFilename) throws IOException {
        return new AttachmentInfo(AttachmentLinks.resolveFilename(url, apiFilename, "", "", "вложение"), "");
    }

    /** Возвращает итоговый путь, включая дополненное расширение. */
    default Path downloadAttachmentNamed(String url, Path target, String apiFilename) throws IOException {
        downloadAttachment(url, target);
        return target;
    }

    /** Сохраняет с ИМЕНЕМ, полученным только после реального GET-ответа сервера. */
    default Path downloadAttachmentToDirectory(String url, Path directory, String apiFilename) throws IOException {
        throw new IOException("Этот источник не поддерживает автоматическое имя файла.");
    }

    default void downloadAttachment(String url, Path target) throws IOException {
        throw new IOException("Этот источник данных не поддерживает загрузку файлов.");
    }
}

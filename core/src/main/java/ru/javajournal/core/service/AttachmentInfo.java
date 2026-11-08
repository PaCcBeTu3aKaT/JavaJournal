package ru.javajournal.core.service;

/** Имя и MIME-тип вложения, извлечённые из метаданных или ответа сервера. */
public record AttachmentInfo(String filename, String contentType) {
    public AttachmentInfo {
        filename = filename == null ? "" : filename;
        contentType = contentType == null ? "" : contentType;
    }
}

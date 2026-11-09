package ru.javajournal.core.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A local-only upload draft. ZIP is constructed AFTER the student clicks Send. */
public final class WorkSubmission {
    private WorkSubmission() { }
    @FunctionalInterface public interface StreamSource { InputStream open() throws IOException; }
    public record Attachment(String name, StreamSource content) {
        public Attachment { Objects.requireNonNull(name); Objects.requireNonNull(content); }
    }
    public record Form(String homeworkId, int type, List<Attachment> attachments, String answer,
                       int hours, int minutes, int rating, List<Integer> tags, String comment) {
        public Form {
            attachments = List.copyOf(attachments);
            tags = List.copyOf(tags);
            if (homeworkId == null || !homeworkId.matches("[0-9]+")) throw new IllegalArgumentException("Неверный ID задания.");
            if (type != 0 && type != 1) throw new IllegalArgumentException("Неизвестный тип работы.");
            if (hours < 0 || minutes < 0 || minutes > 59 || (hours == 0 && minutes == 0))
                throw new IllegalArgumentException("Укажи время выполнения.");
            if (rating < 1 || rating > 5) throw new IllegalArgumentException("Оцени задание от 1 до 5.");
            if (attachments.size() == 1 && attachments.get(0).name().toLowerCase(Locale.ROOT).matches(".*\\.(txt|csv)"))
                throw new IllegalArgumentException("Форматы TXT и CSV нельзя отправлять отдельными файлами.");
            if (attachments.isEmpty() && (answer == null || answer.isBlank()))
                throw new IllegalArgumentException("Добавь файл или текстовый ответ.");
            if (tags.stream().anyMatch(t -> t == null || t < 1 || t > 7))
                throw new IllegalArgumentException("Неизвестный тег оценки задания.");
            answer = answer == null ? "" : answer;
            comment = comment == null ? "" : comment;
        }
    }
    public record Result(String submissionId, boolean reviewSaved, String message) { }
    public record Prepared(Path path, String filename) implements AutoCloseable {
        @Override public void close() throws IOException { if(path != null) Files.deleteIfExists(path); }
    }

    /** For N files create a ZIP only in the send worker; for one file create a transient copy. */
    public static Prepared prepare(Form form, Path directory) throws IOException {
        Files.createDirectories(directory);
        if (form.attachments().isEmpty()) return new Prepared(null, "");
        boolean zip = form.attachments().size() > 1;
        String filename = zip ? "JavaJournal_" + (form.type() == 1 ? "LAB_" : "HW_")
            + form.homeworkId() + ".zip" : safeName(form.attachments().get(0).name());
        Path temp = Files.createTempFile(directory, ".javajournal-upload-", zip ? ".zip" : ".bin");
        try {
            if (zip) {
                try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temp))) {
                    Set<String> used = new HashSet<>();
                    for (Attachment item : form.attachments()) {
                        String name = safeName(item.name());
                        String unique = name; int suffix = 2;
                        while(!used.add(unique.toLowerCase(Locale.ROOT))) {
                            int dot = name.lastIndexOf('.');
                            unique = (dot > 0 ? name.substring(0, dot) : name) + " (" + suffix++ + ")"
                                + (dot > 0 ? name.substring(dot) : "");
                        }
                        output.putNextEntry(new ZipEntry(unique));
                        try (InputStream input = item.content().open()) { input.transferTo(output); }
                        output.closeEntry();
                    }
                }
            } else {
                try (InputStream input = form.attachments().get(0).content().open()) {
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new Prepared(temp, filename);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }
    public static String safeName(String name) {
        String safe = name.replace('\\','/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) return "attachment";
        return safe.length() > 180 ? safe.substring(safe.length()-180) : safe;
    }
}

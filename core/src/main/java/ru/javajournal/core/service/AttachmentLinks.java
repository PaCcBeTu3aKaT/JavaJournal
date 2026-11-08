package ru.javajournal.core.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipFile;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Проверка HTTPS-ссылок и восстановление читаемых имён вложений, в том числе кириллицы. */
public final class AttachmentLinks {
    private static final Pattern encodedWord = Pattern.compile("^=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=$");
    private static final Pattern extensionPattern = Pattern.compile("\\.[a-zA-Z0-9]{1,10}$");
    private static final Map<String, String> extensions = Map.ofEntries(
        Map.entry("application/pdf", ".pdf"),
        Map.entry("application/msword", ".doc"),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
        Map.entry("application/vnd.ms-excel", ".xls"),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
        Map.entry("application/vnd.ms-powerpoint", ".ppt"),
        Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
        Map.entry("application/zip", ".zip"),
        Map.entry("application/x-zip-compressed", ".zip"),
        Map.entry("image/png", ".png"),
        Map.entry("image/jpeg", ".jpg"),
        Map.entry("image/webp", ".webp"),
        Map.entry("text/plain", ".txt"),
        Map.entry("text/csv", ".csv"),
        Map.entry("application/rtf", ".rtf")
    );

    private AttachmentLinks() { }

    public static boolean isSafeHttpsLink(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null || !host.contains(".") || host.endsWith(".") || host.contains(":")) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")) return false;
            return !normalized.matches("[0-9.]+") && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Старый публичный метод остаётся для обратной совместимости. */
    public static String suggestedFilename(String raw, String fallback) {
        return resolveFilename(raw, "", "", "", fallback);
    }

    /** Приоритет: имя из HTTP Content-Disposition, из API, затем из URL. */
    public static String resolveFilename(String url, String apiFilename, String contentDisposition,
                                         String contentType, String fallback) {
        String urlName = filenameFromUrl(url);
        String headerName = filenameFromContentDisposition(contentDisposition);
        String apiName = sanitizedFilename(apiFilename);
        // Достоверное читаемое имя предпочтительнее технического идентификатора CDN.
        String candidate = "";
        for (String name : new String[] {headerName, apiName, urlName}) {
            if (!name.isBlank() && !isOpaqueName(name)) {
                candidate = name;
                break;
            }
        }
        if (candidate.isBlank()) candidate = sanitizedFilename(fallback);
        if (candidate.isBlank() || isOpaqueName(candidate)) candidate = "вложение";

        // Из MIME или из другого источника переносим расширение, только если оно отсутствует.
        if (extension(candidate).isBlank()) {
            String ext = extension(firstUseful(apiName, urlName));
            if (ext.isBlank()) ext = extensionForMime(contentType);
            if (!ext.isBlank()) candidate += ext;
        }
        return sanitizedFilename(candidate);
    }

    /** Имя, которое сервер передал согласно RFC 5987 / Content-Disposition. */
    public static String filenameFromContentDisposition(String header) {
        if (header == null || header.isBlank()) return "";
        // filename*=UTF-8''%D0%A4%D0%B0%D0%B9%D0%BB.docx
        Matcher extended = Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))")
            .matcher(header);
        if (extended.find()) {
            String part = extended.group(1) != null ? extended.group(1) : extended.group(2).strip();
            int firstQuote = part.indexOf("\'");
            int secondQuote = firstQuote < 0 ? -1 : part.indexOf("\'", firstQuote + 1);
            if (firstQuote >= 0 && secondQuote > firstQuote) {
                String charset = part.substring(0, firstQuote).strip();
                String encoded = part.substring(secondQuote + 1);
                if (charset.equalsIgnoreCase("UTF-8") || charset.equalsIgnoreCase("UTF8")) {
                    return sanitizedFilename(percentDecode(encoded));
                }
                if (charset.equalsIgnoreCase("ISO-8859-1")) {
                    try { return sanitizedFilename(java.net.URLDecoder.decode(encoded.replace("+", "%2B"),
                        java.nio.charset.StandardCharsets.ISO_8859_1)); }
                    catch (IllegalArgumentException ignored) { }
                }
            }
        }
        Matcher plain = Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]+))")
            .matcher(header);
        if (!plain.find()) return "";
        String value = plain.group(1) != null ? plain.group(1).replace("\\\"", "\"") : plain.group(2).strip();
        String decoded = decodeEncodedWord(value);
        if (decoded.matches(".*%[0-9a-fA-F]{2}.*")) decoded = percentDecode(decoded);
        return sanitizedFilename(decoded);
    }

    public static String sanitizedFilename(String input) {
        if (input == null || input.isBlank()) return "";
        String normalizedInput = input.strip();
        if (normalizedInput.matches(".*%[0-9a-fA-F]{2}.*")) normalizedInput = percentDecode(normalizedInput);
        String value = Normalizer.normalize(repairMojibake(normalizedInput), Normalizer.Form.NFC);
        // URL и HTTP-заголовки — недоверенные. Отсекаем любые пути и управляющие символы.
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        value = value.replaceAll("[\\x00-\\x1f\\x7f<>:\"|?*]", "_").strip();
        while (value.endsWith(".") || value.endsWith(" ")) value = value.substring(0, value.length() - 1);
        if (value.equals(".") || value.equals("..")) return "";
        if (value.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$")) value = "_" + value;
        if (value.length() > 170) {
            String ext = extension(value);
            value = value.substring(0, Math.max(1, 170 - ext.length())) + ext;
        }
        return value;
    }

    /** Иногда сервер передаёт UTF-8 как байты Latin-1/Windows-1252 (Ð ÑƒÑÑÐºÐ¸Ð¹). */
    private static String repairMojibake(String value) {
        if (!(value.contains("Ð") || value.contains("Ñ"))) return value;
        for (String charsetName : new String[] {"windows-1252", "ISO-8859-1"}) {
            try {
                byte[] bytes = value.getBytes(java.nio.charset.Charset.forName(charsetName));
                var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                String repaired = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (repaired.matches(".*[А-Яа-яЁё].*")) return repaired;
            } catch (java.nio.charset.CharacterCodingException ignored) { }
        }
        return value;
    }

    /** Распознавание типа по сигнатуре файла, если API/CDN не указали расширение и MIME. */
    public static String extensionFromContent(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] signature = input.readNBytes(8);
            if (signature.length >= 4) {
                if (signature[0] == '%' && signature[1] == 'P' && signature[2] == 'D' && signature[3] == 'F') return ".pdf";
                if ((signature[0] & 0xff) == 0x89 && signature[1] == 'P' && signature[2] == 'N' && signature[3] == 'G') return ".png";
                if ((signature[0] & 0xff) == 0xff && (signature[1] & 0xff) == 0xd8) return ".jpg";
                if (signature[0] == 'P' && signature[1] == 'K' && signature[2] == 3 && signature[3] == 4) {
                    // DOCX/XLSX/PPTX технически ZIP: читаем только центральный каталог.
                    try (ZipFile zip = new ZipFile(file.toFile())) {
                        if (zip.getEntry("word/document.xml") != null) return ".docx";
                        if (zip.getEntry("xl/workbook.xml") != null) return ".xlsx";
                        if (zip.getEntry("ppt/presentation.xml") != null) return ".pptx";
                    }
                    return ".zip";
                }
            }
        } catch (IOException ignored) { }
        return "";
    }

    public static String extensionForMime(String contentType) {
        if (contentType == null) return "";
        String mime = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return extensions.getOrDefault(mime, "");
    }

    /** Дополняем выбранное пользователем имя расширением, если он его не ввёл. */
    public static Path withMissingExtension(Path requested, String resolvedName) {
        String current = requested.getFileName().toString();
        if (!extension(current).isBlank()) return requested;
        String ext = extension(resolvedName);
        return ext.isBlank() ? requested : requested.resolveSibling(current + ext);
    }

    private static boolean isOpaqueName(String name) {
        if (name == null || name.isBlank()) return true;
        String stem = name.replaceFirst("(?i)\\.[a-z0-9]{1,10}$", "");
        return stem.matches("(?i)^[0-9a-f]{16,}$")
            || stem.matches("(?i)^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
            || (stem.matches("^[a-zA-Z0-9_-]{28,}$") && stem.matches(".*[0-9].*"))
            || stem.equalsIgnoreCase("download") || stem.equalsIgnoreCase("attachment")
            || stem.equalsIgnoreCase("file") || stem.equalsIgnoreCase("get-file");
    }

    private static String firstUseful(String... choices) {
        for (String choice : choices) {
            String clean = sanitizedFilename(choice);
            if (!clean.isBlank()) return clean;
        }
        return "";
    }

    private static String filenameFromUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI url = URI.create(raw);
            String path = url.getRawPath();
            String fromPath = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            fromPath = percentDecode(fromPath);
            // Некоторые CDN URL используют UUID без расширения, а имя передают query-параметром.
            String query = url.getRawQuery();
            if (query != null) {
                for (String segment : query.split("&")) {
                    int pos = segment.indexOf('=');
                    if (pos < 0) continue;
                    String key = segment.substring(0, pos).toLowerCase(Locale.ROOT);
                    if (key.equals("filename") || key.equals("file_name") || key.equals("name")) {
                        String hint = sanitizedFilename(percentDecode(segment.substring(pos + 1)));
                        if (!hint.isBlank()) return hint;
                    }
                }
            }
            return sanitizedFilename(fromPath);
        } catch (IllegalArgumentException e) { return ""; }
    }

    private static String extension(String name) {
        if (name == null) return "";
        Matcher match = extensionPattern.matcher(name);
        return match.find() ? match.group().toLowerCase(Locale.ROOT) : "";
    }


    /** Не перезаписываем существующий файл: при повторной загрузке добавляем (1), (2) и т. д. */
    public static Path unusedPath(Path folder, String filename) throws IOException {
        String clean = sanitizedFilename(filename);
        if (clean.isBlank()) clean = "вложение";
        Path first = folder.resolve(clean);
        if (!Files.exists(first)) return first;
        String ext = extension(clean);
        String stem = clean.substring(0, clean.length() - ext.length());
        for (int number = 1; number < 1000; number++) {
            Path alternative = folder.resolve(stem + " (" + number + ")" + ext);
            if (!Files.exists(alternative)) return alternative;
        }
        throw new IOException("Слишком много одинаковых файлов в папке.");
    }

    private static String percentDecode(String raw) {
        try { return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return raw; }
    }

    private static String decodeEncodedWord(String value) {
        Matcher match = encodedWord.matcher(value.strip());
        if (!match.matches()) return value;
        try {
            java.nio.charset.Charset encoding = java.nio.charset.Charset.forName(match.group(1));
            byte[] bytes = match.group(2).equalsIgnoreCase("B")
                ? Base64.getDecoder().decode(match.group(3))
                : decodeQuotedPrintable(match.group(3).replace('_', ' '));
            return new String(bytes, encoding);
        } catch (IllegalArgumentException e) { return value; }
    }

    private static byte[] decodeQuotedPrintable(String value) {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '=' && index + 2 < value.length()) {
                try {
                    result.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
                    index += 2;
                    continue;
                } catch (NumberFormatException ignored) { }
            }
            result.write((byte)value.charAt(index));
        }
        return result.toByteArray();
    }
}

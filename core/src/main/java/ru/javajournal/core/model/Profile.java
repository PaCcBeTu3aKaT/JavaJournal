package ru.javajournal.core.model;

/** Полное отображаемое имя и имя для персонального приветствия. */
public record Profile(String name, String group, String stream, String level, String givenName) {
    public Profile {
        name = safe(name, "Студент");
        group = safe(group, "Группа не определена");
        stream = safe(stream, "");
        level = safe(level, "");
        givenName = safe(givenName, guessGivenName(name));
    }

    public Profile(String name, String group, String stream, String level) {
        this(name, group, stream, level, "");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String guessGivenName(String name) {
        if (name == null || name.isBlank()) return "студент";
        String[] parts = name.strip().split("\\s+");
        // API часто отдаёт Фамилия Имя Отчество. Если специальное поле имени отсутствует,
        // выбираем вторую часть ФИО. Для демо передаём givenName явно.
        return parts.length >= 2 ? parts[1] : parts[0];
    }
}

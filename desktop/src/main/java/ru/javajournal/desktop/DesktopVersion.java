package ru.javajournal.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.prefs.Preferences;
import ru.javajournal.core.service.ReleaseUpdates;

/** Версия передаётся Gradle при сборке; репозиторий можно задать в профиле. */
final class DesktopVersion {
    private static final Preferences preferences = Preferences.userNodeForPackage(DesktopVersion.class);
    private static final Properties build = readBuild();

    private DesktopVersion() { }

    static String current() {
        return build.getProperty("appVersion", "1.0.1");
    }

    static String repository() {
        return preferences.get("githubRepository", build.getProperty("githubRepository", "")).strip();
    }

    static void saveRepository(String value) {
        String repo = ReleaseUpdates.normalizeRepository(value);
        preferences.put("githubRepository", repo);
    }

    private static Properties readBuild() {
        Properties properties = new Properties();
        try (InputStream input = DesktopVersion.class.getResourceAsStream("/javajournal-build.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException ignored) { }
        return properties;
    }
}

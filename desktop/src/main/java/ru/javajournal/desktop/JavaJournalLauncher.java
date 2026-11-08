package ru.javajournal.desktop;

import javafx.application.Application;

/** Обычная точка входа для Gradle, Windows .exe и JavaFX. */
public final class JavaJournalLauncher {
    private JavaJournalLauncher() { }
    public static void main(String[] args) {
        Application.launch(JavaJournalApp.class, args);
    }
}

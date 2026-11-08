package ru.javajournal.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import ru.javajournal.core.service.AttachmentLinks;
import ru.javajournal.core.service.AttachmentInfo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import ru.javajournal.core.api.TopAcademySource;
import ru.javajournal.core.model.*;
import ru.javajournal.core.service.JournalSource;
import ru.javajournal.core.service.JournalFilters;
import ru.javajournal.core.service.JournalDisplay;
import ru.javajournal.core.service.ReleaseUpdates;
import javafx.beans.binding.Bindings;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JavaJournal. Все элементы GUI создаются Java-кодом: FXML и внешних CSS-файлов нет.
 * Desktop GUI не блокирует JavaFX Thread во время сетевых операций.
 */
public final class JavaJournalApp extends Application {
    private enum Page { overview, schedule, homework, grades, attendance, profile }
    private record Palette(String bg, String surface, String raised, String border,
                           String text, String muted, String accent, String accentDim,
                           String green, String greenDim, String amber, String amberDim,
                           String red, String redDim, String sidebar) { }
    private static final Locale russianLocale = Locale.forLanguageTag("ru-RU");
    private static final DateTimeFormatter fullDate = DateTimeFormatter.ofPattern("d MMMM yyyy", russianLocale);
    private static final DateTimeFormatter shortDate = DateTimeFormatter.ofPattern("d MMM", russianLocale);

    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "javajournal-network");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger requestSequence = new AtomicInteger();
    private Stage stage;
    private Scene scene;
    private JournalSource source;
    private JournalData data;
    private Page page = Page.overview;
    private LocalDate selectedDate = JournalDisplay.startingWeekDate(LocalDate.now());
    private boolean weekMode = true;
    private boolean light = false;
    private boolean loading = false;
    private String loadError = "";
    private String homeworkFilter = "Все";
    private boolean homeworkTiles = false;
    private JournalFilters.HomeworkSort homeworkSort = JournalFilters.HomeworkSort.dueAscending;
    private JournalFilters.Period statsPeriod = JournalFilters.Period.month;
    private LocalDate statsDate = LocalDate.now();
    private boolean checkingUpdates = false;
    private boolean updateNoticeShown = false;

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("JavaJournal");
        stage.setMinWidth(960);
        stage.setMinHeight(650);
        try (var stream = JavaJournalApp.class.getResourceAsStream("/icons/javajournal.png")) {
            if (stream != null) stage.getIcons().add(new Image(stream));
        } catch (java.io.IOException ignored) { }
        showLogin();
        stage.show();
        // Не блокируем окно входа: проверка публичного GitHub Release идёт в фоне.
        checkUpdates(false, null);
    }

    @Override public void stop() { executor.shutdownNow(); }

    private Palette palette() {
        return light
            ? new Palette("#F4F6FC", "#FFFFFF", "#F8FAFF", "#E4E8F2", "#15203B", "#75829D",
                "#5865E8", "#E9ECFF", "#168A62", "#E1F8EF", "#B57A1C", "#FFF1CF", "#C34858", "#FDE8EC", "#FFFFFF")
            : new Palette("#0C1222", "#151D31", "#1D2840", "#2B3650", "#EDF2FF", "#96A4BF",
                "#8290FF", "#29355B", "#58D8AA", "#163C38", "#FFCA78", "#3A302B", "#FF8896", "#3B2736", "#11192C");
    }

    private Label text(String value, double size, boolean bold, String color) {
        Label label = new Label(value == null ? "" : value);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + size + "px; -fx-font-weight: "
            + (bold ? "700" : "400") + "; -fx-font-family: 'Segoe UI', 'Arial';");
        return label;
    }
    private Label title(String value, double size) { return text(value, size, true, palette().text); }
    private Label muted(String value, double size) { return text(value, size, false, palette().muted); }
    private VBox column(double gap, Node... children) { return new VBox(gap, children); }
    private HBox row(double gap, Node... children) { return new HBox(gap, children); }
    private void grow(Node node) { if (node instanceof Region r) { HBox.setHgrow(r, Priority.ALWAYS); r.setMaxWidth(Double.MAX_VALUE); } }
    private void full(Node node) { if (node instanceof Region r) r.setMaxWidth(Double.MAX_VALUE); }

    private String boxStyle(String background, int radius, boolean border) {
        return "-fx-background-color: " + background + "; -fx-background-radius: " + radius + "; "
            + (border ? "-fx-border-color: " + palette().border + "; -fx-border-radius: " + radius + "; -fx-border-width: 1;" : "");
    }
    private VBox card(double gap) {
        VBox box = new VBox(gap);
        box.setPadding(new Insets(20));
        box.setStyle(boxStyle(palette().surface, 18, true));
        return box;
    }
    private Region spacer() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); return region; }

    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(label);
        Palette p = palette();
        String normal = "-fx-background-color: " + (primary ? p.accent : p.raised) + "; "
            + "-fx-text-fill: " + (primary ? "#FFFFFF" : p.text) + "; "
            + "-fx-font-size: 13px; -fx-font-weight: 700; -fx-background-radius: 11; "
            + "-fx-padding: 11 16 11 16; -fx-cursor: hand; -fx-border-width: 0;";
        String hover = normal.replace("-fx-background-color: " + (primary ? p.accent : p.raised),
            "-fx-background-color: " + (primary ? "#6777E2" : p.accentDim));
        b.setStyle(normal);
        b.setOnMouseEntered(e -> b.setStyle(hover));
        b.setOnMouseExited(e -> b.setStyle(normal));
        b.setOnAction(e -> action.run());
        return b;
    }
    private Button subtle(String label, Runnable action) {
        Button b = button(label, false, action);
        b.setFocusTraversable(false);
        return b;
    }
    private Label pill(String label, String fg, String bg) {
        Label tag = text(label, 11, true, fg);
        tag.setPadding(new Insets(6, 10, 6, 10));
        tag.setStyle(tag.getStyle() + " -fx-background-color: " + bg + "; -fx-background-radius: 99;");
        return tag;
    }

    private void showLogin() {
        Palette p = palette();
        StackPane wrapper = new StackPane();
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(30));
        wrapper.setStyle("-fx-background-color: " + p.bg + ";");

        VBox authCard = card(15);
        authCard.setPrefWidth(400);
        authCard.setMinWidth(350);
        authCard.setMaxWidth(420);
        // Высота определяется содержимым — карточка не растягивается до высоты окна.
        authCard.setMaxHeight(Region.USE_PREF_SIZE);
        authCard.setPadding(new Insets(30));
        HBox brand = row(10, markIcon(35), title("JavaJournal", 19));
        brand.setAlignment(Pos.CENTER_LEFT);
        authCard.getChildren().addAll(brand, title("Добро пожаловать", 25));

        TextField login = new TextField();
        login.setPromptText("Логин");
        styleInput(login);
        PasswordField password = new PasswordField();
        password.setPromptText("Пароль");
        styleInput(password);
        authCard.getChildren().addAll(text("Логин", 12, true, p.text), login,
            text("Пароль", 12, true, p.text), password);
        Label error = text("", 12, false, p.red);
        error.setMinHeight(18);
        Button signIn = button("Войти в аккаунт", true, () -> {
            if (login.getText().isBlank() || password.getText().isBlank()) {
                error.setText("Введите логин и пароль.");
                return;
            }
            signInInProgress(login, password, error, authCard);
        });
        password.setOnAction(event -> signIn.fire());
        full(signIn);
        authCard.getChildren().addAll(error, signIn);
        wrapper.getChildren().add(authCard);
        setScene(wrapper, 1100, 760);
    }

    private void signInInProgress(TextField login, PasswordField password, Label error, VBox card) {
        String username = login.getText().strip();
        String passwordValue = password.getText();
        password.clear();
        error.setText("Подключаемся к TOP Academy...");
        card.setDisable(true);
        CompletableFuture.supplyAsync(() -> {
            try { return TopAcademySource.login(username, passwordValue); }
            catch (Exception e) { throw new CompletionException(e); }
        }, executor).whenComplete((result, failure) -> Platform.runLater(() -> {
            card.setDisable(false);
            if (failure != null) {
                error.setText(safeError(failure));
                return;
            }
            source = result;
            data = null;
            selectedDate = JournalDisplay.startingWeekDate(LocalDate.now());
            weekMode = true;
            statsDate = LocalDate.now();
            statsPeriod = JournalFilters.Period.month;
            page = Page.overview;
            requestData(false);
        }));
    }

    private void styleInput(TextField input) {
        Palette p = palette();
        input.setPrefHeight(45);
        input.setStyle("-fx-background-color: " + p.raised + "; -fx-background-radius: 10; "
            + "-fx-border-color: " + p.border + "; -fx-border-radius: 10; "
            + "-fx-text-fill: " + p.text + "; -fx-prompt-text-fill: " + p.muted + "; "
            + "-fx-font-size: 13px; -fx-padding: 10 13 10 13;");
    }
    private StackPane markIcon(int size) {
        StackPane icon = new StackPane();
        icon.setPrefSize(size, size);
        icon.setMinSize(size, size);
        icon.setMaxSize(size, size);
        icon.setStyle("-fx-background-color: #6778F8; -fx-background-radius: " + (size / 4) + ";");
        Label j = text("J", size * 0.54, true, "#FFFFFF");
        icon.getChildren().add(j);
        return icon;
    }

    private void setScene(Region root, int width, int height) {
        if (scene == null) {
            scene = new Scene(root, width, height);
            stage.setScene(scene);
        } else {
            scene.setRoot(root);
        }
    }

    private void requestData(boolean force) {
        if (source == null) return;
        int id = requestSequence.incrementAndGet();
        loading = true;
        loadError = "";
        drawApp();
        JournalSource current = source;
        LocalDate day = selectedDate;
        boolean week = weekMode;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (force) current.refresh();
                return current.load(day, week);
            } catch (Exception e) { throw new CompletionException(e); }
        }, executor).whenComplete((value, failure) -> Platform.runLater(() -> {
            if (id != requestSequence.get() || source != current) return;
            loading = false;
            if (failure != null) loadError = safeError(failure);
            else { data = value; loadError = ""; }
            drawApp();
        }));
    }

    private static String safeError(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && (cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Не удалось выполнить запрос. Проверь соединение и повтори попытку." : message;
    }

    private void drawApp() {
        if (source == null) { showLogin(); return; }
        Palette p = palette();
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + p.bg + ";");
        root.setLeft(sidebar());
        VBox area = column(0);
        area.getChildren().add(topbar());
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: " + p.bg + "; -fx-border-width: 0;");
        VBox inner = column(22);
        inner.setPadding(new Insets(29, 34, 50, 34));
        inner.setMaxWidth(1280);
        inner.getChildren().addAll(content());
        if (page == Page.profile) {
            // Версия находится в правом нижнем углу даже на пустой странице профиля.
            inner.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, scroll.getViewportBounds().getHeight()),
                scroll.viewportBoundsProperty()));
        }
        scroll.setContent(inner);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        area.getChildren().add(scroll);
        root.setCenter(area);
        setScene(root, 1280, 820);
    }

    private VBox sidebar() {
        Palette p = palette();
        VBox panel = column(0);
        panel.setPrefWidth(242);
        panel.setMinWidth(242);
        panel.setPadding(new Insets(25, 14, 20, 14));
        panel.setStyle("-fx-background-color: " + p.sidebar + "; -fx-border-color: " + p.border + "; -fx-border-width: 0 1 0 0;");
        HBox brand = row(12, markIcon(38), title("JavaJournal", 19));
        brand.setPadding(new Insets(0, 8, 34, 9));
        brand.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().add(brand);
        Label navTitle = muted("РАЗДЕЛЫ", 10);
        navTitle.setPadding(new Insets(0, 0, 10, 13));
        panel.getChildren().add(navTitle);
        addMenu(panel, "Обзор", Page.overview);
        addMenu(panel, "Расписание", Page.schedule);
        addMenu(panel, "Домашние задания", Page.homework);
        addMenu(panel, "Оценки", Page.grades);
        addMenu(panel, "Посещаемость", Page.attendance);
        addMenu(panel, "Профиль", Page.profile);
        Region vSpacer = new Region();
        VBox.setVgrow(vSpacer, Priority.ALWAYS);
        panel.getChildren().add(vSpacer);
        Button exit = subtle("Выйти", () -> {
            requestSequence.incrementAndGet();
            source = null;
            data = null;
            loading = false;
            loadError = "";
            homeworkFilter = "Все";
            homeworkTiles = false;
            selectedDate = JournalDisplay.startingWeekDate(LocalDate.now());
            weekMode = true;
            showLogin();
        });
        exit.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(exit, new Insets(13, 0, 0, 0));
        panel.getChildren().add(exit);
        return panel;
    }

    private Circle marker(String color, double radius) {
        Circle circle = new Circle(radius);
        circle.setFill(Color.web(color));
        return circle;
    }

    private void addMenu(VBox panel, String name, Page target) {
        boolean active = page == target;
        Palette p = palette();
        HBox item = row(13, marker(active ? p.accent : p.muted, 5),
            text(name, 13, active, active ? p.text : p.muted));
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(12, 13, 12, 13));
        String normal = boxStyle(active ? p.accentDim : "transparent", 11, false);
        item.setStyle(normal + "-fx-cursor: hand;");
        item.setOnMouseEntered(e -> { if (!active) item.setStyle(boxStyle(p.raised, 11, false) + "-fx-cursor: hand;"); });
        item.setOnMouseExited(e -> item.setStyle(normal + "-fx-cursor: hand;"));
        item.setOnMouseClicked(e -> {
            page = target;
            if (target == Page.schedule || target == Page.overview) {
                selectedDate = JournalDisplay.startingWeekDate(LocalDate.now());
                weekMode = true;
                requestData(false);
            } else drawApp();
        });
        VBox.setMargin(item, new Insets(1, 0, 1, 0));
        panel.getChildren().add(item);
    }

    private HBox topbar() {
        Palette p = palette();
        HBox bar = row(14);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(17, 34, 17, 34));
        bar.setStyle("-fx-background-color: " + p.surface + "; -fx-border-color: " + p.border + "; -fx-border-width: 0 0 1 0;");
        bar.getChildren().add(spacer());
        Button theme = subtle(light ? "Тёмная тема" : "Светлая тема", () -> { light = !light; drawApp(); });
        bar.getChildren().add(theme);
        Button refresh = button(loading ? "Обновление..." : "Обновить", true, () -> requestData(true));
        refresh.setDisable(loading);
        bar.getChildren().add(refresh);
        return bar;
    }

    private List<Node> content() {
        List<Node> out = new ArrayList<>();
        String heading = switch (page) {
            case overview -> "Главная";
            case schedule -> "Расписание";
            case homework -> "Домашние задания";
            case grades -> "Оценки";
            case attendance -> "Посещаемость";
            case profile -> "Мой профиль";
        };
        out.add(title(heading, 28));
        if (!loadError.isBlank()) out.add(warning("Не удалось обновить данные: " + loadError));
        if (loading && data == null) {
            VBox wait = card(18);
            wait.setAlignment(Pos.CENTER);
            wait.setMinHeight(250);
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(43, 43);
            wait.getChildren().addAll(spinner, muted("Загружаем журнал...", 14));
            out.add(wait);
            return out;
        }
        if (data == null) {
            out.add(blankState("Данные пока недоступны. Нажми «Обновить», чтобы повторить запрос."));
            return out;
        }
        if (loading) out.add(pill("Идёт обновление...", palette().accent, palette().accentDim));
        switch (page) {
            case overview -> overview(out);
            case schedule -> schedule(out);
            case homework -> homework(out);
            case grades -> grades(out);
            case attendance -> attendance(out);
            case profile -> profile(out);
        }
        return out;
    }

    private VBox warning(String message) {
        VBox box = column(5, text("Данные получены не полностью", 13, true, palette().amber),
            muted(message, 12));
        box.setPadding(new Insets(17));
        box.setStyle(boxStyle(palette().amberDim, 13, false));
        return box;
    }
    private VBox blankState(String message) {
        VBox box = card(10);
        box.setAlignment(Pos.CENTER);
        box.setMinHeight(140);
        box.getChildren().add(muted(message, 14));
        return box;
    }
    private void addWarning(List<Node> out, String key) {
        String warning = data.warnings().get(key);
        if (warning != null) out.add(warning("Раздел «" + key + "»: " + warning));
    }

    private void overview(List<Node> out) {
        Palette p = palette();
        VBox banner = column(12);
        banner.setPadding(new Insets(26));
        banner.setStyle("-fx-background-color: linear-gradient(to right, #4148B7, #707BE4); -fx-background-radius: 19;");
        Label greeting = text("Привет, " + data.profile().givenName() + "!", 27, true, "#FFFFFF");
        banner.getChildren().add(greeting);
        out.add(banner);

        HBox metrics = row(14);
        VBox a = metric("Занятий на неделе", String.valueOf(weekLessonCount()), "Расписание", p.accent);
        VBox b = metric("Активных заданий", String.valueOf(data.activeHomework()), "Нужно сделать", p.amber);
        VBox c = metric("Средний балл", data.grades().isEmpty() ? "—" : String.format(russianLocale, "%.1f", data.averageGrade()), "Все оценки", p.green);
        VBox d = metric("Посещаемость", data.attendance().isEmpty() ? "—" : String.format(russianLocale, "%.0f%%", data.attendancePercent()), "Все занятия", p.accent);
        for (VBox v : List.of(a, b, c, d)) { grow(v); metrics.getChildren().add(v); }
        out.add(metrics);

        HBox columns = row(18);
        VBox left = card(12);
        grow(left);
        left.setPrefWidth(560);
        HBox lh = row(8, title("Ближайшее занятие", 17), spacer(), link("Всё расписание", () -> { page = Page.schedule; drawApp(); }));
        lh.setAlignment(Pos.CENTER_LEFT);
        left.getChildren().add(lh);
        addWarningTo(left, "schedule");
        JournalDisplay.nearestTodayLesson(data.lessons(), LocalDate.now(), LocalTime.now())
            .ifPresentOrElse(lesson -> {
                if (JournalDisplay.lessonInProgress(lesson, LocalTime.now())) {
                    left.getChildren().add(pill("Идёт сейчас", p.green, p.greenDim));
                } else {
                    left.getChildren().add(pill("Следующая пара сегодня", p.accent, p.accentDim));
                }
                left.getChildren().add(lessonRow(lesson, true));
            }, () -> left.getChildren().add(muted("На сегодня занятий больше нет.", 13)));
        columns.getChildren().add(left);

        VBox right = card(12);
        right.setPrefWidth(330);
        grow(right);
        HBox rh = row(8, title("Домашние задания", 17), spacer(), link("Все задания", () -> { page = Page.homework; drawApp(); }));
        rh.setAlignment(Pos.CENTER_LEFT);
        right.getChildren().add(rh);
        addWarningTo(right, "homework");
        List<Homework> pending = JournalFilters.sortedHomework(data.homework()).stream()
            .filter(h -> h.status() == HomeworkStatus.pending || h.status() == HomeworkStatus.overdue)
            .limit(5).toList();
        if (pending.isEmpty()) right.getChildren().add(muted("Нет активных заданий.", 13));
        else pending.forEach(h -> right.getChildren().add(homeworkShort(h)));
        columns.getChildren().add(right);
        out.add(columns);
    }

    private int weekLessonCount() {
        if (data.week()) return data.lessons().size();
        return data.lessons().size(); // Для режима «День» показываем количество за выбранный день.
    }
    private VBox metric(String name, String value, String hint, String color) {
        VBox box = card(8);
        HBox head = row(7, muted(name, 11), spacer(), marker(color, 5));
        head.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(head, title(value, 30), muted(hint, 11));
        return box;
    }
    private Button link(String label, Runnable action) {
        Button btn = new Button(label + "  ›");
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + palette().accent + "; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private HBox dateControls() {
        HBox box = row(9);
        box.setAlignment(Pos.CENTER_LEFT);
        Button back = subtle("‹", () -> moveDate(-1));
        Button next = subtle("›", () -> moveDate(1));
        Label current = title(dateLabel(), 14);
        current.setMinWidth(210);
        current.setAlignment(Pos.CENTER);
        Button today = subtle("Сегодня", () -> { selectedDate = weekMode ? JournalDisplay.startingWeekDate(LocalDate.now()) : LocalDate.now(); requestData(false); });
        box.getChildren().addAll(back, current, next, today);
        return box;
    }
    private String dateLabel() {
        if (!weekMode) return selectedDate.format(fullDate);
        LocalDate monday = selectedDate.minusDays(selectedDate.getDayOfWeek().getValue() - 1);
        return monday.format(shortDate) + " — " + monday.plusDays(6).format(shortDate);
    }
    private void moveDate(int direction) {
        selectedDate = selectedDate.plusDays(weekMode ? 7L * direction : direction);
        requestData(false);
    }

    private void schedule(List<Node> out) {
        HBox toolbar = row(14);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().add(dateControls());
        toolbar.getChildren().add(spacer());
        HBox modes = row(6, toggle("День", !weekMode, () -> { selectedDate = LocalDate.now(); weekMode = false; requestData(false); }),
            toggle("Неделя", weekMode, () -> { selectedDate = JournalDisplay.startingWeekDate(LocalDate.now()); weekMode = true; requestData(false); }));
        toolbar.getChildren().add(modes);
        out.add(toolbar);
        addWarning(out, "schedule");
        if (data.lessons().isEmpty()) { out.add(blankState("На выбранный период нет занятий.")); return; }
        Map<LocalDate, List<Lesson>> grouped = new LinkedHashMap<>();
        data.lessons().forEach(l -> grouped.computeIfAbsent(l.date(), d -> new ArrayList<>()).add(l));
        for (Map.Entry<LocalDate, List<Lesson>> group : grouped.entrySet()) {
            String weekday = group.getKey().getDayOfWeek().getDisplayName(TextStyle.FULL, russianLocale);
            HBox heading = row(12, title(capitalize(weekday), 17), muted(group.getKey().format(fullDate), 12), spacer(),
                pill(JournalDisplay.lessonCount(group.getValue().size()), palette().accent, palette().accentDim));
            heading.setAlignment(Pos.CENTER_LEFT);
            out.add(heading);
            VBox rows = card(10);
            for (Lesson l : group.getValue()) rows.getChildren().add(lessonRow(l, false));
            out.add(rows);
        }
    }
    private Button toggle(String name, boolean active, Runnable action) {
        Palette palette = palette();
        Button control = new Button(name);
        control.setFocusTraversable(false);
        String background = active ? palette.accentDim : palette.surface;
        String foreground = active ? palette.text : palette.muted;
        String outline = active ? palette.accent : palette.border;
        String regularStyle = "-fx-background-color: " + background + "; -fx-text-fill: " + foreground
            + "; -fx-border-color: " + outline + "; -fx-border-radius: 10;"
            + " -fx-border-width: 1; -fx-background-radius: 10; -fx-padding: 10 15;"
            + " -fx-font-size: 13px; -fx-font-weight: 600; -fx-cursor: hand;";
        String hoverStyle = regularStyle.replace("-fx-background-color: " + background,
            "-fx-background-color: " + palette.raised);
        control.setStyle(regularStyle);
        control.setOnMouseEntered(event -> control.setStyle(hoverStyle));
        control.setOnMouseExited(event -> control.setStyle(regularStyle));
        control.setOnAction(event -> action.run());
        return control;
    }

    private HBox lessonRow(Lesson lesson, boolean compact) {
        Palette p = palette();
        HBox box = row(15);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(compact ? 12 : 14, 12, compact ? 12 : 14, 12));
        box.setStyle(boxStyle(p.raised, 12, false));
        VBox time = column(3, text(blank(lesson.start(), "—"), 15, true, p.accent),
            muted(blank(lesson.end(), ""), 11));
        time.setPrefWidth(61);
        box.getChildren().add(time);
        Region rule = new Region();
        rule.setPrefWidth(3);
        rule.setMinWidth(3);
        rule.setMinHeight(compact ? 48 : 58);
        rule.setStyle("-fx-background-color: " + p.accent + "; -fx-background-radius: 3;");
        box.getChildren().add(rule);
        VBox details = column(5, title(lesson.subject(), compact ? 13 : 15),
            muted((lesson.teacher() == null || lesson.teacher().isBlank() ? "Преподаватель не указан" : lesson.teacher())
                + (lesson.room() == null || lesson.room().isBlank() ? "" : "  ·  " + lesson.room()), compact ? 11 : 12));
        grow(details);
        box.getChildren().add(details);
        if (!compact) box.getChildren().add(lessonState(lesson));
        return box;
    }
    private Label lessonState(Lesson lesson) {
        LocalDate today = LocalDate.now();
        Palette p = palette();
        if (lesson.date().isBefore(today)) return pill("Прошло", p.muted, p.surface);
        if (lesson.date().isAfter(today)) return pill("Предстоит", p.accent, p.accentDim);
        try {
            LocalTime start = LocalTime.parse(lesson.start());
            LocalTime end = LocalTime.parse(lesson.end());
            LocalTime now = LocalTime.now();
            if (!now.isBefore(start) && now.isBefore(end)) return pill("Идёт", p.green, p.greenDim);
            if (!now.isBefore(end)) return pill("Прошло", p.muted, p.surface);
        } catch (Exception ignored) { }
        return pill("Сегодня", p.accent, p.accentDim);
    }

    private void homework(List<Node> out) {
        // Единая линия управления: фильтры слева, сортировка и вид справа.
        // При узком окне панель прокручивается по горизонтали, но не разваливается на две строки.
        HBox filters = row(7);
        filters.setAlignment(Pos.CENTER_LEFT);
        for (String filter : List.of("Все", "Активно", "Просрочено", "На проверке", "Проверено")) {
            filters.getChildren().add(toggle(filter, filter.equals(homeworkFilter), () -> {
                homeworkFilter = filter;
                drawApp();
            }));
        }
        Button sortButton = subtle(homeworkSort.label() + "  ▾", () -> { });
        sortButton.setMinWidth(175);
        sortButton.setPrefWidth(175);
        ContextMenu sortMenu = new ContextMenu();
        sortMenu.setStyle("-fx-background-color: " + palette().surface + "; -fx-background-radius: 10; "
            + "-fx-border-color: " + palette().border + "; -fx-border-radius: 10; -fx-padding: 5;");
        for (JournalFilters.HomeworkSort mode : JournalFilters.HomeworkSort.values()) {
            MenuItem option = new MenuItem(mode.label());
            option.setStyle("-fx-font-size: 12px; -fx-text-fill: " + palette().text + ";");
            option.setOnAction(event -> { homeworkSort = mode; drawApp(); });
            sortMenu.getItems().add(option);
        }
        sortButton.setOnAction(event -> sortMenu.show(sortButton, Side.BOTTOM, 0, 5));
        HBox viewModes = row(6,
            toggle("Список", !homeworkTiles, () -> { homeworkTiles = false; drawApp(); }),
            toggle("Плитки", homeworkTiles, () -> { homeworkTiles = true; drawApp(); }));
        viewModes.setAlignment(Pos.CENTER_RIGHT);
        HBox actions = row(9, sortButton, viewModes);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox toolbar = row(10, filters, spacer(), actions);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinWidth(895);
        ScrollPane toolbarScroll = new ScrollPane(toolbar);
        toolbarScroll.setFitToWidth(true);
        toolbarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        toolbarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        toolbarScroll.setPannable(true);
        toolbarScroll.setPrefHeight(62);
        toolbarScroll.setMinHeight(62);
        toolbarScroll.setStyle("-fx-background: " + palette().bg + "; -fx-background-color: transparent; -fx-border-width: 0;");
        out.add(toolbarScroll);
        addWarning(out, "homework");

        List<Homework> visible = data.homework().stream().filter(task -> switch (homeworkFilter) {
            case "Активно" -> task.status() == HomeworkStatus.pending;
            case "Просрочено" -> task.status() == HomeworkStatus.overdue;
            case "На проверке" -> task.status() == HomeworkStatus.review;
            case "Проверено" -> task.status() == HomeworkStatus.done;
            default -> task.status() != HomeworkStatus.deleted;
        }).toList();
        if (visible.isEmpty()) {
            out.add(blankState("Заданий с выбранным статусом нет."));
            return;
        }
        for (Map.Entry<HomeworkStatus, List<Homework>> group : JournalFilters.groupedHomework(visible, homeworkSort).entrySet()) {
            HomeworkStatus status = group.getKey();
            List<Homework> tasks = group.getValue();
            VBox section = column(13);
            section.setPadding(new Insets(17, 18, 19, 18));
            section.setStyle(boxStyle(palette().surface, 15, true));
            HBox heading = row(12);
            heading.setAlignment(Pos.CENTER_LEFT);
            Region accentBar = new Region();
            accentBar.setMinWidth(5);
            accentBar.setPrefWidth(5);
            accentBar.setMinHeight(25);
            accentBar.setStyle("-fx-background-color: " + statusColor(status) + "; -fx-background-radius: 4;");
            heading.getChildren().addAll(accentBar, title(status.label(), 18),
                pill(String.valueOf(tasks.size()), statusColor(status), statusBackground(status)));
            section.getChildren().add(heading);

            if (homeworkSort == JournalFilters.HomeworkSort.subject) {
                for (Map.Entry<String, List<Homework>> bySubject : JournalFilters.groupedBySubject(tasks).entrySet()) {
                    VBox subjectBlock = column(9);
                    subjectBlock.setPadding(new Insets(11, 0, 3, 0));
                    HBox header = row(10, title(bySubject.getKey(), 14),
                        muted("· " + bySubject.getValue().size(), 12));
                    header.setAlignment(Pos.CENTER_LEFT);
                    subjectBlock.getChildren().add(header);
                    subjectBlock.getChildren().add(homeworkItems(bySubject.getValue(), true));
                    section.getChildren().add(subjectBlock);
                }
            } else {
                section.getChildren().add(homeworkItems(tasks, false));
            }
            out.add(section);
        }
    }

    private Pane homeworkItems(List<Homework> tasks, boolean groupedBySubject) {
        if (homeworkTiles) {
            TilePane tiles = new TilePane();
            tiles.setHgap(12);
            tiles.setVgap(12);
            tiles.setPrefColumns(3);
            tiles.setMaxWidth(Double.MAX_VALUE);
            tiles.setTileAlignment(Pos.TOP_LEFT);
            for (Homework task : tasks) {
                VBox tile = homeworkCard(task, true, !groupedBySubject);
                tile.setMinWidth(0);
                tile.setMaxWidth(Double.MAX_VALUE);
                tiles.getChildren().add(tile);
            }
            // Подгоняем ширину всех плиток под ширину доступного пространства.
            // Максимум 3 колонки, при уменьшении окна — 2 или 1.
            tiles.widthProperty().addListener((observable, oldWidth, newWidth) -> {
                double width = newWidth.doubleValue();
                if (width < 1) return;
                int columns = width < 440 ? 1 : width < 735 ? 2 : 3;
                tiles.setPrefColumns(columns);
                tiles.setPrefTileWidth(Math.max(1, (width - (columns - 1) * 12) / columns));
            });
            return tiles;
        }
        VBox rows = column(9);
        rows.setFillWidth(true);
        for (Homework task : tasks) rows.getChildren().add(homeworkCard(task, false, !groupedBySubject));
        return rows;
    }

    private String statusColor(HomeworkStatus status) {
        Palette palette = palette();
        return switch (status) {
            case overdue -> palette.red;
            case review -> palette.accent;
            case pending -> palette.green;
            case done -> palette.muted;
            case deleted -> palette.muted;
        };
    }

    private String statusBackground(HomeworkStatus status) {
        Palette palette = palette();
        return switch (status) {
            case overdue -> palette.redDim;
            case review -> palette.accentDim;
            case pending -> palette.greenDim;
            case done, deleted -> palette.raised;
        };
    }

    private VBox homeworkCard(Homework homework, boolean tile, boolean showSubject) {
        Palette palette = palette();
        VBox card = column(tile ? 9 : 11);
        card.setPadding(new Insets(tile ? 15 : 16));
        card.setStyle(boxStyle(palette.raised, 12, false));
        if (showSubject) {
            HBox heading = row(9, pill(homework.subject(), palette.accent, palette.accentDim), spacer());
            heading.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(heading);
        }
        Label name = title(homework.title(), tile ? 14 : 16);
        card.getChildren().add(name);
        if (homework.description() != null && !homework.description().isBlank()) {
            Label description = muted(homework.description(), 12);
            if (tile) description.setMaxHeight(43);
            card.getChildren().add(description);
        }
        Label due = text("Сдать: " + dateOrDash(homework.due()), 12, true,
            homework.status() == HomeworkStatus.overdue ? palette.red : palette.muted);
        card.getChildren().add(due);
        if (!tile && homework.teacher() != null && !homework.teacher().isBlank()) {
            card.getChildren().add(muted(homework.teacher(), 12));
        }
        if (homework.grade() != null) {
            card.getChildren().add(pill("Оценка: " + homework.grade(), palette.green, palette.greenDim));
        }
        if (homework.teacherComment() != null && !homework.teacherComment().isBlank()) {
            Label comment = muted("Комментарий: " + homework.teacherComment(), 12);
            card.getChildren().add(comment);
        }
        installHomeworkOpen(card, homework);
        return card;
    }

    private Label statusChip(HomeworkStatus status) {
        return pill(status.label(), statusColor(status), statusBackground(status));
    }

    private VBox homeworkShort(Homework hw) {
        VBox box = column(7);
        box.setPadding(new Insets(12));
        box.setStyle(boxStyle(palette().raised, 11, false));
        HBox top = row(8, text(hw.subject(), 11, true, palette().accent), spacer(), statusChip(hw.status()));
        top.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(top, title(hw.title(), 12), muted("До " + dateOrDash(hw.due()), 11));
        installHomeworkOpen(box, hw);
        return box;
    }

    private void installHomeworkOpen(VBox target, Homework task) {
        target.setCursor(Cursor.HAND);
        target.setFocusTraversable(true);
        target.setAccessibleText("Открыть домашнее задание: " + task.title());
        String ordinary = boxStyle(palette().raised, 12, false) + " -fx-cursor: hand;";
        String highlighted = boxStyle(palette().accentDim, 12, false) + " -fx-cursor: hand;";
        target.setStyle(ordinary);
        target.setOnMouseEntered(event -> target.setStyle(highlighted));
        target.setOnMouseExited(event -> target.setStyle(ordinary));
        target.setOnMouseClicked(event -> openHomeworkDetails(task));
        target.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                openHomeworkDetails(task);
                event.consume();
            }
        });
    }

    /** Детали задания открываются одинаково из списка, сетки и главной страницы. */
    private void openHomeworkDetails(Homework task) {
        Palette colors = palette();
        Stage details = new Stage();
        details.initOwner(stage);
        details.initModality(Modality.WINDOW_MODAL);
        details.setTitle(task.title() + " — JavaJournal");
        details.setMinWidth(550);
        details.setMinHeight(470);
        try (var image = JavaJournalApp.class.getResourceAsStream("/icons/javajournal.png")) {
            if (image != null) details.getIcons().add(new Image(image));
        } catch (java.io.IOException ignored) { }

        VBox body = column(16);
        body.setPadding(new Insets(26, 30, 32, 30));
        body.setStyle("-fx-background-color: " + colors.bg + ";");

        HBox header = row(10, pill(task.subject(), colors.accent, colors.accentDim),
            spacer(), statusChip(task.status()));
        header.setAlignment(Pos.CENTER_LEFT);
        Label heading = title(task.title(), 24);
        body.getChildren().addAll(header, heading);

        HBox attributes = row(14);
        attributes.getChildren().add(pill("Сдать: " + dateOrDash(task.due()), colors.muted, colors.surface));
        if (task.grade() != null) attributes.getChildren().add(pill("Оценка: " + task.grade(), colors.green, colors.greenDim));
        body.getChildren().add(attributes);
        if (task.teacher() != null && !task.teacher().isBlank()) {
            body.getChildren().add(muted("Преподаватель: " + task.teacher(), 12));
        }

        VBox description = card(8);
        description.getChildren().addAll(title("Задание", 16),
            text(task.description() == null || task.description().isBlank()
                ? "Описание не добавлено." : task.description(), 13, false, colors.text));
        body.getChildren().add(description);

        if (task.teacherComment() != null && !task.teacherComment().isBlank()) {
            VBox feedback = card(9);
            feedback.getChildren().addAll(title("Комментарий преподавателя", 16),
                text(task.teacherComment(), 13, false, colors.text));
            body.getChildren().add(feedback);
        }

        // Материалы преподавателя показываем во ВСЕХ статусах.
        VBox teacherMaterials = card(10);
        teacherMaterials.getChildren().add(title("Материалы преподавателя", 16));
        attachmentRow(teacherMaterials, "Файл задания", task.teacherFileUrl(), task.teacherFileName(),
            "задание", details);
        body.getChildren().add(teacherMaterials);

        // Отправленная работа и форма новой отправки взаимоисключающие.
        boolean alreadySubmitted = task.status() == HomeworkStatus.review || task.status() == HomeworkStatus.done;
        if (alreadySubmitted) {
            VBox submitted = card(12);
            submitted.getChildren().add(title("Моя отправленная работа", 16));
            attachmentRow(submitted, "Прикреплённый файл", task.studentFileUrl(), task.studentFileName(),
                "моя-работа", details);
            if (task.studentAnswer() != null && !task.studentAnswer().isBlank()) {
                submitted.getChildren().addAll(muted("Текстовый ответ", 12),
                    text(task.studentAnswer(), 13, false, colors.text));
            }
            body.getChildren().add(submitted);
            if (task.status() == HomeworkStatus.review) {
                Button delete = subtle("Удалить работу", () -> { });
                delete.setDisable(true);
                delete.setTooltip(new Tooltip("Удаление будет подключено после уточнения API."));
                full(delete);
                body.getChildren().add(delete);
            } else {
                Button retake = button("Запрос на пересдачу", false, () -> { });
                retake.setDisable(true);
                retake.setTooltip(new Tooltip("Запрос на пересдачу будет подключён после уточнения API."));
                full(retake);
                body.getChildren().add(retake);
            }
        } else {
        VBox answer = card(12);
        answer.getChildren().add(title("Мой ответ", 16));
        TextArea reply = new TextArea();
        reply.setWrapText(true);
        reply.setPromptText("Текстовый ответ на домашнее задание...");
        reply.setPrefRowCount(4);
        reply.setMinHeight(90);
        reply.setStyle("-fx-control-inner-background: " + colors.raised + "; "
            + "-fx-background-color: " + colors.raised + "; -fx-text-fill: " + colors.text + "; "
            + "-fx-prompt-text-fill: " + colors.muted + "; -fx-font-size: 13px; "
            + "-fx-background-radius: 10; -fx-border-color: " + colors.border + "; -fx-border-radius: 10;");
        answer.getChildren().add(reply);

        Label selectedLabel = muted("Файл не выбран", 12);
        Button selectFile = subtle("Выбрать файл", () -> { });
        selectFile.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Выберите выполненную домашнюю работу");
            File selected = chooser.showOpenDialog(details);
            if (selected == null) return;
            String lower = selected.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".txt") || lower.endsWith(".csv")) {
                selectedLabel.setText("Форматы .txt и .csv не принимаются официальным журналом.");
                selectedLabel.setStyle("-fx-text-fill: " + colors.red + "; -fx-font-size: 12px;");
                return;
            }
            selectedLabel.setText(selected.getName() + " — выбран локально, не отправлен");
            selectedLabel.setStyle("-fx-text-fill: " + colors.muted + "; -fx-font-size: 12px;");
        });
        HBox selectRow = row(12, selectFile, selectedLabel);
        selectRow.setAlignment(Pos.CENTER_LEFT);
        answer.getChildren().add(selectRow);

        Label timeLabel = muted("Время выполнения (чч:мм)", 12);
        TextField duration = new TextField();
        duration.setPromptText("00:00");
        duration.setMaxWidth(135);
        styleInput(duration);
        HBox timing = row(14, timeLabel, duration);
        timing.setAlignment(Pos.CENTER_LEFT);
        answer.getChildren().add(timing);

        HBox rating = row(7);
        rating.setAlignment(Pos.CENTER_LEFT);
        rating.getChildren().add(muted("Оценка полезности занятия", 12));
        final int[] chosenScore = {0};
        List<Button> ratingButtons = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int value = i;
            Button point = subtle("○", () -> { });
            point.setMinWidth(30);
            point.setPrefWidth(30);
            point.setPadding(new Insets(5));
            point.setOnAction(event -> {
                chosenScore[0] = value;
                for (int position = 0; position < ratingButtons.size(); position++) {
                    ratingButtons.get(position).setText(position < value ? "●" : "○");
                }
            });
            ratingButtons.add(point);
            rating.getChildren().add(point);
        }
        answer.getChildren().add(rating);

        Button disabledSend = button("Отправить на проверку", true, () -> { });
        disabledSend.setDisable(true);
        disabledSend.setTooltip(new Tooltip("Отправка пока недоступна: требуется проверенный API-метод"));
        full(disabledSend);
        answer.getChildren().addAll(disabledSend,
            muted("Отправка пока недоступна. Текст и выбранный файл не передаются на сервер.", 12));
        body.getChildren().add(answer);

        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: " + colors.bg + "; -fx-background-color: " + colors.bg + "; -fx-border-width: 0;");
        details.setScene(new Scene(scroll, 720, 745));
        details.show();
    }

    private void attachmentRow(VBox target, String caption, String url, String apiFilename,
                               String fallbackName, Stage owner) {
        HBox line = row(12);
        line.setAlignment(Pos.CENTER_LEFT);
        VBox labels = column(4);
        labels.getChildren().add(text(caption, 13, true, palette().text));
        if (url == null || url.isBlank()) {
            labels.getChildren().add(muted("Файл не указан сервером", 12));
            line.getChildren().add(labels);
            target.getChildren().add(line);
            return;
        }
        String initialName = AttachmentLinks.resolveFilename(url, apiFilename, "", "", fallbackName);
        Label nameLabel = muted(displayAttachmentName(initialName), 12);
        labels.getChildren().add(nameLabel);
        HBox.setHgrow(labels, Priority.ALWAYS);
        line.getChildren().add(labels);
        if (AttachmentLinks.isSafeHttpsLink(url)) {
            Button download = button("Скачать", false, () -> { });
            Label feedback = muted("", 11);
            String[] currentName = {initialName};
            download.setOnAction(event -> saveAttachment(url, apiFilename, currentName[0],
                owner, download, feedback));
            line.getChildren().add(download);
            target.getChildren().addAll(line, feedback);
            // Имена файлов и MIME-тип узнаём в фоне, не задерживая открытие карточки.
            JournalSource activeSource = source;
            if (activeSource != null) {
                CompletableFuture.supplyAsync(() -> {
                    try { return activeSource.inspectAttachment(url, apiFilename); }
                    catch (Exception error) { return new AttachmentInfo(initialName, ""); }
                }, executor).thenAccept(info -> Platform.runLater(() -> {
                    currentName[0] = info.filename();
                    nameLabel.setText(displayAttachmentName(info.filename()));
                }));
            }
        } else {
            line.getChildren().add(muted("Ссылка недоступна по HTTPS", 11));
            target.getChildren().add(line);
        }
    }

    private static String displayAttachmentName(String name) {
        if (name == null || name.isBlank()) return "Файл · имя не указано";
        String lower = name.toLowerCase(Locale.ROOT);
        String stem = lower.replaceFirst("\\.[a-z0-9]{1,10}$", "");
        if (stem.equals("вложение") || stem.equals("attachment")) {
            int dot = name.lastIndexOf('.');
            String type = dot < 0 ? "" : " " + name.substring(dot + 1).toUpperCase(Locale.ROOT);
            return "Файл" + type + " · исходное имя не указано";
        }
        return name;
    }

    private void saveAttachment(String url, String apiFilename, String filename, Stage owner,
                                Button trigger, Label feedback) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Выберите папку для скачивания файла");
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        if (downloads.isDirectory()) chooser.setInitialDirectory(downloads);
        File folder = chooser.showDialog(owner);
        if (folder == null) return;
        JournalSource activeSource = source;
        if (activeSource == null) return;
        trigger.setDisable(true);
        feedback.setText("Скачиваем файл...");
        CompletableFuture.supplyAsync(() -> {
            try { return activeSource.downloadAttachmentToDirectory(url, folder.toPath(), apiFilename); }
            catch (Exception error) { throw new CompletionException(error); }
        }, executor).whenComplete((savedPath, failure) -> Platform.runLater(() -> {
            if (!owner.isShowing()) return;
            trigger.setDisable(false);
            if (failure != null) {
                feedback.setStyle("-fx-text-fill: " + palette().red + "; -fx-font-size: 11px;");
                feedback.setText("Ошибка: " + safeError(failure));
            } else {
                feedback.setStyle("-fx-text-fill: " + palette().green + "; -fx-font-size: 11px;");
                feedback.setText("Сохранено: " + savedPath.getFileName());
            }
        }));
    }

    private HBox periodControls() {
        HBox controls = row(10);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox modes = row(6,
            toggle("Месяц", statsPeriod == JournalFilters.Period.month, () -> {
                statsPeriod = JournalFilters.Period.month;
                statsDate = LocalDate.now();
                drawApp();
            }),
            toggle("Учебный год", statsPeriod == JournalFilters.Period.academicYear, () -> {
                statsPeriod = JournalFilters.Period.academicYear;
                statsDate = LocalDate.now();
                drawApp();
            }));
        Button previous = subtle("‹", () -> {
            statsDate = JournalFilters.shift(statsDate, statsPeriod, -1);
            drawApp();
        });
        Button next = subtle("›", () -> {
            statsDate = JournalFilters.shift(statsDate, statsPeriod, 1);
            drawApp();
        });
        Label current = title(statsPeriodLabel(), 14);
        current.setMinWidth(190);
        current.setAlignment(Pos.CENTER);
        controls.getChildren().addAll(modes, previous, current, next);
        return controls;
    }

    private String statsPeriodLabel() {
        if (statsPeriod == JournalFilters.Period.month) {
            String month = statsDate.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, russianLocale);
            return capitalize(month) + " " + statsDate.getYear();
        }
        LocalDate start = JournalFilters.periodStart(statsDate, statsPeriod);
        return start.getYear() + "–" + start.plusYears(1).getYear();
    }

    private void grades(List<Node> out) {
        out.add(periodControls());
        addWarning(out, "grades");
        List<Grade> grades = JournalFilters.grades(data.grades(), statsDate, statsPeriod);
        double average = grades.stream().mapToInt(Grade::value).average().orElse(0);
        HBox summary = row(14);
        VBox avg = metric("Средний балл", grades.isEmpty() ? "—" : String.format(russianLocale, "%.2f", average),
            "За выбранный период", palette().green);
        VBox count = metric("Всего оценок", String.valueOf(grades.size()), "За выбранный период", palette().accent);
        grow(avg); grow(count);
        summary.getChildren().addAll(avg, count);
        out.add(summary);
        VBox list = card(12);
        list.getChildren().add(title("История оценок", 17));
        if (grades.isEmpty()) list.getChildren().add(muted("В выбранном периоде оценок нет.", 13));
        for (Grade grade : grades) {
            HBox line = row(13);
            line.setAlignment(Pos.CENTER_LEFT);
            Label mark = text(String.valueOf(grade.value()), 19, true, palette().green);
            mark.setMinWidth(50);
            mark.setAlignment(Pos.CENTER);
            mark.setPadding(new Insets(12));
            mark.setStyle(mark.getStyle() + " -fx-background-color: " + palette().greenDim + "; -fx-background-radius: 11;");
            VBox detail = column(4, title(grade.subject(), 14),
                muted(grade.type() + "  ·  " + blank(grade.theme(), "Тема не указана"), 12));
            grow(detail);
            line.getChildren().addAll(mark, detail, muted(dateOrDash(grade.date()), 12));
            list.getChildren().add(line);
        }
        out.add(list);
    }

    private void attendance(List<Node> out) {
        out.add(periodControls());
        addWarning(out, "attendance");
        List<Attendance> rows = JournalFilters.attendance(data.attendanceEntries(), statsDate, statsPeriod);
        int visited = rows.stream().mapToInt(Attendance::visited).sum();
        int total = rows.stream().mapToInt(Attendance::total).sum();
        HBox totals = row(14);
        VBox first = metric("Посещаемость", total == 0 ? "—" : String.format(russianLocale, "%.1f%%", 100.0 * visited / total),
            "За выбранный период", palette().green);
        VBox second = metric("Посещено", visited + " / " + total, "Занятий", palette().accent);
        grow(first); grow(second);
        totals.getChildren().addAll(first, second);
        out.add(totals);
        VBox box = card(18);
        box.getChildren().add(title("По предметам", 17));
        if (rows.isEmpty()) {
            box.getChildren().add(muted(data.attendanceEntries().isEmpty() && !data.attendance().isEmpty()
                ? "Сервер не предоставил даты посещений — статистику по периоду определить нельзя."
                : "В выбранном периоде занятий нет.", 13));
        }
        for (Attendance entry : rows) {
            VBox item = column(9);
            HBox line = row(10, title(entry.subject(), 13), spacer(),
                muted(entry.visited() + " / " + entry.total(), 12),
                text(String.format(russianLocale, "%.0f%%", entry.percent()), 12, true, palette().muted));
            line.setAlignment(Pos.CENTER_LEFT);
            HBox track = new HBox();
            track.setAlignment(Pos.CENTER_LEFT);
            track.setPrefHeight(7);
            track.setMinHeight(7);
            track.setMaxHeight(7);
            track.setMaxWidth(Double.MAX_VALUE);
            track.setStyle("-fx-background-color: " + palette().raised + "; -fx-background-radius: 10;");
            Region filled = new Region();
            filled.setMinHeight(7);
            filled.setMaxHeight(7);
            filled.setStyle("-fx-background-color: " + (light ? "#6F9C9F" : "#486D85") + "; -fx-background-radius: 10;");
            filled.prefWidthProperty().bind(track.widthProperty().multiply(Math.max(0, Math.min(1, entry.percent() / 100.0))));
            track.getChildren().add(filled);
            item.getChildren().addAll(line, track);
            box.getChildren().add(item);
        }
        out.add(box);
    }

    private void profile(List<Node> out) {
        Profile who = data.profile();
        VBox header = card(18);
        HBox info = row(19);
        info.setAlignment(Pos.CENTER_LEFT);
        StackPane avatar = markIcon(76);
        info.getChildren().addAll(avatar, column(7, title(who.name(), 23), muted(who.group(), 14)));
        header.getChildren().add(info);
        out.add(header);

        VBox updates = card(13);
        updates.getChildren().add(title("Обновления", 17));
        Label repository = muted(DesktopVersion.repository().isBlank()
            ? "Репозиторий GitHub не указан" : DesktopVersion.repository(), 12);
        updates.getChildren().add(repository);
        Label result = muted("", 12);
        Button check = subtle("Проверить обновления", () -> checkUpdates(true, result));
        Button configure = subtle("Репозиторий GitHub", () -> configureUpdateRepository(repository, result));
        HBox actions = row(10, check, configure);
        actions.setAlignment(Pos.CENTER_LEFT);
        updates.getChildren().addAll(actions, result);
        out.add(updates);

        Region filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);
        out.add(filler);
        HBox footer = row(8, muted("Версия " + DesktopVersion.current(), 11));
        footer.setAlignment(Pos.CENTER_RIGHT);
        out.add(footer);
    }

    private void configureUpdateRepository(Label repositoryLabel, Label result) {
        TextInputDialog dialog = new TextInputDialog(DesktopVersion.repository());
        dialog.initOwner(stage);
        dialog.setTitle("Обновления JavaJournal");
        dialog.setHeaderText("Публичный репозиторий GitHub");
        dialog.setContentText("owner/repo:");
        dialog.showAndWait().ifPresent(value -> {
            try {
                DesktopVersion.saveRepository(value);
                repositoryLabel.setText(DesktopVersion.repository());
                result.setText("Репозиторий сохранён. Проверяем обновления...");
                checkUpdates(true, result);
            } catch (IllegalArgumentException error) {
                result.setText(error.getMessage());
            }
        });
    }

    private void checkUpdates(boolean manual, Label feedback) {
        if (checkingUpdates) {
            if (feedback != null) feedback.setText("Проверка уже выполняется...");
            return;
        }
        String repository = DesktopVersion.repository();
        if (repository.isBlank()) {
            if (feedback != null) feedback.setText("Сначала укажите репозиторий GitHub.");
            return;
        }
        checkingUpdates = true;
        if (feedback != null) feedback.setText("Проверяем GitHub Releases...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return ReleaseUpdates.checkLatest(repository, DesktopVersion.current(),
                    System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor).whenComplete((release, failure) -> Platform.runLater(() -> {
            checkingUpdates = false;
            if (failure != null) {
                // Ошибка сети не влияет на запуск или авторизацию.
                if (feedback != null) feedback.setText("Не удалось проверить: " + safeError(failure));
                return;
            }
            if (!release.available()) {
                if (feedback != null) feedback.setText("Установлена актуальная версия.");
                return;
            }
            if (feedback != null) feedback.setText("Доступна версия " + release.version());
            if (!manual && updateNoticeShown) return;
            updateNoticeShown = true;
            showUpdatePrompt(release);
        }));
    }

    private void showUpdatePrompt(ReleaseUpdates.Release release) {
        boolean hasAsset = !release.downloadUrl().isBlank();
        ButtonType download = new ButtonType(hasAsset ? "Скачать обновление" : "Открыть релиз", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Позже", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("Обновление JavaJournal");
        alert.setHeaderText("Доступна версия " + release.version());
        alert.setContentText(hasAsset
            ? "Новая версия будет скачана через браузер. После загрузки распакуйте её вместо старой версии."
            : "Для этой платформы не найден готовый ZIP. Можно открыть страницу релиза.");
        alert.getButtonTypes().setAll(download, later);
        alert.showAndWait().filter(selected -> selected == download).ifPresent(selected -> {
            // Скачивание через браузер, никакой автоматической замены EXE или запуска файлов.
            getHostServices().showDocument(hasAsset ? release.downloadUrl() : release.pageUrl());
        });
    }

    private void addWarningTo(VBox box, String key) {
        String error = data.warnings().get(key);
        if (error != null) box.getChildren().add(text("Недоступно: " + error, 11, false, palette().amber));
    }
    private static String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String dateOrDash(LocalDate date) { return date == null ? "не указано" : date.format(shortDate); }
    private static String capitalize(String s) { return s.isBlank() ? s : s.substring(0, 1).toUpperCase(russianLocale) + s.substring(1); }

    public static void main(String[] args) { launch(args); }
}

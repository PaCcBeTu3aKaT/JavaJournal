package ru.javajournal.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MultipartBody;
import ru.javajournal.core.service.WorkSubmission;
import okhttp3.Response;
import ru.javajournal.core.model.*;
import ru.javajournal.core.service.JournalSource;
import ru.javajournal.core.service.AttachmentInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import ru.javajournal.core.service.AttachmentLinks;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Перенос документированных в исходном JS-JOURNAL запросов к API TOP Academy.
 * ВАЖНО: API сторонний, совместимость не подтверждена реальной авторизацией.
 * Passwords are handled by platform credential stores, never logged by this client.
 */
public final class TopAcademySource implements JournalSource {
    private static final String baseUrl = "https://msapi.top-academy.ru/api/v2";
    // Приложение-ключ взят из предоставленного пользователем открытого JS-JOURNAL.
    private static final String applicationKey = "6a56a5df2667e65aab73ce76d1dd737f7d1faef9c52e8b8c55ac75f565d8e8a6";
    private static final MediaType jsonMediaType = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final Pattern timePattern = Pattern.compile("(\\d{1,2}:\\d{2})");
    private static final long cacheTtl = TimeUnit.MINUTES.toMillis(2);

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build();
    private final OkHttpClient attachmentHttp = http.newBuilder()
        .followRedirects(false).followSslRedirects(false).build();
    private final Map<String, Cached> cache = new HashMap<>();
    private final String token;
    private final Profile profile;
    private final String groupId;

    private record Cached(long at, Object value) { }

    private TopAcademySource(String token, JsonNode info) {
        this.token = token;
        JsonNode group = pick(info, "current_group");
        String name = text(pick(group, "full_name", "fio", "student_name", "name"));
        if (name.isBlank()) name = text(pick(info, "full_name", "fio", "student_name", "name"));
        String groupName = text(pick(group, "name", "group_name", "title"));
        if (groupName.isBlank()) groupName = text(pick(info, "group_name", "current_group_name"));
        String stream = text(pick(group, "stream_name", "stream"));
        if (stream.isBlank()) stream = text(pick(info, "stream_name", "stream"));
        String level = text(pick(group, "level", "level_name"));
        if (level.isBlank()) level = text(pick(info, "level", "level_name"));
        String givenName = text(pick(info, "first_name", "firstName", "given_name", "firstname", "student_first_name"));
        if (givenName.isBlank()) {
            JsonNode student = pick(info, "student", "user", "student_info");
            givenName = text(pick(student, "first_name", "firstName", "given_name", "firstname"));
        }
        this.profile = new Profile(name, groupName, stream, level, givenName);
        String id = text(pick(info, "current_group_id", "group_id", "id_group"));
        if (id.isBlank()) id = text(pick(group, "id", "group_id", "id_group"));
        if (id.isBlank()) {
            JsonNode groups = pick(info, "groups");
            if (groups.isArray() && !groups.isEmpty()) id = text(pick(groups.get(0), "id", "group_id"));
        }
        this.groupId = id;
    }

    /** Выполнять только в рабочем потоке: здесь есть сетевые запросы. */
    public static TopAcademySource login(String username, String password) throws IOException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IOException("Введите логин и пароль.");
        }
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("application_key", applicationKey);
        payload.putNull("id_city");
        payload.put("username", username.strip());
        payload.put("password", password);
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build();
        Request request = baseRequest(baseUrl + "/auth/login")
            .post(RequestBody.create(payload.toString(), jsonMediaType)).build();
        JsonNode response = execute(client, request);
        String token = findToken(response, 0);
        if (token.isBlank()) throw new IOException("Сервер не вернул токен доступа. Возможно, формат API изменился.");
        // В отличие от старого Python-клиента здесь не создаётся общий сервер сессий.
        TopAcademySource provisional = new TopAcademySource(token, jsonMapper.createObjectNode());
        JsonNode info = provisional.get("/settings/user-info", null);
        if (info.has("data") && info.get("data").isObject()) info = info.get("data");
        return new TopAcademySource(token, info);
    }

    /** Восстанавливает сессию по сохранённому токену; пароль повторно не передаётся. */
    public static TopAcademySource restoreSession(String savedToken) throws IOException {
        if (savedToken == null || savedToken.isBlank()) {
            throw new AuthException("Сохранённая сессия отсутствует.");
        }
        TopAcademySource provisional = new TopAcademySource(savedToken, jsonMapper.createObjectNode());
        JsonNode info = provisional.get("/settings/user-info", null);
        if (info.has("data") && info.get("data").isObject()) info = info.get("data");
        return new TopAcademySource(savedToken, info);
    }

    /** Только для безопасного хранения в Android Keystore. */
    public String sessionToken() { return token; }

    @Override public Profile profile() { return profile; }
    @Override public void refresh() { synchronized (cache) { cache.clear(); } }

    @Override public JournalData load(LocalDate date, boolean week) throws IOException {
        Map<String, String> warnings = new HashMap<>();
        List<Lesson> lessons;
        try { lessons = lessons(date, week); }
        catch (IOException e) { if (isAuth(e)) throw e; lessons = List.of(); warnings.put("schedule", e.getMessage()); }
        List<Homework> homework;
        try { homework = loadHomework(0); }
        catch (IOException e) { if (isAuth(e)) throw e; homework = List.of(); warnings.put("homework", e.getMessage()); }
        List<Grade> grades = new ArrayList<>();
        List<Attendance> attendance = new ArrayList<>();
        List<AttendanceEntry> attendanceEntries = new ArrayList<>();
        try { parseVisits(cachedGet("visits", "/progress/operations/student-visits", null), grades, attendance, attendanceEntries); }
        catch (IOException e) { if (isAuth(e)) throw e; warnings.put("grades", e.getMessage()); warnings.put("attendance", e.getMessage()); }
        // Do not synthesize grades from homework: student-visits already contains
        // home_work_mark and duplicate synthetic grades caused two entries for one mark.
        grades.sort(Comparator.comparing(Grade::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return new JournalData(date, week, profile, lessons, homework, grades, attendance, attendanceEntries, warnings);
    }

    @Override public JournalData loadCalendarMonth(java.time.YearMonth month) throws IOException {
        java.time.LocalDate start=month.atDay(1),end=month.atEndOfMonth();
        List<Lesson> monthLessons=new ArrayList<>();
        Map<String,String> warnings=new HashMap<>();
        try {
            parseLessons(cachedGet("month:"+month,"/schedule/operations/get-month",
                Map.of("date_filter",start.toString())),start,end,monthLessons);
            monthLessons.sort(Comparator.comparing(Lesson::date).thenComparing(Lesson::start));
        } catch(IOException e){if(isAuth(e))throw e;warnings.put("schedule",e.getMessage());}
        List<Grade> grades=new ArrayList<>();
        List<Attendance> totals=new ArrayList<>();
        List<AttendanceEntry> visits=new ArrayList<>();
        try{parseVisits(cachedGet("visits","/progress/operations/student-visits",null),grades,totals,visits);}
        catch(IOException e){if(isAuth(e))throw e;warnings.put("attendance",e.getMessage());}
        return new JournalData(start,true,profile,monthLessons,List.of(),grades,totals,visits,warnings);
    }

    private List<Lesson> lessons(LocalDate date, boolean week) throws IOException {
        List<Lesson> result = new ArrayList<>();
        LocalDate start = week ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
        LocalDate end = week ? start.plusDays(6) : start;
        if (week) {
            JsonNode month = cachedGet("month:" + start, "/schedule/operations/get-month", Map.of("date_filter", start.toString()));
            parseLessons(month, start, end, result);
            if (end.getMonth() != start.getMonth()) {
                JsonNode nextMonth = cachedGet("month:" + end, "/schedule/operations/get-month", Map.of("date_filter", end.toString()));
                parseLessons(nextMonth, start, end, result);
            }
            // Некоторые API возвращают структуру календаря вместо массива занятий.
            if (result.isEmpty()) {
                for (int i = 0; i < 7; i++) {
                    LocalDate day = start.plusDays(i);
                    parseLessons(cachedGet("day:" + day, "/schedule/operations/get-by-date", Map.of("date_filter", day.toString())), day, day, result);
                }
            }
        } else {
            parseLessons(cachedGet("day:" + date, "/schedule/operations/get-by-date", Map.of("date_filter", date.toString())), date, date, result);
        }
        Set<String> seen = new HashSet<>();
        result.removeIf(l -> !seen.add(l.date() + ":" + l.start() + ":" + l.subject()));
        result.sort(Comparator.comparing(Lesson::date).thenComparing(Lesson::start));
        return result;
    }

    private void parseLessons(JsonNode root, LocalDate from, LocalDate to, List<Lesson> result) {
        List<JsonNode> items = extractList(root, "lessons", "lesson_list", "schedule", "days", "items", "days_lessons", "lessons_list");
        int idx = result.size();
        for (JsonNode item : items) {
            LocalDate d = parseDate(text(pick(item, "date", "date_lesson", "day", "lesson_date")));
            if (d == null) d = from.equals(to) ? from : null;
            if (d == null || d.isBefore(from) || d.isAfter(to)) continue;
            String subject = text(pick(item, "spec_name", "subject_name", "name_spec", "subject", "name", "discipline_name"));
            if (subject.isBlank()) continue;
            result.add(new Lesson(textOr(pick(item, "id", "lesson_id"), "lesson-" + idx++), d, subject,
                timeText(pick(item, "started_at", "time_start", "start_time", "begin_time", "start")),
                timeText(pick(item, "finished_at", "time_end", "end_time", "finish_time", "end")),
                text(pick(item, "fio_teach", "teacher_name", "teacher_fio", "teacher", "teachers")),
                text(pick(item, "auditory_name", "room_name", "auditory", "room", "cabinet"))));
        }
    }

    @Override public List<Homework> loadHomework(int type) throws IOException {
        if (type != 0 && type != 1) throw new IOException("Неизвестный тип работы.");
        String key = "homework:models:" + type;
        synchronized (cache) {
            Cached found = cache.get(key);
            if (found != null && System.currentTimeMillis() - found.at < cacheTtl) {
                @SuppressWarnings("unchecked") List<Homework> value = (List<Homework>) found.value;
                return value;
            }
        }
        if (groupId.isBlank()) throw new IOException("Не удалось определить учебную группу.");
        List<Homework> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int status : new int[]{3, 2, 0, 1, 5}) {
            for (int page = 1; page <= 100; page++) {
                JsonNode root;
                try {
                    root = get("/homework/operations/list", Map.of(
                        "page", page, "status", status, "type", type, "group_id", groupId));
                } catch (IOException e) {
                    if (isAuth(e)) throw e;
                    if (page == 1 && result.isEmpty()) continue;
                    break;
                }
                List<JsonNode> items = extractList(root, "homework_list", "homework", "items", "tasks");
                if (items.isEmpty()) break;
                int added = 0;
                for (JsonNode item : items) {
                    String id = textOr(pick(item, "id"), "hw-" + result.size());
                    if (!seen.add(id)) continue;
                    result.add(parseHomework(item, status, id, type));
                    added++;
                }
                if (added == 0) break;
            }
        }
        result.sort(Comparator.comparingInt(h -> switch (h.status()) {
            case pending -> 0; case overdue -> 1; case review -> 2; case done -> 3; case deleted -> 4;
        }));
        List<Homework> snapshot = List.copyOf(result);
        synchronized (cache) { cache.put(key, new Cached(System.currentTimeMillis(), snapshot)); }
        return snapshot;
    }

    static Homework parseHomework(JsonNode node, int requestedStatus, String id) {
        return parseHomework(node, requestedStatus, id, 0);
    }
    static Homework parseHomework(JsonNode node, int requestedStatus, String id, int workType) {
        JsonNode stud = pick(node, "homework_stud");
        String markStr = text(pick(stud, "mark", "grade", "auto_mark"));
        if (markStr.isBlank()) markStr = text(pick(node, "mark", "grade"));
        Integer mark = parseMark(markStr);
        String raw = text(pick(node, "status", "common_status"));
        int st;
        try { st = raw.isBlank() ? requestedStatus : Integer.parseInt(raw); }
        catch (NumberFormatException e) { st = requestedStatus; }
        String statusName = text(pick(node, "status_name", "status_text")).toLowerCase();
        LocalDate due = parseDate(text(pick(node, "completion_time", "overdue_time")));
        HomeworkStatus status;
        if (st == 5 || statusName.contains("удал")) status = HomeworkStatus.deleted;
        else if (st == 1 || mark != null || statusName.contains("проверен")) status = HomeworkStatus.done;
        else if (st == 2 || statusName.contains("проверк")) status = HomeworkStatus.review;
        else if (st == 0 || statusName.contains("просроч")) status = HomeworkStatus.overdue;
        else status = HomeworkStatus.pending;
        String subject = textOr(pick(node, "name_spec", "spec_name", "subject_name"), "Предмет не указан");
        String title = textOr(pick(node, "theme", "name", "title"), "Домашнее задание");
        String desc = text(pick(node, "comment", "description", "task_text"));
        String teacher = text(pick(node, "fio_teach", "teacher_name"));
        JsonNode comment = pick(node, "homework_comment");
        String teacherComment = comment.isObject() ? text(pick(comment, "text_comment", "comment", "message")) : text(comment);
        JsonNode teacherAttachment = pick(node, "file_path", "file_url", "download_url", "attachment", "file");
        JsonNode studentAttachment = pick(stud, "file_path", "file_url", "download_url", "attachment", "file");
        String teacherFile = fileLink(teacherAttachment);
        String studentFile = fileLink(studentAttachment);
        String teacherName = text(pick(node, "file_name", "filename", "original_filename", "original_name", "name_file", "fileName"));
        String studentName = text(pick(stud, "file_name", "filename", "original_filename", "original_name", "name_file", "fileName"));
        if (teacherName.isBlank() && teacherAttachment.isObject()) {
            teacherName = text(pick(teacherAttachment, "file_name", "filename", "original_filename", "original_name", "name"));
        }
        if (studentName.isBlank() && studentAttachment.isObject()) {
            studentName = text(pick(studentAttachment, "file_name", "filename", "original_filename", "original_name", "name"));
        }
        String studentAnswer = text(pick(stud, "stud_answer", "text_answer", "answer_text", "answer", "text", "comment", "homework_text"));
        return new Homework(id, subject, title, desc, due, teacher, status, mark,
            teacherComment, teacherFile, studentFile, teacherName, studentName, studentAnswer, workType);
    }

    /** Never run on the UI thread. First creates the homework, then saves feedback.
     * A review failure returns a PARTIAL success, so the caller must never resend create.
     */
    @Override public WorkSubmission.Result submitWork(WorkSubmission.Form form) throws IOException {
        return submitWork(form,java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
    }
    @Override public WorkSubmission.Result submitWork(WorkSubmission.Form form, Path workDirectory) throws IOException {
        try (WorkSubmission.Prepared prepared = WorkSubmission.prepare(form,workDirectory)) {
            MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("id", form.homeworkId())
                .addFormDataPart("answerText", form.answer())
                .addFormDataPart("spentTimeHour", String.valueOf(form.hours()))
                .addFormDataPart("spentTimeMin", String.valueOf(form.minutes()));
            if (prepared.path() != null) body.addFormDataPart("file", prepared.filename(),
                RequestBody.create(prepared.path().toFile(), MediaType.parse("application/octet-stream")));
            Request req = baseRequest(baseUrl + "/homework/operations/create")
                .header("Authorization", "Bearer " + token).post(body.build()).build();
            JsonNode created = execute(http, req);
            String submissionId = text(pick(created, "id"));
            if (submissionId.isBlank()) throw new IOException("Сервер не подтвердил ID отправленной работы. Проверь статус в журнале перед повтором.");
            refresh();
            ObjectNode evaluation = jsonMapper.createObjectNode();
            evaluation.putNull("id");
            evaluation.put("idDomZad", Integer.parseInt(form.homeworkId()));
            evaluation.putNull("idStud");
            evaluation.put("mark", form.rating());
            evaluation.put("comment", form.comment());
            var tags = evaluation.putArray("tags");
            form.tags().forEach(tags::add);
            MultipartBody reviewBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("EvaluationHomeworkForm", evaluation.toString()).build();
            try {
                Request review = baseRequest(baseUrl + "/homework/evaluation/operations/save")
                    .header("Authorization", "Bearer " + token).post(reviewBody).build();
                execute(http, review);
                return new WorkSubmission.Result(submissionId, true, "Работа и отзыв отправлены.");
            } catch (IOException failure) {
                return new WorkSubmission.Result(submissionId, false,
                    "Работа принята сервером (ID " + submissionId + "), но отзыв не сохранился: " + failure.getMessage()
                    + ". Не отправляй работу повторно.");
            }
        }
    }

    private static String fileLink(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return text(node);
        if (node.isArray()) {
            String[] found = {""};
            node.forEach(child -> {
                if (found[0].isBlank()) found[0] = fileLink(child);
            });
            return found[0];
        }
        if (node.isObject()) {
            return fileLink(pick(node, "file_path", "file_url", "download_url", "url", "href", "link", "path"));
        }
        return "";
    }

    private static void parseVisits(JsonNode root, List<Grade> grades, List<Attendance> attendance, List<AttendanceEntry> entries) {
        Map<String, int[]> stats = new HashMap<>();
        Set<String> seen = new HashSet<>();
        String[][] marks = {
            {"class_work_mark", "Классная работа"}, {"control_work_mark", "Контрольная работа"},
            {"home_work_mark", "Домашняя работа"}, {"lab_work_mark", "Лабораторная работа"},
            {"practical_work_mark", "Практическая работа"}, {"mark", "Оценка за занятие"}
        };
        int idx = 0;
        for (JsonNode visit : extractList(root, "visits", "student_visits", "items")) {
            String subject = textOr(pick(visit, "spec_name", "subject_name", "name_spec"), "Предмет");
            String dateText = text(pick(visit, "date_visit", "date", "lesson_date"));
            LocalDate date = parseDate(dateText);
            String visitId = textOr(pick(visit, "lesson_id", "id"), "row-" + idx++);
            int[] total = stats.computeIfAbsent(subject, key -> new int[2]);
            total[1]++;
            String was = text(pick(visit, "status_was"));
            boolean visited = "1".equals(was) || "2".equals(was) || "true".equalsIgnoreCase(was);
            if (visited) total[0]++;
            // The source does not document the semantic difference between 1 and 2.
            // Only an explicit lateness indicator is interpreted as 'late'.
            String lateFlag = text(pick(visit, "is_late", "late", "was_late"));
            String lateMinutes = text(pick(visit, "late_minutes", "minutes_late"));
            boolean late = visited && ("true".equalsIgnoreCase(lateFlag)
                || "1".equals(lateFlag)
                || (lateMinutes.matches("[0-9]{1,4}") && Integer.parseInt(lateMinutes) > 0));
            AttendanceState state = visited ? (late ? AttendanceState.late : AttendanceState.present)
                : ("0".equals(was) || "false".equalsIgnoreCase(was) ? AttendanceState.absent : AttendanceState.unknown);
            if (date != null) entries.add(new AttendanceEntry(date, subject, visited, state, was, visitId));
            for (String[] f : marks) {
                Integer mark = parseMark(text(pick(visit, f[0])));
                if (mark == null || mark <= 0) continue;
                String id = visitId + "-" + f[0] + "-" + dateText;
                if (seen.add(id)) grades.add(new Grade(id, subject, mark, f[1],
                    textOr(pick(visit, "lesson_theme", "theme", "topic"), f[1]), date));
            }
        }
        stats.keySet().stream().sorted().forEach(subject -> attendance.add(
            new Attendance(subject, stats.get(subject)[0], stats.get(subject)[1])));
    }

    private JsonNode cachedGet(String key, String path, Map<String, ?> params) throws IOException {
        synchronized (cache) {
            Cached found = cache.get(key);
            if (found != null && System.currentTimeMillis() - found.at < cacheTtl) return (JsonNode) found.value;
        }
        JsonNode value = get(path, params);
        synchronized (cache) { cache.put(key, new Cached(System.currentTimeMillis(), value)); }
        return value;
    }

    private JsonNode get(String path, Map<String, ?> params) throws IOException {
        okhttp3.HttpUrl.Builder url = java.util.Objects.requireNonNull(okhttp3.HttpUrl.parse(baseUrl + path)).newBuilder();
        if (params != null) params.forEach((k, v) -> { if (v != null) url.addQueryParameter(k, String.valueOf(v)); });
        Request req = baseRequest(url.build().toString()).header("Authorization", "Bearer " + token).get().build();
        return execute(http, req);
    }

    /**
     * Запрашивает имя файла через HEAD. Не загружает содержимое; если HEAD не поддерживается,
     * интерфейс продолжит использовать имя из API либо URL.
     */
    @Override public AttachmentInfo inspectAttachment(String link, String apiFilename) throws IOException {
        if (!AttachmentLinks.isSafeHttpsLink(link)) throw new IOException("Некорректный HTTPS-адрес вложения.");
        String nextUrl = link;
        for (int redirectCount = 0; redirectCount < 5; redirectCount++) {
            if (!AttachmentLinks.isSafeHttpsLink(nextUrl)) throw new IOException("Небезопасный адрес перенаправления.");
            URI uri = URI.create(nextUrl);
            Request.Builder builder = attachmentRequest(nextUrl);
            try (Response response = attachmentHttp.newCall(builder.head().build()).execute()) {
                int code = response.code();
                if (code >= 300 && code < 400) {
                    nextUrl = redirectTarget(uri, response);
                    continue;
                }
                if (!response.isSuccessful()) throw new IOException("HEAD-запрос не поддержан сервером (HTTP " + code + ").");
                String contentType = response.header("Content-Type", "");
                String suggested = AttachmentLinks.resolveFilename(link, apiFilename,
                    response.header("Content-Disposition", ""), contentType, "вложение");
                return new AttachmentInfo(suggested, contentType);
            }
        }
        throw new IOException("Слишком много перенаправлений при запросе имени файла.");
    }

    private Request.Builder attachmentRequest(String url) {
        Request.Builder builder = baseRequest(url);
        // Никогда не передаём токен сторонним доменам, даже при перенаправлении.
        if ("msapi.top-academy.ru".equalsIgnoreCase(URI.create(url).getHost())) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static String redirectTarget(URI from, Response response) throws IOException {
        String location = response.header("Location");
        if (location == null || location.isBlank()) throw new IOException("Пустой адрес перенаправления.");
        String resolved = from.resolve(location).toString();
        if (!AttachmentLinks.isSafeHttpsLink(resolved)) throw new IOException("Перенаправление на небезопасный адрес.");
        return resolved;
    }

    /** Совместимость с первоначальным методом API (сохраняет возможность вызова напрямую). */
    @Override public void downloadAttachment(String link, Path destination) throws IOException {
        downloadAttachmentNamed(link, destination, "");
    }

    /**
     * Скачивает вложение в фоновом потоке вызывающего кода. Имя/расширение восстанавливает
     * из Content-Disposition, данных API, пути URL и Content-Type (в таком порядке).
     * Если пользователь выбрал имя без расширения, оно добавляется автоматически.
     */
    @Override public Path downloadAttachmentNamed(String link, Path destination, String apiFilename) throws IOException {
        if (!AttachmentLinks.isSafeHttpsLink(link)) throw new IOException("Некорректный HTTPS-адрес вложения.");
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Папка для сохранения не существует.");
        Path temporary = Files.createTempFile(parent, ".javajournal-", ".part");
        boolean completed = false;
        try {
            String nextUrl = link;
            for (int redirectCount = 0; redirectCount < 5; redirectCount++) {
                if (!AttachmentLinks.isSafeHttpsLink(nextUrl)) throw new IOException("Небезопасный адрес перенаправления.");
                URI uri = URI.create(nextUrl);
                try (Response response = attachmentHttp.newCall(attachmentRequest(nextUrl).get().build()).execute()) {
                    int code = response.code();
                    if (code >= 300 && code < 400) {
                        nextUrl = redirectTarget(uri, response);
                        continue;
                    }
                    if (!response.isSuccessful()) throw new IOException("Ошибка скачивания: HTTP " + code);
                    if (response.body() == null) throw new IOException("Сервер вернул пустой ответ.");
                    String actualName = AttachmentLinks.resolveFilename(link, apiFilename,
                        response.header("Content-Disposition", ""), response.header("Content-Type", ""), "вложение");
                    Path finalPath = AttachmentLinks.withMissingExtension(absolute, actualName);
                    if (!finalPath.equals(absolute) && Files.exists(finalPath)) {
                        throw new IOException("Файл " + finalPath.getFileName() + " уже существует. Укажи другое имя.");
                    }
                    long maxBytes = 100L * 1024 * 1024;
                    if (response.body().contentLength() > maxBytes) throw new IOException("Файл больше 100 МБ.");
                    try (InputStream input = response.body().byteStream();
                         var output = Files.newOutputStream(temporary)) {
                        byte[] buffer = new byte[8192];
                        long total = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > maxBytes) throw new IOException("Файл больше 100 МБ.");
                            output.write(buffer, 0, count);
                        }
                    }
                    if (finalPath.equals(absolute)) {
                        // Если ни URL, ни сервер не указали тип, проверяем сигнатуру файла.
                        String detected = AttachmentLinks.extensionFromContent(temporary);
                        if (!detected.isBlank()) {
                            Path detectedPath = AttachmentLinks.withMissingExtension(absolute, "файл" + detected);
                            if (!detectedPath.equals(absolute) && Files.exists(detectedPath)) {
                                throw new IOException("Файл " + detectedPath.getFileName() + " уже существует. Укажи другое имя.");
                            }
                            finalPath = detectedPath;
                        }
                    }
                    Files.move(temporary, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    completed = true;
                    return finalPath;
                }
            }
            throw new IOException("Слишком много перенаправлений при скачивании.");
        } finally {
            if (!completed) Files.deleteIfExists(temporary);
        }
    }

    /**
     * Исправление 0.3.3: имя определяется по настоящему GET-ответу, а не по
     * предварительному HEAD или первоначальному URL. Файлы не перезаписываем.
     */
    @Override public Path downloadAttachmentToDirectory(String link, Path directory, String apiFilename) throws IOException {
        if (!AttachmentLinks.isSafeHttpsLink(link)) throw new IOException("Некорректный HTTPS-адрес вложения.");
        Path folder = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) throw new IOException("Папка для сохранения не существует.");
        Path temporary = Files.createTempFile(folder, ".javajournal-", ".part");
        boolean completed = false;
        try {
            String nextUrl = link;
            for (int redirects = 0; redirects < 5; redirects++) {
                if (!AttachmentLinks.isSafeHttpsLink(nextUrl)) throw new IOException("Небезопасный адрес перенаправления.");
                URI uri = URI.create(nextUrl);
                try (Response response = attachmentHttp.newCall(attachmentRequest(nextUrl).get().build()).execute()) {
                    int statusCode = response.code();
                    if (statusCode >= 300 && statusCode < 400) {
                        nextUrl = redirectTarget(uri, response);
                        continue;
                    }
                    if (!response.isSuccessful()) throw new IOException("Ошибка скачивания: HTTP " + statusCode);
                    if (response.body() == null) throw new IOException("Сервер вернул пустой ответ.");
                    String mime = response.header("Content-Type", "");
                    String simplifiedMime = mime.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                    if (simplifiedMime.equals("text/html") || simplifiedMime.equals("application/xhtml+xml")) {
                        throw new IOException("Сервер вернул HTML вместо файла. Проверь авторизацию.");
                    }
                    String filename = AttachmentLinks.resolveFilename(nextUrl, apiFilename,
                        response.header("Content-Disposition", ""), mime, "вложение");
                    long maxBytes = 100L * 1024 * 1024;
                    if (response.body().contentLength() > maxBytes) throw new IOException("Файл больше 100 МБ.");
                    try (InputStream input = response.body().byteStream();
                         java.io.OutputStream output = Files.newOutputStream(temporary)) {
                        byte[] buffer = new byte[8192];
                        long total = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > maxBytes) throw new IOException("Файл больше 100 МБ.");
                            output.write(buffer, 0, count);
                        }
                    }
                    if (simplifiedMime.equals("application/json") && !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                        throw new IOException("Сервер вернул JSON вместо вложения. Возможно, ссылка на файл устарела.");
                    }
                    String detected = AttachmentLinks.extensionFromContent(temporary);
                    if (!detected.isBlank() && !filename.matches("(?i).*\\.[a-z0-9]{1,10}$")) filename += detected;
                    Path output = AttachmentLinks.unusedPath(folder, filename);
                    Files.move(temporary, output);
                    completed = true;
                    return output;
                }
            }
            throw new IOException("Слишком много перенаправлений при скачивании.");
        } finally {
            if (!completed) Files.deleteIfExists(temporary);
        }
    }

    private static Request.Builder baseRequest(String url) {
        return new Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ru_RU, ru")
            .header("Origin", "https://journal.top-academy.ru")
            .header("Referer", "https://journal.top-academy.ru/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36");
    }

    private static JsonNode execute(OkHttpClient client, Request req) throws IOException {
        try (Response response = client.newCall(req).execute()) {
            int status = response.code();
            if (status == 401 || status == 403 || (req.url().encodedPath().endsWith("/auth/login") && status == 400)) {
                throw new AuthException("Вход отклонён или сессия истекла (HTTP " + status + ").");
            }
            if (!response.isSuccessful()) throw new IOException("Ошибка сервера TOP Academy: HTTP " + status);
            if (response.body() == null) throw new IOException("Сервер вернул пустой ответ.");
            try { return jsonMapper.readTree(response.body().string()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IOException("Неверный формат ответа API.", e); }
        }
    }

    public static final class AuthException extends IOException {
        public AuthException(String message) { super(message); }
    }
    private static boolean isAuth(IOException e) { return e instanceof AuthException; }

    private static String findToken(JsonNode node, int depth) {
        if (node == null || !node.isObject() || depth > 4) return "";
        String token = text(pick(node, "access_token", "token"));
        if (!token.isBlank()) return token;
        for (String key : List.of("data", "result", "auth", "payload")) {
            String found = findToken(pick(node, key), depth + 1);
            if (!found.isBlank()) return found;
        }
        return "";
    }
    private static JsonNode pick(JsonNode node, String... names) {
        if (node == null || !node.isObject()) return jsonMapper.missingNode();
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull() && !(value.isTextual() && value.asText().isBlank())) return value;
        }
        return jsonMapper.missingNode();
    }
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(n -> { String s = text(n); if (!s.isBlank()) parts.add(s); });
            return String.join(", ", parts);
        }
        if (node.isObject()) return text(pick(node, "name", "title", "fio", "fio_teach", "full_name", "teacher_name", "room_name", "value"));
        return node.asText("").replace("\\n", " ").replace("\\r", " ").replaceAll("\\s+", " ").strip();
    }
    private static String textOr(JsonNode node, String fallback) {
        String s = text(node); return s.isBlank() ? fallback : s;
    }
    private static List<JsonNode> extractList(JsonNode node, String... preferred) {
        if (node == null) return List.of();
        if (node.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            node.forEach(result::add);
            return result;
        }
        if (!node.isObject()) return List.of();
        List<String> keys = new ArrayList<>(List.of(preferred));
        keys.addAll(List.of("homework_list", "lesson_list", "lessons", "visits", "items", "list", "result", "data"));
        for (String key : keys) {
            JsonNode child = node.path(key);
            if (child.isArray()) return extractList(child);
            if (child.isObject()) {
                List<JsonNode> result = extractList(child, preferred);
                if (!result.isEmpty()) return result;
            }
        }
        return List.of();
    }
    private static String timeText(JsonNode value) {
        Matcher matcher = timePattern.matcher(text(value));
        if (!matcher.find()) return "";
        String result = matcher.group(1);
        return result.length() == 4 ? "0" + result : result;
    }
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 10) return null;
        try { return LocalDate.parse(raw.substring(0, 10)); }
        catch (RuntimeException e) { return null; }
    }
    private static Integer parseMark(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { int n = (int) Double.parseDouble(raw.replace(',', '.')); return n > 0 ? n : null; }
        catch (NumberFormatException e) { return null; }
    }
}

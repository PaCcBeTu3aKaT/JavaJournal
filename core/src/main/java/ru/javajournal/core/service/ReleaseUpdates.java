package ru.javajournal.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Проверка опубликованных GitHub Releases. Только чтение публичного API;
 * личные токены и данные из журнала сюда не передаются.
 */
public final class ReleaseUpdates {
    private static final Pattern repositoryPattern = Pattern.compile("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern versionPattern = Pattern.compile("[vV]?\\d+(?:\\.\\d+){0,3}");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build();

    private ReleaseUpdates() { }

    public record Release(String version, boolean available, String pageUrl, String assetName, String downloadUrl) { }

    /** Принимает owner/repo либо адрес github.com/owner/repo. */
    public static String normalizeRepository(String value) {
        String repo = value == null ? "" : value.strip();
        if (repo.startsWith("https://github.com/")) repo = repo.substring("https://github.com/".length());
        else if (repo.startsWith("http://") || repo.startsWith("https://"))
            throw new IllegalArgumentException("Укажите публичный репозиторий GitHub.");
        while (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
        if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        if (!repositoryPattern.matcher(repo).matches() || repo.contains(".."))
            throw new IllegalArgumentException("Введите owner/repo или https://github.com/owner/repo.");
        return repo;
    }

    public static int compareVersions(String current, String latest) {
        if (!versionPattern.matcher(current).matches() || !versionPattern.matcher(latest).matches())
            throw new IllegalArgumentException("Неподдерживаемый номер версии GitHub Release.");
        String[] left = current.replaceFirst("^[vV]", "").split("\\.");
        String[] right = latest.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long x = i < left.length ? Long.parseLong(left[i]) : 0;
            long y = i < right.length ? Long.parseLong(right[i]) : 0;
            int difference = Long.compare(x, y);
            if (difference != 0) return difference;
        }
        return 0;
    }

    /** Выполняется в фоновом потоке, никогда в JavaFX Application Thread. */
    public static Release checkLatest(String repository, String currentVersion,
                                      String osName, String architecture) throws IOException {
        String repo = normalizeRepository(repository);
        Request request = new Request.Builder()
            .url("https://api.github.com/repos/" + repo + "/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "JavaJournal-Updater")
            .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404) throw new IOException("Публичные релизы GitHub ещё не опубликованы.");
            if (response.code() == 403 || response.code() == 429)
                throw new IOException("GitHub временно ограничил запросы. Повторите позже.");
            if (!response.isSuccessful() || response.body() == null)
                throw new IOException("Ошибка GitHub: HTTP " + response.code());
            return parseRelease(response.body().string(), repo, currentVersion, osName, architecture);
        }
    }

    /** Отдельный парсер упрощает проверку без сетевых запросов. */
    public static Release parseRelease(String json, String repository, String currentVersion,
                                       String osName, String architecture) throws IOException {
        String repo = normalizeRepository(repository);
        JsonNode release = mapper.readTree(json);
        if (release == null || release.isNull() || release.path("draft").asBoolean(false)
            || release.path("prerelease").asBoolean(false))
            throw new IOException("Последний стабильный релиз GitHub недоступен.");
        String latest = release.path("tag_name").asText("").strip();
        boolean available;
        try { available = compareVersions(currentVersion, latest) < 0; }
        catch (RuntimeException error) { throw new IOException("У релиза неподдерживаемый номер версии: " + latest, error); }
        String fallbackUrl = "https://github.com/" + repo + "/releases/latest";
        String releaseUrl = trustedGitHubUrl(release.path("html_url").asText(""), fallbackUrl);
        String assetUrl = "";
        String assetName = "";
        int highestScore = -1;
        JsonNode assets = release.path("assets");
        if (assets.isArray()) for (JsonNode asset : assets) {
            String name = asset.path("name").asText("");
            String candidate = asset.path("browser_download_url").asText("");
            if (trustedGitHubUrl(candidate, "").isBlank()) continue;
            int score = scoreAsset(name, osName, architecture);
            if (score > highestScore) {
                highestScore = score;
                assetName = name;
                assetUrl = candidate;
            }
        }
        return new Release(latest, available, releaseUrl, assetName, assetUrl);
    }

    private static String trustedGitHubUrl(String raw, String fallback) {
        try {
            URI uri = URI.create(raw);
            if ("https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && "github.com".equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1) return uri.toString();
        } catch (IllegalArgumentException ignored) { }
        return fallback;
    }

    private static int scoreAsset(String filename, String osName, String architecture) {
        String file = filename.toLowerCase(Locale.ROOT);
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        if (!file.endsWith(".zip") || file.contains("source") || file.contains("legacy")) return -1;
        if (os.contains("windows")) {
            if (!(file.contains("windows") || file.contains("win-"))) return -1;
            return file.contains("portable") ? 4 : 2;
        }
        if (os.contains("mac")) {
            if (!(file.contains("macos") || file.contains("mac-"))) return -1;
            boolean arm = arch.contains("aarch64") || arch.contains("arm");
            boolean targetArm = file.contains("arm64") || file.contains("aarch64") || file.contains("apple");
            boolean targetIntel = file.contains("intel") || file.contains("x64") || file.contains("x86_64");
            if (arm && targetArm) return 5;
            if (!arm && targetIntel) return 5;
            return -1;
        }
        return -1;
    }
}
